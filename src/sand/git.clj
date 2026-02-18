(ns sand.git
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.java.process :as p]
   [clojure.set :as set]))

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
  (when (seq paths)
    (let [dir->paths (reduce
                       (fn [m path]
                         (let [cpath (fs/canonicalize path)
                               dir-path (if (fs/directory? cpath)
                                          cpath
                                          (fs/parent cpath))]
                           (update m dir-path (fnil conj #{}) cpath)))
                       {}
                       paths)
          has-dot-git? (memoize
                         (fn [path]
                           (fs/exists? (fs/path path ".git"))))]
      (loop [repo->paths {}
             dir->paths dir->paths]
        (if (empty? dir->paths)
          repo->paths
          (let [[[k v] & more] dir->paths]
            (if (or (nil? k) (has-dot-git? k))
              (recur
                (update repo->paths k (fnil set/union #{}) v)
                more)
              (recur
                repo->paths
                (cons [(fs/parent k) v] more)))))))))

(defn list-unignored-files [process-opts & [paths]]
  (let [p (apply p/start
            (assoc process-opts
              :err :inherit
              :out :pipe)
            "git" "ls-files" "--others" "--cached" "--exclude-standard"
            paths)]
    (get-out-lines p (line-seq (io/reader (p/stdout p))))))
