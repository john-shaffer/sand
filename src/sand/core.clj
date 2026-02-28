(ns sand.core
  (:require
   [babashka.fs :as fs]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.java.process :as p]
   [clojure.string :as str])
  (:import
   (java.nio.file AccessDeniedException InvalidPathException Path)))

(def ^{:private true}
  default-priority 5)

(defn select-by-priority
  "Select the option with the lowest priority.
   Throws an exception if there is a tie."
  [m by-id]
  (update-vals m
    (fn [ids]
      (if (= 1 (count ids))
        (first ids)
        (let [priorities (mapv (fn [id] (get-in by-id [id "priority"])) ids)
              lowest (apply min priorities)
              lowest-ids (mapcat
                           (fn [priority id]
                             (when (= lowest priority)
                               [id]))
                           priorities
                           ids)]
          (if (= 1 (count lowest-ids))
            (first lowest-ids)
            (throw
              (ex-info
                (str "Found multiple options with same priority (" lowest "): "
                  (str/join ", " lowest-ids))
                {:ids lowest-ids
                 :priority lowest}))))))))

(defn compile-formatters [data]
  (let [by-id (update-vals data
                (fn [{:as m :strs [priority]}]
                  (if priority
                    m
                    (assoc m "priority" default-priority))))
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
    {:by-extension (select-by-priority by-extension by-id)
     :by-filename (select-by-priority by-filename by-id)
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

(defn- shell-quote [s]
  (let [s (str s)]
    (if (re-matches #"[a-zA-Z0-9_./:@=+-]+" s)
      s
      (str \' (str/replace s "'" "'\"'\"'") \'))))

(defn formatter-args [formatter fname shell-nix]
  (let [{:strs [args args-config bin-name config-filenames package]} formatter
        config-path (when (seq config-filenames)
                      (find-filename-up (fs/parent (fs/absolutize fname)) config-filenames))
        active-args (if (and config-path args-config) args-config args)
        shell-args (keep
                     (fn [arg]
                       (case arg
                         ("@" "*@") fname
                         "{{config}}" (some-> config-path str)
                         arg))
                     active-args)
        cmd (or bin-name package)]
    ["nix-shell" (str shell-nix) "--run"
     (str/join " " (map shell-quote (cons cmd shell-args)))]))

(defn formatter-for-file [formatters fname]
  (if-let [id (get (:by-filename formatters) fname)]
    (get (:by-id formatters) id)
    (let [ext (fs/extension fname)]
      (some->> (get (:by-extension formatters) ext)
        (get (:by-id formatters))))))

(defn generate-sand-json
  "Generates a sand.json file. `existing` may be nil or pre-existing data."
  [existing {:keys [nixpkgs-input packages]}]
  (cond-> (or existing {})
    nixpkgs-input (assoc "nixpkgs" nixpkgs-input)
    (seq packages) (assoc "shellPkgs"
                     (->> packages
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

(defn- write-dot-sand-files! [dot-sand-dir opts]
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

(defn write-dot-sand-dir! [dir opts]
  (if-let [dot-sand-dir (find-dot-sand-dir dir)]
    (do
      (when-not (fs/exists? dot-sand-dir)
        (fs/create-dir dot-sand-dir))
      (write-dot-sand-files! dot-sand-dir opts))
    (fs/with-temp-dir [dot-sand-dir {:prefix "sand"}]
      (write-dot-sand-files! dot-sand-dir opts))))

