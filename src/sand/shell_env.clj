(ns sand.shell-env
  "Captures the environment of a .sand nix-shell and caches it, so that
   commands can run in that environment without evaluating nix again.

   Evaluating nixpkgs takes seconds, while everything else sand does takes
   milliseconds, so the cache is what makes sand fast. The cache is keyed by
   everything that affects the evaluation that sand can cheaply observe.
   Each entry is a file in the store that references the shell's inputs,
   linked to from the cache dir, so nix keeps an entry exactly as long as
   it keeps the paths the entry needs."
  (:require
   [babashka.fs :as fs]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.java.process :as p]
   [clojure.string :as str]
   [sand.util :as u])
  (:import
   (java.io File)
   (java.nio.charset StandardCharsets)
   (java.nio.file.attribute FileTime)
   (java.security MessageDigest)))

(set! *warn-on-reflection* true)

; Bump when the cache format or the meaning of its contents changes.
(def ^:private ^:const cache-version "2")

(def ^:private excluded-vars
  "Variables that nix-shell sets which are specific to its process, such as
   the temp dir that it deletes on exit, and so must not be reused."
  #{"NIX_BUILD_SHELL" "NIX_BUILD_TOP" "OLDPWD" "PWD" "SHLVL"
    "TEMP" "TEMPDIR" "TMP" "TMPDIR" "_"})

(def ^:private nixpkgs-env-vars
  "Variables that change what `import nixpkgs {}` evaluates to."
  ["NIXPKGS_ALLOW_BROKEN" "NIXPKGS_ALLOW_INSECURE" "NIXPKGS_ALLOW_UNFREE"
   "NIXPKGS_ALLOW_UNSUPPORTED_SYSTEM" "NIXPKGS_CONFIG"])

(defn- getenv ^String [k]
  (System/getenv ^String k))

(defn- home-path [& more]
  (apply fs/path (System/getProperty "user.home") more))

(defn- cache-dir []
  (fs/path
    (or (not-empty (getenv "SAND_CACHE_DIR"))
      (fs/path
        (or (not-empty (getenv "XDG_CACHE_HOME")) (home-path ".cache"))
        "sand"))
    "shell-env"))

