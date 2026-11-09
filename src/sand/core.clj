(ns sand.core
  (:require
   [babashka.fs :as fs]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.java.process :as p]
   [clojure.string :as str]
   [sand.util :as u])
  (:import
   (java.nio.file AccessDeniedException InvalidPathException Path)))

(def ^{:private true}
  default-priority 5)

(def ^{:private true
       :doc "Extensions of files that aren't formatted, even if they end with
             a formatter's extension. E.g., minified build output."}
  excluded-extensions
  ["min.css" "min.js"])

(defn- sort-by-priority
  "Returns m with each value replaced by a priority-sorted vector of ids.
   Throws if any two ids share the same priority."
  [m by-id]
  (update-vals m
    (fn [ids]
      (let [sorted (vec (sort-by #(get-in by-id [% "priority"]) ids))]
        (doseq [[a b] (partition 2 1 sorted)]
          (let [pa (get-in by-id [a "priority"])
                pb (get-in by-id [b "priority"])]
            (when (= pa pb)
              (throw
                (ex-info
                  (str "Found multiple options with same priority (" pa "): "
                    (str/join ", " [a b]))
                  {:ids [a b] :priority pa})))))
        sorted))))

(defn flake-name
  "Returns the name that a formatter's flake package is known by, or nil
   if it doesn't come from a flake. The flake's ref, which is only for
   reference, is part of the name so that people can recognize it."
  [{:strs [flake flake-package]}]
  (when flake
    (str flake "#" (or flake-package "default"))))

(defn flake-entry
  "Returns the sand.json entry for the flake package of formatter. Only
   its locked ref decides what is fetched. The flake's ref (e.g., a tag)
   is never used, because it can be moved to a different commit."
  [{:strs [flake-lock flake-package]}]
  {"locked" flake-lock
   "package" (or flake-package "default")})

(defn- check-formatter
  "Throws if formatter's package settings are invalid."
  [{:as formatter :strs [bin-name flake flake-lock id package]}]
  (let [fail (fn [msg]
               (throw (ex-info (str "Invalid formatter " id ": " msg)
                        {:formatter formatter})))]
    (when (and flake package)
      (fail "flake and package can't both be set"))
    (when flake
      (when-not (map? flake-lock)
        (fail "flake requires flake-lock, the locked ref to use"))
      (when-not (get flake-lock "narHash")
        (fail "flake-lock requires narHash"))
      (when-not bin-name
        (fail "flake requires bin-name")))))

(defn- compile-formatter [k {:as m :strs [priority]}]
  (doto (cond-> (assoc m "id" k)
          (not priority) (assoc "priority" default-priority))
    check-formatter))

(defn compile-formatters [data]
  (let [by-id (reduce-kv
                (fn [m k v]
                  (assoc m k (compile-formatter k v)))
                data
                data)
        by-extension (reduce-kv
                       (fn [m k {:strs [extensions]}]
                         (reduce
                           (fn [m ext]
                             (update m ext (fnil conj []) k))
                           m
                           extensions))
                       {}
                       by-id)
        by-filename (reduce-kv
                      (fn [m k {:strs [filenames]}]
                        (reduce
                          (fn [m fname]
                            (update m fname (fnil conj []) k))
                          m
                          filenames))
                      {}
                      by-id)]
    {:by-extension (sort-by-priority by-extension by-id)
     :by-filename (sort-by-priority by-filename by-id)
     :by-id by-id}))

(defn conform-config
  "Returns config-map conformed to schema. E.g., with default values set."
  [config-map]
  config-map)

(defn- find-filename-in
  "Returns the [[java.nio.file.Path]] of the first of `filenames` that
   exists in the `dir` directory, or nil. A filename can be a glob
   pattern, like `*.csproj`. If a pattern matches several files, the
   first in sorted order is returned."
  ^Path [dir filenames]
  (some #(if (re-find #"[*?\[{]" %)
           (first (sort (fs/glob dir %)))
           (let [candidate (fs/path dir %)]
             (when (fs/exists? candidate)
               candidate)))
    filenames))

(defn find-filename-up
  "Finds a file or directory name, looking first in the `dir` directory
   and then in parent directories recursively. Returns the [[java.nio.file.Path]]
   or nil if not found or inaccessible."
  ^Path [dir filenames]
  (try
    (loop [current (fs/absolutize dir)]
      (when current
        (if-let [found (find-filename-in current filenames)]
          found
          (recur (fs/parent current)))))
    (catch AccessDeniedException _ nil)
    (catch InvalidPathException _ nil)
    (catch SecurityException _ nil)))

(defn- resolve-flake-input
  "Returns the node key that the input named `input-name` of the node
   `node-key` refers to in a parsed flake.lock map. An input is either a
   node key or, for inputs that follow another, a path of input names
   starting at the root node."
  [m node-key input-name]
  (let [ref (get-in m ["nodes" node-key "inputs" input-name])]
    (if (sequential? ref)
      (reduce #(resolve-flake-input m %1 %2) (get m "root") ref)
      ref)))

(defn find-nixpkgs-input
  "Finds a nixpkgs input, if any, from a parsed flake.lock map.
   Prefers the root flake's input named nixpkgs."
  [m]
  (let [candidate (some->> (resolve-flake-input m (get m "root") "nixpkgs")
                    (vector "nodes")
                    (get-in m))]
    (if (= "github" (get-in candidate ["locked" "type"]))
      candidate
      (->> (get m "nodes")
        (filter
          (fn [[_ {:strs [locked]}]]
            (and (= "github" (get locked "type"))
              (= "nixpkgs" (some-> (get locked "repo") str/lower-case))
              (= "nixos" (some-> (get locked "owner") str/lower-case)))))
        (map val)
        (sort-by #(get-in % ["locked" "lastModified"]))
        last))))

(defn find-flake-nixpkgs
  "Finds a nixpkgs input for the flake for the given dir. The search
   stops at the root of the git repo containing dir, if any.
   Returns nil if a flake or suitable nixpkgs is not found."
  [dir]
  (let [found (find-filename-up dir ["flake.lock" ".git"])
        flake-lock (when (and found (= "flake.lock" (str (fs/file-name found))))
                     (with-open [rdr (-> found fs/file io/reader)]
                       (json/read rdr)))]
    (when flake-lock
      (find-nixpkgs-input flake-lock))))

(defn project-root
  "Returns the canonical path in PRJ_ROOT, or nil if it isn't set. Tools
   like `nix fmt` set it to the project's root dir."
  ^Path []
  (some-> (System/getenv "PRJ_ROOT") not-empty fs/canonicalize))

(defn find-dot-sand-dir
  "Returns the .sand dir in PRJ_ROOT, if it's set. Otherwise finds a .sand
   dir searching upward: either an existing .sand dir or a path next to an
   existing .git dir, whichever is nearest. Returns nil if neither is
   found."
  ^Path [dir]
  (if-let [root (project-root)]
    (fs/path root ".sand")
    (when-let [found (find-filename-up dir [".sand" ".git"])]
      (if (= ".sand" (str (fs/file-name found)))
        found
        (-> found fs/parent fs/canonicalize (fs/path ".sand"))))))

(defn- formatter-fname
  "Returns the path to pass a formatter for fname. That's a path relative
   to `dir`, unless fname is in one of `absolute-roots`. Relative paths
   inside dir start with ./ so that a name like -x can't be read as an
   option."
  [dir absolute-roots fname]
  (let [rel (fs/relativize dir fname)]
    (cond
      (some #(fs/starts-with? fname %) absolute-roots) fname
      (str/starts-with? (str rel) "..") rel
      :else (fs/path "." rel))))

(defn formatter-args
  "Returns a seq of argument vectors to run formatter on fnames, from
   the `dir` directory. Files are passed as paths relative to `dir`,
   except for those in `absolute-roots`, which are canonical paths that
   were given as absolute paths."
  [formatter {:keys [absolute-roots dir]} fnames]
  (let [{:strs [args args-config bin-name config-filenames package]} formatter
        grouped-by-config (u/group-paths-by-ancestor
                            #(find-filename-in % config-filenames)
                            fnames)
        cmd (or bin-name package)
        cdir (fs/canonicalize dir)]
    (mapcat
      (fn [[config-dir fnames]]
        (let [fnames (map #(formatter-fname cdir absolute-roots %) fnames)
              config-path (when config-dir
                            (find-filename-up config-dir config-filenames))
              active-args (or (when config-path args-config) args)
              arg-arity (some #{"@" "*@"} active-args)
              shell-args (keep
                           (fn [arg]
                             (if (= "{{config}}" arg)
                               (some-> config-path str)
                               arg))
                           active-args)
              shell-arg-seqs
              #__ (case arg-arity
                    "@"
                    (for [fname fnames]
                      (mapcat
                        (fn [arg]
                          (if (= "@" arg)
                            [fname]
                            [arg]))
                        shell-args))

                    "*@"
                    [(mapcat
                       (fn [arg]
                         (if (= "*@" arg)
                           fnames
                           [arg]))
                       shell-args)]

                    (throw (ex-info "Invalid formatter" {:formatter formatter})))]
          (for [sas shell-arg-seqs]
            (into [cmd] (map str) sas))))
      grouped-by-config)))

(defn formatter-for-file [formatters dir fname]
  (let [candidates (or (get (:by-filename formatters) fname)
                     (get (:by-extension formatters) (fs/extension fname)))]
    (some
      (fn [id]
        (let [{:strs [args args-config config-filenames parent-dirnames] :as formatter}
              (get (:by-id formatters) id)]
          (when (and (or (empty? parent-dirnames)
                       (some #{(fs/file-name dir)} parent-dirnames))
                  (or args
                    (and args-config
                      (seq config-filenames)
                      (find-filename-up dir config-filenames))))
            formatter)))
      (when (not-any? #(str/ends-with? fname (str "." %)) excluded-extensions)
        candidates))))

(defn- merge-flakes
  "Returns sand.json data with flakes added to its flakes, and
   missing-names removed. The flakes key is left out if none remain."
  [data flakes missing-names]
  (let [merged (apply dissoc (merge (get data "flakes") flakes) missing-names)]
    (if (seq merged)
      (assoc data "flakes" merged)
      (dissoc data "flakes"))))

(defn generate-sand-json
  "Generates a sand.json file. `existing` may be nil or pre-existing data.
   `missing-packages` are removed from the existing packages and flakes,
   and not added from `packages` and `flakes`. `flakes` maps flake names to
   entries."
  [existing {:keys [flakes missing-packages nixpkgs-input packages]}]
  (cond-> (or existing {})
    nixpkgs-input (assoc "nixpkgs" nixpkgs-input)
    (or (seq flakes) (get existing "flakes"))
    (merge-flakes flakes missing-packages)
    (or (seq packages) (seq missing-packages))
    (assoc "shellPkgs"
      (->> packages
        (remove empty?)
        (into (set (get existing "shellPkgs")))
        (remove (set missing-packages))
        sort))))

(defn- canonicalize-json
  "Sorts the keys of all maps in x, so that it serializes the same way
   regardless of how it was built."
  [x]
  (cond
    (map? x) (into (sorted-map) (update-vals x canonicalize-json))
    (sequential? x) (mapv canonicalize-json x)
    :else x))

(defn sand-json-str
  "Returns the contents of a sand.json file for data."
  [data]
  (str (json/write-str (canonicalize-json data) :indent true) "\n"))

(defn read-sand-json
  "Returns the parsed sand.json in dot-sand-dir, or nil if it is missing
   or invalid."
  [dot-sand-dir]
  (try
    (with-open [rdr (-> (fs/path dot-sand-dir "sand.json") fs/file io/reader)]
      (json/read rdr))
    (catch Exception _ nil)))

(defn update-sand-json!
  "Adds the packages and nixpkgs input in opts to dot-sand-dir's sand.json,
   if they aren't already there."
  [dot-sand-dir opts]
  (when (not= (read-sand-json dot-sand-dir)
          (generate-sand-json (read-sand-json dot-sand-dir) opts))
    ; Another process may be adding different packages at the same time,
    ; so re-read and update sand.json while holding a lock, so that
    ; neither update is lost. The file is still replaced atomically,
    ; because readers, including nix, don't take the lock.
    (u/with-file-lock (fs/path dot-sand-dir "sand.json.lock")
      (fn []
        (let [existing (read-sand-json dot-sand-dir)
              data (generate-sand-json existing opts)]
          (when (not= existing data)
            (u/write-atomically! (fs/path dot-sand-dir "sand.json")
              (fn [^java.io.Writer w]
                (.write w ^String (sand-json-str data))))))))))

(defn prepare-dot-sand-dir!
  "Creates dot-sand-dir if needed, and writes its shell.nix and .gitignore.
   Returns dot-sand-dir. Files are replaced atomically, because other sand
   processes may be reading them."
  [dot-sand-dir]
  (fs/create-dirs dot-sand-dir)
  (let [shell-nix-path (fs/path dot-sand-dir "shell.nix")
        gitignore-path (fs/path dot-sand-dir ".gitignore")]
    ; shell.nix is generated, so replace it whenever sand's template
    ; changes. Otherwise repos would keep whatever version they started with.
    (let [template (-> "SAND_DATA_DIR" System/getenv (fs/file "shell.nix") slurp)]
      (when-not (= template (try (slurp (fs/file shell-nix-path))
                              (catch java.io.IOException _ nil)))
        (u/write-atomically! shell-nix-path
          (fn [^java.io.Writer w]
            (.write w ^String template)))))
    ; GC roots are specific to the machine, the lock file is only used
    ; while sand runs, and shell.nix is generated. Lines are added to an
    ; existing .gitignore, so that repos set up by older versions of sand
    ; get the new ones, and any lines added by users are kept.
    (let [existing (try (slurp (fs/file gitignore-path))
                     (catch java.io.IOException _ ""))
          missing (remove (set (str/split-lines existing))
                    ["/gcroots/" "/sand.json.lock" "/shell.nix"])]
      (when (seq missing)
        (u/write-atomically! gitignore-path
          (fn [^java.io.Writer w]
            (.write w ^String existing)
            (when-not (or (empty? existing) (str/ends-with? existing "\n"))
              (.write w "\n"))
            (doseq [line missing]
              (.write w (str line "\n")))))))
    dot-sand-dir))

(defmacro with-dot-sand-dir
  "Binds binding to `{:dot-sand-dir path :temp? bool}`. When no .sand dir
   is found, a temporary one is used and deleted after body."
  [[binding dir] & body]
  `(if-let [found# (find-dot-sand-dir ~dir)]
     (let [~binding {:dot-sand-dir (prepare-dot-sand-dir! found#)
                     :temp? false}]
       ~@body)
     (fs/with-temp-dir [tmp# {:prefix "sand"}]
       (let [~binding {:dot-sand-dir (prepare-dot-sand-dir! tmp#)
                       :temp? true}]
         ~@body))))
