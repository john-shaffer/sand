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

(defn- get-out-entries
  "Read seq of NUL-terminated entries from a process, making sure to check
   the exit code of the process. If the exit code is non-zero, the last
   entry could be corrupted, so we omit it and throw an ExceptionInfo."
  [p entries]
  (lazy-seq
    (let [[entry & more] entries]
      (if entry
        (cons entry
          (if more
            (get-out-entries p more)
            (ensure-zero-exit p)))
        (ensure-zero-exit p)))))

(defn- nul-separated-seq
  "Returns a lazy seq of the NUL-terminated strings read from rdr."
  [^java.io.Reader rdr]
  (let [sb (StringBuilder.)]
    (letfn [(step []
              (lazy-seq
                (loop []
                  (let [c (.read rdr)]
                    (cond
                      (= -1 c) (when (pos? (.length sb))
                                 (list (.toString sb)))
                      (zero? c) (let [s (.toString sb)]
                                  (.setLength sb 0)
                                  (cons s (step)))
                      :else (do (.append sb (char c))
                              (recur)))))))]
      (step))))

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
            ; -z so that paths aren't quoted, as git does for paths with
            ; unusual characters, like non-ASCII ones
            "git" "ls-files" "-z" "--others" "--cached" "--exclude-standard"
            paths)]
    (get-out-entries p (nul-separated-seq (io/reader (p/stdout p))))))