(defn- sha256-hex [^String s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                 (.getBytes s StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn- path-state
  "A string that changes when the file or dir at path changes."
  [path]
  (str path "="
    (when (fs/exists? path)
      (str (fs/canonicalize path) "@" (.toMillis ^FileTime (fs/last-modified-time path))))))

(defn- nix-path-states
  "States of the paths that <nixpkgs> may resolve to. Channels are symlinks
   into the store, so updating a channel changes its canonical path.
   flake: entries in NIX_PATH are only tracked by their text."
  []
  (let [nix-path (getenv "NIX_PATH")]
    (cons (str "NIX_PATH=" nix-path)
      (map path-state
        (concat
          (for [entry (some-> nix-path (str/split #":"))
                :let [path (str/replace entry #"^[^=/]*=" "")]
                :when (str/starts-with? path "/")]
            path)
          [(home-path ".nix-defexpr" "channels")
           "/nix/var/nix/profiles/per-user/root/channels"])))))

(defn- split-nix-path
  "Splits NIX_PATH into entries. Entries may be URLs or flake refs that
   contain colons, like flake:nixpkgs or https://..., so a part that looks
   like a URL scheme is joined with the part after it."
  [nix-path]
  (reduce
    (fn [entries part]
      (let [prev (peek entries)]
        (if (and prev (re-matches #"(?:[^=/]*=)?[a-zA-Z][a-zA-Z0-9+.-]*" prev))
          (conj (pop entries) (str prev ":" part))
          (conj entries part))))
    []
    (str/split nix-path #":")))

(defn nix-env
  "Returns environment variables to run nix with. nix warns about each
   NIX_PATH entry that doesn't exist and then ignores it, so those entries
   are removed to avoid the warnings without changing any behavior."
  []
  (let [nix-path (getenv "NIX_PATH")]
    (when (seq nix-path)
      (let [entries (split-nix-path nix-path)
            kept (remove
                   (fn [entry]
                     (let [path (str/replace entry #"^[^=/]*=" "")]
                       (and (str/starts-with? path "/")
                         (not (fs/exists? path)))))
                   entries)]
        (when (not= entries kept)
          {"NIX_PATH" (str/join ":" kept)})))))

(defn cache-key
  "Returns a hash of everything known to affect the evaluation of a shell
   with the given shell.nix and sand.json contents."
  [shell-nix sand-json]
  (let [pinned? (contains? (json/read-str sand-json) "nixpkgs")]
    (sha256-hex
      (str/join "\u0000"
        (concat
          [cache-version sand-json shell-nix]
          (for [k nixpkgs-env-vars]
            (str k "=" (getenv k)))
          (map path-state
            [(home-path ".config" "nixpkgs" "config.nix")
             (home-path ".config" "nixpkgs" "overlays")
             (home-path ".config" "nixpkgs" "overlays.nix")
             (home-path ".nixpkgs" "config.nix")])
          (when-not pinned?
            (nix-path-states)))))))

(defn- parse-env-0
  "Parses the output of `env -0` into a map."
  [^String s]
  (into {}
    (keep
      (fn [^String entry]
        (let [i (.indexOf entry "=")]
          (when (pos? i)
            [(subs entry 0 i) (subs entry (inc i))]))))
    (str/split s #"\u0000")))

(defn env-diff
  "Returns the changes that nix-shell made to the outer environment.
   Values that nix-shell extended with a :-separated prefix or suffix,
   like PATH, are stored as that prefix or suffix, so that they can be
   applied to a different outer value later."
  [outer captured]
  (into (sorted-map)
    (for [[k v] captured
          :let [ov (get outer k)]
          :when (and (not (excluded-vars k)) (not= v ov))]
      [k (cond
           (and (seq ov) (str/ends-with? v (str ":" ov)))
           {"prefix" (subs v 0 (- (count v) (count ov) 1))}

           (and (seq ov) (str/starts-with? v (str ov ":")))
           {"suffix" (subs v (inc (count ov)))}

           :else
           {"value" v})])))

(defn apply-diff
  "Returns the environment variables to set to apply diff to outer."
  [outer diff]
  (into {}
    (for [[k {:strs [prefix suffix value]}] diff
          :let [ov (get outer k)]]
      [k (cond
           prefix (if (seq ov) (str prefix ":" ov) prefix)
           suffix (if (seq ov) (str ov ":" suffix) suffix)
           :else value)])))

(defn- store-paths
  "Returns the existing store paths referenced by the values of diff.
   These are the same paths that the shell's inputDerivation references."
  [captured diff]
  (let [store-dir (or (get captured "NIX_STORE") "/nix/store")
        re (re-pattern (str (java.util.regex.Pattern/quote store-dir)
                         "/[0-9a-z]{32}-[A-Za-z0-9+\\-._?=]+"))]
    (->> (vals diff)
      (mapcat vals)
      (mapcat #(re-seq re %))
      distinct
      (filter fs/exists?)
      sort
      vec)))

(defn- capture!
  "Runs nix-shell to capture the environment of a shell with the given
   shell.nix and sand.json contents. This is the only step that evaluates
   nix. It runs in a temp dir, so that .sand/sand.json is only changed
   after the evaluation has succeeded."
  [shell-nix sand-json]
  (fs/with-temp-dir [tmp {:prefix "sand"}]
    (spit (fs/file tmp "shell.nix") shell-nix)
    (spit (fs/file tmp "sand.json") sand-json)
    (let [env-file (str (fs/path tmp "env"))
          ; Without NIX_BUILD_SHELL, nix-shell evaluates <nixpkgs> a second
          ; time just to find bashInteractive, which takes seconds.
          build-shell (or (not-empty (getenv "NIX_BUILD_SHELL"))
                        (not-empty (getenv "SAND_BASH"))
                        "bash")
          proc (p/start
                 {:env (assoc (nix-env) "NIX_BUILD_SHELL" build-shell)
                  :err :inherit
                  :out :inherit}
                 "nix-shell" (str (fs/path tmp "shell.nix"))
                 "--run" (str "env -0 > '" (str/replace env-file "'" "'\"'\"'") "'"))
          exit @(p/exit-ref proc)]
      (when-not (zero? exit)
        (throw (u/user-error (str "nix-shell exited with code " exit) exit)))
      (parse-env-0 (slurp env-file)))))

(defn- roots-parent-dir [dot-sand-dir]
  (fs/path dot-sand-dir "gcroots" "shell-env"))

(defn- root-path
  "Returns the path of the GC root for the cache entry key. Each key gets
   its own root, so that concurrent runs never delete a root that another
   run is registering or relying on."
  [dot-sand-dir key]
  (fs/path (roots-parent-dir dot-sand-dir) (subs key 0 16)))

(defn- rooted?
  "Returns true if the root for the cache entry key is registered."
  [dot-sand-dir key]
  (fs/exists? (root-path dot-sand-dir key)))

(defn- start-rooting!
  "Starts registering entry as the GC root for the cache entry key.
   Returns the process."
  [dot-sand-dir key entry]
  (fs/create-dirs (roots-parent-dir dot-sand-dir))
  (p/start
    {:err :inherit :out :discard}
    "nix-store" "--realise" "--add-root" (str (root-path dot-sand-dir key))
    entry))

(defn- delete-other-roots!
  "Deletes GC roots other than the one for the cache entry key, including
   those from older versions of sand. Errors are ignored, since a
   concurrent run may be deleting the same files."
  [dot-sand-dir key]
  (let [keep-path (root-path dot-sand-dir key)
        parent (roots-parent-dir dot-sand-dir)]
    (doseq [path (concat
                   ; Older versions of sand rooted the shell's inputDerivation here.
                   [(fs/path dot-sand-dir "gcroots" "shell")]
                   (try (fs/list-dir parent) (catch Exception _ nil)))
            :when (not= keep-path path)]
      (try
        (if (fs/directory? path {:nofollow-links true})
          (fs/delete-tree path)
          (fs/delete-if-exists path))
        (catch Exception _ nil)))))

(def ^:private entry-expr
  "Writes the cache entry to the store with the store paths that it uses
   as references, so that rooting the entry keeps them alive, and garbage
   collecting it lets them be collected."
  "let
     a = builtins.fromJSON (builtins.readFile (builtins.getEnv \"SAND_ENTRY_FILE\"));
   in builtins.toFile \"sand-shell-env.json\" (builtins.appendContext a.json
     (builtins.listToAttrs (map (p: { name = p; value = { path = true; }; }) a.paths)))")

(defn- add-entry!
  "Adds the cache entry m to the store, referencing paths, and returns its
   store path. When root is given, the entry is registered as a GC root
   there by the same nix process, so that it is never unrooted."
  [m paths root]
  (fs/with-temp-dir [tmp {:prefix "sand"}]
    (let [f (fs/path tmp "entry.json")
          _ (spit (fs/file f) (json/write-str {"json" (json/write-str m) "paths" paths}))
          proc (apply p/start
                 {:env (assoc (nix-env) "SAND_ENTRY_FILE" (str f))
                  :err :inherit}
                 "nix" "--extra-experimental-features" "nix-command"
                 "build" "--impure" "--print-out-paths" "--expr" entry-expr
                 (if root
                   ["--out-link" (str root)]
                   ["--no-link"]))
          ; stderr is inherited, so reading stdout to the end can't block on it
          out (slurp (p/stdout proc))
          exit @(p/exit-ref proc)]
      (when-not (zero? exit)
        (throw (u/user-error (str "nix build exited with code " exit) exit)))
      (str/trim out))))

(defn- link-entry!
  "Points the cache's link for key at entry, replacing it atomically."
  [key entry]
  (let [dir (cache-dir)
        tmp (fs/path dir (str key "." (random-uuid) ".tmp"))]
    (fs/create-dirs dir)
    (fs/create-sym-link tmp entry)
    (try
      (fs/move tmp (fs/path dir key) {:atomic-move true :replace-existing true})
      (finally
        (fs/delete-if-exists tmp)))))

(defn- read-cache
  "Returns [entry m] for the cache entry for key, where entry is its store
   path and m its contents, or nil if it is missing or was garbage
   collected."
  [key]
  (let [link (fs/path (cache-dir) key)]
    (when (fs/exists? link)
      (let [entry (str (fs/read-link link))
            m (try
                (with-open [rdr (io/reader (fs/file entry))]
                  (json/read rdr))
                (catch Exception _ nil))]
        (when (= cache-version (get m "version"))
          [entry m])))))

(defn shell-env!
  "Returns the environment of a shell for dot-sand-dir's shell.nix with
   sand-json as the contents of its sand.json, as a map whose :env holds
   the variables to set on top of the current environment. sand-json may
   differ from the sand.json in dot-sand-dir, which is left unchanged.

   Evaluates nix only if there is no valid cache entry. When root? is true,
   the cache entry, which references the shell's inputs, is registered as
   a GC root in dot-sand-dir if it isn't already. For an existing entry,
   that runs in the background: call `finish!` on the result to wait for
   it."
  [dot-sand-dir sand-json {:keys [root?]}]
  (let [shell-nix (slurp (fs/file dot-sand-dir "shell.nix"))
        key (cache-key shell-nix sand-json)
        outer (into {} (System/getenv))]
    (if-let [[entry {:strs [diff]}] (read-cache key)]
      {:env (apply-diff outer diff)
       :rooting (when (and root? (not (rooted? dot-sand-dir key)))
                  {:dot-sand-dir dot-sand-dir
                   :key key
                   :proc (start-rooting! dot-sand-dir key entry)})}
      (let [captured (capture! shell-nix sand-json)
            diff (env-diff outer captured)
            root (when root?
                   (fs/create-dirs (roots-parent-dir dot-sand-dir))
                   (root-path dot-sand-dir key))
            entry (add-entry! {"diff" diff "version" cache-version}
                    (store-paths captured diff)
                    root)]
        (link-entry! key entry)
        (when root?
          (delete-other-roots! dot-sand-dir key))
        {:env (apply-diff outer diff)}))))

(defn finish!
  "Waits for any background work started by shell-env!."
  [{:keys [rooting]}]
  (when-let [{:keys [dot-sand-dir key proc]} rooting]
    (let [exit @(p/exit-ref proc)]
      (if (zero? exit)
        (delete-other-roots! dot-sand-dir key)
        (binding [*out* *err*]
          (println "sand: warning: failed to register GC roots, exit code" exit))))))

(defn resolve-command
  "Resolves cmd against the PATH in env, like a shell would, returning nil
   if it isn't found. The JVM resolves commands against its own PATH, not
   the child's."
  [env cmd]
  (if (str/includes? cmd "/")
    cmd
    (some
      (fn [dir]
        (let [^File f (fs/file (if (empty? dir) "." dir) cmd)]
          (when (and (.isFile f) (.canExecute f))
            (str f))))
      (str/split (or (get env "PATH") (getenv "PATH") "") #":"))))
