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

(defn- compile-formatter [k {:as m :strs [priority]}]
  (cond-> (assoc m "id" k)
    (not priority) (assoc "priority" default-priority)))

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

(defn find-filename-up
  "Finds a file or directory name, looking first in the `dir` directory
   and then in parent directories recursively. Returns the [[java.nio.file.Path]]
   or nil if not found or inaccessible."
  ^Path [dir filenames]
  (try
    (loop [current (fs/absolutize dir)]
      (when current
        (if-let [found (some #(let [candidate (fs/path current %)]
                                (when (fs/exists? candidate)
                                  candidate))
                             filenames)]
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

(defn find-dot-sand-dir
  "Finds a .sand dir searching upward. Finds either an existing .sand dir
   or creates a path next to an existing .git dir, whichever is nearest.
   Returns nil if neither is found."
  ^Path [dir]
  (when-let [found (find-filename-up dir [".sand" ".git"])]
    (if (= ".sand" (str (fs/file-name found)))
      found
      (-> found fs/parent fs/canonicalize (fs/path ".sand")))))

(defn formatter-args
  "Returns a seq of argument vectors to run formatter on fnames."
  [formatter fnames]
  (let [{:strs [args args-config bin-name config-filenames package]} formatter
        grouped-by-config (u/group-paths-by-ancestor
                            (fn [path]
                              (loop [[cfg & more] config-filenames]
                                (cond
                                  (nil? cfg) false
                                  (fs/exists? (fs/path path cfg)) true
                                  :else (recur more))))
                            fnames)
        cmd (or bin-name package)]
    (mapcat
      (fn [[config-dir fnames]]
        (let [config-path (when config-dir
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
        (let [{:strs [args args-config config-filenames] :as formatter}
              (get (:by-id formatters) id)]
          (when (or args
                  (and args-config
                    (seq config-filenames)
                    (find-filename-up dir config-filenames)))
            formatter)))
      candidates)))

(defn generate-sand-json
  "Generates a sand.json file. `existing` may be nil or pre-existing data.
   `missing-packages` are removed from the existing packages, and not
   added from `packages`."
  [existing {:keys [missing-packages nixpkgs-input packages]}]
  (cond-> (or existing {})
    nixpkgs-input (assoc "nixpkgs" nixpkgs-input)
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
    ; GC roots are specific to the machine, and the lock file is only
    ; used while sand runs.
    (when-not (fs/exists? gitignore-path)
      (u/write-atomically! gitignore-path
        (fn [^java.io.Writer w]
          (.write w "/gcroots/\n/sand.json.lock\n"))))
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
