(ns sand.core
  (:require
   [babashka.fs :as fs]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.nio.file AccessDeniedException InvalidPathException Path)))

(defn compile-formatters [data]
  {:by-extension
   (into {}
     (mapcat
       (fn [[id {:strs [extensions]}]]
         (for [ext extensions]
           [ext id]))
       data))
   :by-filename
   (into {}
     (mapcat
       (fn [[id {:strs [filenames]}]]
         (for [fname filenames]
           [fname id]))
       data))
   :by-id data})

(defn conform-config
  "Returns config-map conformed to schema. E.g., with default values set."
  [config-map]
  config-map)

(defn find-filename-up
  "Finds a file or directory name, looking first in the `dir` directory
   and then in parent directories recursively. Returns the [[java.nio.file.Path]]
   or nil if not found or inaccessible."
  ^Path [dir filename]
  (try
    (loop [current (fs/absolutize dir)]
      (when current
        (let [candidate (fs/path current filename)]
          (if (fs/exists? candidate)
            candidate
            (recur (fs/parent current))))))
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

(defn find-dot-sand-dir
  "Finds a .sand dir searching upward. Finds either an existing .sand dir
   or creates a path next to an existing .git dir. Returns nil if neither
   is found."
  ^Path [dir]
  (or (find-filename-up dir ".sand")
    (some-> (find-filename-up dir ".git")
      fs/parent
      fs/canonicalize
      (fs/path ".sand"))))

(defn formatter-args [formatter fname nixpkgs-input]
  (let [{:strs [args bin-name package]} formatter
        shell-args (for [arg args]
                     (if (#{"@" "*@"} arg)
                       fname
                       arg))
        {:strs [owner repo rev type]} (get nixpkgs-input "locked")
        nixpkgs-ref (if (= "github" type)
                      (str type ":" owner "/" repo "/" rev)
                      "github:NixOS/nixpkgs")
        package-ref (str nixpkgs-ref "#" package)]
    (if bin-name
      (into
        ["nix" "shell" package-ref
         "--command" bin-name]
        shell-args)
      (into
        ["nix" "run" package-ref "--"]
        shell-args))))

(defn formatter-for-file [formatters fname]
  (if-let [id (get (:by-filename formatters) fname)]
    (get (:by-id formatters) id)
    (let [ext (fs/extension fname)]
      (some->> (get (:by-extension formatters) ext)
        (get (:by-id formatters))))))

(defn generate-sand-json
  "Generates a sand.json file. `existing` may be nil or pre-existing data."
  [existing {:keys [nixpkgs-input]}]
  (cond-> (or existing {})
    nixpkgs-input (assoc "nixpkgs" nixpkgs-input)))

(defn write-dot-sand-files! [dir opts]
  (when-let [dot-sand-dir (find-dot-sand-dir dir)]
    (when-not (fs/exists? dot-sand-dir)
      (fs/create-dir dot-sand-dir))
    (let [data-path (fs/path dot-sand-dir "sand.json")
          existing-data (try
                          (with-open [rdr (-> data-path fs/file io/reader)]
                            (json/read rdr))
                          (catch Exception _ nil))
          data (generate-sand-json existing-data opts)]
      (when (not= existing-data data)
        (with-open [w (-> data-path fs/file io/writer)]
          (json/write data w :indent true))))))
