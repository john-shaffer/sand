(ns sand.git
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.java.process :as p]
   [sand.util :as u]))

(defn- ensure-zero-exit [p]
  (let [exit @(p/exit-ref p)]
    (when-not (zero? exit)
      (throw
        (ex-info (str "Command exited with code " exit)
          {:exit exit})))))

(defn- get-out-lines
  "Read seq of lines from a process, making sure to check the
   exit code of the process. If the exit code is non-zero, the
   last line could be corrupted, so we omit it and throw an
   ExceptionInfo."
  [p lines]
  (lazy-seq
    (let [[line & more] lines]
      (if line
        (cons line
          (if more
            (get-out-lines p more)
            (ensure-zero-exit p)))
        (ensure-zero-exit p)))))

(defn group-by-repos
  "Returns a map whose keys are git repo paths and whose values are the items of
   [[paths]] which are in that git repo.
   If any of [[paths]] are not in a git repo, those items are under the key `nil`."
  [paths]
  (u/group-paths-by-ancestor
    (fn [path]
      (fs/exists? (fs/path path ".git")))
    paths))

(defn list-unignored-files [process-opts & [paths]]
  (let [p (apply p/start
            (assoc process-opts
              :err :inherit
              :out :pipe)
            "git" "ls-files" "--others" "--cached" "--exclude-standard"
            paths)]
    (get-out-lines p (line-seq (io/reader (p/stdout p))))))
