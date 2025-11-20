(ns sand.git
  (:require
   [clojure.java.io :as io]
   [clojure.java.process :as p]))

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

(defn list-unignored-files [process-opts]
  (let [p (p/start
            (assoc process-opts
              :err :inherit
              :out :pipe)
            "git" "ls-files" "--others" "--cached" "--exclude-standard")]
    (get-out-lines p (line-seq (io/reader (p/stdout p))))))
