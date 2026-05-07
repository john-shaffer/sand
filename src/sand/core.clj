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

(defn find-nixpkgs-input
  "Finds a nixpkgs input, if any, from a parsed flake.lock map."
  [m]
  (let [candidate (get-in m ["nodes" "nixpkgs"])]
    (if (= "github" (get-in candidate ["locked" "type"]))
      candidate
      (->> (get m "nodes")
        (filter
          (fn [[_ {:strs [locked]}]]
            (and (= "github" (get locked "type"))
              (= "nixpkgs" (some-> (get locked "repo") str/lower-case))
              (= "nixos" (some-> (get locked "owner") str/lower-case)))))
        (sort-by #(get-in % ["locked" "lastModified"]))
        last second))))

(defn find-flake-nixpkgs
  "Finds a nixpkgs input for the flake for the given dir.
   Returns nil if a flake or suitable nixpkgs is not found."
  [dir]
  (let [flake-lock (when-let [path (find-filename-up dir ["flake.lock"])]
                     (with-open [rdr (-> path fs/file io/reader)]
                       (json/read rdr)))]
    (when flake-lock
      (find-nixpkgs-input flake-lock))))

(defn find-dot-sand-dir
  "Finds a .sand dir searching upward. Finds either an existing .sand dir
   or creates a path next to an existing .git dir. Returns nil if neither
   is found."
  ^Path [dir]
  (or (find-filename-up dir [".sand"])
    (some-> (find-filename-up dir [".git"])
      fs/parent
      fs/canonicalize
      (fs/path ".sand"))))

(defn formatter-args [formatter shell-nix fnames]
  (let [{:strs [args args-config bin-name config-filenames package]} formatter
        grouped-by-config (u/group-paths-by-ancestor
                            (fn [path]
                              (loop [[cfg & more] config-filenames]
                                (cond
                                  (nil? cfg) false
                                  (fs/exists? (fs/path path cfg)) true
                                  :else (recur more))))
                            fnames)
        base-command ["nix-shell" (str shell-nix) "--run"]
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
            (conj base-command
              (str/join " " (map u/shell-quote (cons cmd sas)))))))
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
  "Generates a sand.json file. `existing` may be nil or pre-existing data."
  [existing {:keys [nixpkgs-input packages]}]
  (cond-> (or existing {})
    nixpkgs-input (assoc "nixpkgs" nixpkgs-input)
    (seq packages) (assoc "shellPkgs"
                     (->> packages
                       (remove empty?)
                       (into (set (get existing "shellPkgs")))
                       sort))))

(defn build-shell!
  "Builds the inputDerivation of .sand/shell.nix and symlinks it into
   the .sand directory. The inputDerivation output references all build
   inputs, keeping them alive as a GC root. Returns the out-link path."
  [dot-sand-dir]
  (let [shell-nix (str (fs/canonicalize (fs/path dot-sand-dir "shell.nix")))
        gcroots-dir (fs/path dot-sand-dir "gcroots")
        _ (when-not (fs/exists? gcroots-dir)
            (fs/create-dir gcroots-dir))
        out-link (str (fs/path gcroots-dir "shell"))
        expr (str "(import " shell-nix " {}).inputDerivation")
        proc (p/start
               {:err :inherit :out :inherit}
               "nix-build" "--expr" expr
               "--out-link" out-link)
        exit @(p/exit-ref proc)]
    (when-not (zero? exit)
      (throw (ex-info "nix-build failed" {:exit exit})))
    out-link))

(defn write-dot-sand-dir!
  "Writes sand.json and shell.nix into dot-sand-dir, creating the directory
   if needed, then builds the shell. Returns dot-sand-dir."
  [dot-sand-dir opts]
  (when-not (fs/exists? dot-sand-dir)
    (fs/create-dir dot-sand-dir))
  (let [data-path (fs/path dot-sand-dir "sand.json")
        shell-nix-path (fs/path dot-sand-dir "shell.nix")
        existing-data (try
                        (with-open [rdr (-> data-path fs/file io/reader)]
                          (json/read rdr))
                        (catch Exception _ nil))
        data (generate-sand-json existing-data opts)]
    (when (not= existing-data data)
      (with-open [w (-> data-path fs/file io/writer)]
        (json/write data w :indent true)
        (.write w "\n")))
    (when-not (fs/exists? shell-nix-path)
      (fs/copy
        (-> "SAND_DATA_DIR"
          System/getenv
          (fs/path "shell.nix"))
        shell-nix-path))
    (build-shell! dot-sand-dir)
    dot-sand-dir))

(defmacro with-dot-sand-dir [[binding dir opts] & body]
  `(let [opts# ~opts]
     (if-let [found# (find-dot-sand-dir ~dir)]
       (let [~binding (write-dot-sand-dir! found# opts#)]
         ~@body)
       (fs/with-temp-dir [tmp# {:prefix "sand"}]
         (let [~binding (write-dot-sand-dir! tmp# opts#)]
           ~@body)))))
