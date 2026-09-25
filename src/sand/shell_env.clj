(ns sand.shell-env
  "Captures the environment of a .sand nix-shell and caches it, so that
   commands can run in that environment without evaluating nix again.

   Evaluating nixpkgs takes seconds, while everything else sand does takes
   milliseconds, so the cache is what makes sand fast. The cache is keyed by
   everything that affects the evaluation that sand can cheaply observe, and
   an entry is only used while all of the store paths it references exist."
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
(def ^:private ^:const cache-version "1")

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
  "Returns a hash of everything known to affect the shell's evaluation."
  [dot-sand-dir]
  (let [sand-json (slurp (fs/file dot-sand-dir "sand.json"))
        pinned? (contains? (json/read-str sand-json) "nixpkgs")]
    (sha256-hex
      (str/join "\u0000"
        (concat
          [cache-version
           sand-json
           (slurp (fs/file dot-sand-dir "shell.nix"))]
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
  "Runs nix-shell to capture the environment of dot-sand-dir's shell.nix.
   This is the only step that evaluates nix."
  [dot-sand-dir]
  (fs/with-temp-dir [tmp {:prefix "sand"}]
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
                 "nix-shell" (str (fs/path dot-sand-dir "shell.nix"))
                 "--run" (str "env -0 > '" (str/replace env-file "'" "'\"'\"'") "'"))
          exit @(p/exit-ref proc)]
      (when-not (zero? exit)
        (throw (ex-info "nix-shell failed" {:exit exit})))
      (parse-env-0 (slurp env-file)))))

(defn- roots-parent-dir [dot-sand-dir]
  (fs/path dot-sand-dir "gcroots" "shell-env"))

(defn- roots-dir
  "Returns the dir holding the GC roots for the cache entry key. Each key
   gets its own dir, so that concurrent runs never delete roots that
   another run is registering or relying on."
  [dot-sand-dir key]
  (fs/path (roots-parent-dir dot-sand-dir) (subs key 0 16)))

(defn- rooted?
  "Returns true if the roots for the cache entry key are registered."
  [dot-sand-dir key]
  (fs/exists? (fs/path (roots-dir dot-sand-dir key) "done")))

(defn- start-rooting!
  "Starts registering paths as GC roots for the cache entry key.
   Returns the process."
  [dot-sand-dir key paths]
  (let [dir (roots-dir dot-sand-dir key)]
    (fs/create-dirs dir)
    (apply p/start
      {:err :inherit :out :discard}
      "nix-store" "--realise" "--add-root" (str (fs/path dir "root"))
      paths)))

(defn- delete-other-roots!
  "Deletes GC roots other than those for the cache entry key, including
   those from older versions of sand. Errors are ignored, since a
   concurrent run may be deleting the same files."
  [dot-sand-dir key]
  (let [keep-dir (roots-dir dot-sand-dir key)
        parent (roots-parent-dir dot-sand-dir)]
    (doseq [path (concat
                   ; Older versions of sand rooted the shell's inputDerivation here.
                   [(fs/path dot-sand-dir "gcroots" "shell")]
                   (try (fs/list-dir parent) (catch Exception _ nil)))
            :when (not= keep-dir path)]
      (try
        (if (fs/directory? path {:nofollow-links true})
          (fs/delete-tree path)
          (fs/delete-if-exists path))
        (catch Exception _ nil)))))

(defn- write-cache! [key m]
  (let [dir (cache-dir)]
    (fs/create-dirs dir)
    (u/write-atomically! (fs/path dir (str key ".json"))
      #(json/write m %))))

(defn- read-cache
  "Returns the cache entry for key, or nil if it is missing or references
   store paths that no longer exist."
  [key]
  (let [^File f (fs/file (cache-dir) (str key ".json"))]
    (when (.exists f)
      (let [m (try
                (with-open [rdr (io/reader f)]
                  (json/read rdr))
                (catch Exception _ nil))]
        (when (and (= cache-version (get m "version"))
                (every? fs/exists? (get m "paths")))
          m)))))

(defn shell-env!
  "Returns the environment of dot-sand-dir's shell as
   a map whose :env holds the variables to set on top of the current
   environment.

   Evaluates nix only if there is no valid cache entry. When root? is true,
   the shell's inputs are registered as GC roots in dot-sand-dir if they
   aren't already. That runs in the background: call `finish!` on the
   result to wait for it."
  [dot-sand-dir {:keys [root?]}]
  (let [key (cache-key dot-sand-dir)
        outer (into {} (System/getenv))
        {:strs [diff paths]} (or (read-cache key)
                               (let [captured (capture! dot-sand-dir)
                                     diff (env-diff outer captured)
                                     m {"diff" diff
                                        "paths" (store-paths captured diff)
                                        "version" cache-version}]
                                 (write-cache! key m)
                                 m))]
    {:env (apply-diff outer diff)
     :rooting (when (and root? (seq paths) (not (rooted? dot-sand-dir key)))
                {:dot-sand-dir dot-sand-dir
                 :key key
                 :proc (start-rooting! dot-sand-dir key paths)})}))

(defn finish!
  "Waits for any background work started by shell-env!."
  [{:keys [rooting]}]
  (when-let [{:keys [dot-sand-dir key proc]} rooting]
    (let [exit @(p/exit-ref proc)]
      (if (zero? exit)
        (try
          (spit (fs/file (roots-dir dot-sand-dir key) "done") "")
          (delete-other-roots! dot-sand-dir key)
          (catch Exception e
            (binding [*out* *err*]
              (println "sand: warning: failed to record GC roots:" (ex-message e)))))
        (binding [*out* *err*]
          (println "sand: warning: failed to register GC roots, exit code" exit))))))

(defn resolve-command
  "Resolves cmd against the PATH in env, like a shell would. The JVM
   resolves commands against its own PATH, not the child's."
  [env cmd]
  (if (str/includes? cmd "/")
    cmd
    (or (some
          (fn [dir]
            (let [^File f (fs/file (if (empty? dir) "." dir) cmd)]
              (when (and (.isFile f) (.canExecute f))
                (str f))))
          (str/split (or (get env "PATH") (getenv "PATH") "") #":"))
      cmd)))
