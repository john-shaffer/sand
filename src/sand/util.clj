(ns sand.util
  (:require
   [babashka.fs :as fs]
   [clojure.set :as set]))

(defn group-paths-by-ancestor
  "Returns a map whose keys are ancestor paths matching a predicate
   and whose values are the items of [[paths]] which are the nearest children.
   If any of [[paths]] do not have an ancestor who matches the predicate,
   those items are under the key `nil`.

   [[pred]] is a function that takes a directory path and returns
   a truthy or falsey value.
   It is memoized to avoid excessive I/O.

   Examples:
   Group paths by which git repo they are in, if any:
     pred: #(fs/exists? (fs/path % \".git\"))

   Group paths by which .cljfmt.edn config file they share, if any:
     pred: #(fs/exists? (fs/path % \".cljfmt.edn\"))
   "
  [pred paths]
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
          test? (memoize pred)]
      (loop [repo->paths {}
             dir->paths dir->paths]
        (if (empty? dir->paths)
          repo->paths
          (let [[[k v] & more] dir->paths]
            (if (or (nil? k) (test? k))
              (recur
                (update repo->paths k (fnil set/union #{}) v)
                more)
              (recur
                repo->paths
                (cons [(fs/parent k) v] more)))))))))
