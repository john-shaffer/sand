(ns sand.core
  (:require
   [babashka.fs :as fs]))

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

(defn formatter-args [formatter fname]
  (let [{:strs [args package]} formatter]
    (into
      ["nix" "run" (str "nixpkgs#" package) "--"]
      (for [arg args]
        (if (#{"@" "*@"} arg)
          fname
          arg)))))

(defn formatter-for-file [formatters fname]
  (if-let [id (get (:by-filename formatters) fname)]
    (get (:by-id formatters) id)
    (let [ext (fs/extension fname)]
      (some->> (get (:by-extension formatters) ext)
        (get (:by-id formatters))))))
