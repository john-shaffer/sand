(ns sand.sandbox
  "Runs formatters in a bubblewrap sandbox: no network, a cleared
   environment, an empty home dir, and read-only access to /nix/store and
   the root dir (the git repo, or the dir sand runs in). Writes go to an
   overlay, and only the files being formatted are copied back."
  (:require
   [babashka.fs :as fs]
   [clojure.java.process :as p]
   [sand.shell-env :as shell-env])
  (:import
   (java.nio.file CopyOption Files LinkOption Path StandardCopyOption)))

(def ^:private home "/tmp/home")

(defn bwrap-command
  "Returns the bwrap command to run, or nil if it isn't found."
  []
  (shell-env/resolve-command {}
    (or (not-empty (System/getenv "SAND_BWRAP")) "bwrap")))

(defn env
  "Returns the environment for a sandboxed formatter. It has only the PATH
   from the nix shell diff, a few basics, and the formatter's own env."
  [diff formatter-env]
  (merge
    {"HOME" home
     ; GHC programs like nixfmt fail on non-ASCII input without a UTF-8
     ; locale.
     "LANG" "C.UTF-8"
     "TMPDIR" "/tmp"}
    (select-keys (shell-env/apply-diff {} diff) ["PATH"])
    formatter-env))

(defn- ids
  "Returns the [uid gid] that own path, which this process created."
  [^Path path]
  (let [attr #(Files/getAttribute path ^String % (make-array LinkOption 0))]
    [(attr "unix:uid") (attr "unix:gid")]))

(defn- bwrap-args
  "Returns the args for bwrap to run resolved-cmd, before its own args."
  [{:keys [cmd-env root tmp]} resolved-cmd]
  (let [[uid gid] (ids tmp)
        passwd (fs/path tmp "passwd")
        group (fs/path tmp "group")
        sh (some-> (shell-env/resolve-command cmd-env "sh") fs/canonicalize str)
        env-bin (some-> (shell-env/resolve-command cmd-env "env") fs/canonicalize str)]
    ; NuGet, for one, fails when the uid has no passwd entry.
    (spit (fs/file passwd) (str "sand:x:" uid ":" gid "::" home ":/bin/sh\n"))
    (spit (fs/file group) (str "sand:x:" gid ":\n"))
    (concat
      ["--unshare-all" "--die-with-parent" "--new-session" "--clearenv"]
      (mapcat (fn [[k v]] ["--setenv" k v]) cmd-env)
      ["--ro-bind" "/nix/store" "/nix/store"
       "--proc" "/proc"
       "--dev" "/dev"
       "--tmpfs" "/tmp"
       "--dir" home
       "--ro-bind" (str passwd) "/etc/passwd"
       "--ro-bind" (str group) "/etc/group"]
      ; Wrapper scripts like clang-format's start with #!/bin/sh
      (when sh ["--symlink" sh "/bin/sh"])
      (when env-bin ["--symlink" env-bin "/usr/bin/env"])
      ; After the /tmp tmpfs, since root may be under /tmp
      ["--overlay-src" (str root)
       "--overlay" (str (fs/path tmp "upper")) (str (fs/path tmp "work")) (str root)
       "--chdir" (str root)
       "--" resolved-cmd])))

(defn- copy-back!
  "Atomically replaces each of fnames with its version in the overlay's
   upper dir, if the formatter changed it. Other changes are discarded."
  [root upper fnames]
  (doseq [fname fnames
          :let [target ^Path (fs/path fname)
                changed ^Path (fs/path upper (fs/relativize root target))]
          :when (fs/exists? changed {:nofollow-links true})]
    (if (fs/regular-file? changed {:nofollow-links true})
      (when (not= -1 (Files/mismatch changed target))
        (let [tmp ^Path (fs/create-temp-file {:dir (fs/parent target)
                                              :prefix ".sand-"})]
          (try
            (Files/copy changed tmp
              ^"[Ljava.nio.file.CopyOption;"
              (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
            (fs/set-posix-file-permissions tmp (fs/posix-file-permissions target))
            (Files/move tmp target
              ^"[Ljava.nio.file.CopyOption;"
              (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                      StandardCopyOption/REPLACE_EXISTING]))
            (finally
              (fs/delete-if-exists tmp)))))
      (binding [*out* *err*]
        (println (str "sand: warning: the formatter removed or replaced "
                   fname ", so it was left unchanged"))))))

(defn run!
  "Runs resolved-cmd with args in a sandbox, in the canonical dir root,
   then copies back the formatted versions of fnames, which must be
   canonical paths under root. Returns the exit code."
  [{:keys [bwrap cmd-env fnames root]} resolved-cmd args]
  (let [tmp (fs/create-temp-dir {:prefix "sand-sandbox"})
        work (fs/path tmp "work")]
    (try
      (fs/create-dirs (fs/path tmp "upper"))
      (fs/create-dirs work)
      (let [proc (apply p/start
                   {:err :inherit :out :inherit}
                   bwrap
                   (concat
                     (bwrap-args {:cmd-env cmd-env :root root :tmp tmp}
                       resolved-cmd)
                     args))
            exit-code @(p/exit-ref proc)]
        ; Even after a failure, like any formatter that formats the files
        ; it can and exits nonzero for the rest
        (copy-back! root (fs/path tmp "upper") fnames)
        exit-code)
      (finally
        ; overlayfs leaves a dir in work with no permissions
        (doseq [d (fs/list-dir work)]
          (fs/set-posix-file-permissions d "rwx------"))
        (fs/delete-tree tmp)))))

(defn outside-root
  "Returns the fnames that aren't under root, which a sandbox can't reach."
  [root fnames]
  (remove #(fs/starts-with? (fs/canonicalize %) root) fnames))
