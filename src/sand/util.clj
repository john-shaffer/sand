(ns sand.util
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str])
  (:import
   (java.nio.channels FileChannel)
   (java.nio.file OpenOption StandardOpenOption)))

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

(defn shell-quote [s]
  (let [s (str s)]
    (if (re-matches #"[a-zA-Z0-9_./:@=+-]+" s)
      s
      (str \' (str/replace s "'" "'\"'\"'") \'))))

(defn write-atomically!
  "Calls (f writer) to write the file at path, replacing it atomically,
   so that concurrent readers see either the old or the new contents."
  [path f]
  (let [path (fs/path path)
        tmp (fs/create-temp-file {:dir (fs/parent path)
                                  :prefix (str (fs/file-name path) ".")
                                  :suffix ".tmp"})]
    ; Temp files are created readable only by the owner
    (fs/set-posix-file-permissions tmp "rw-r--r--")
    (try
      (with-open [w (io/writer (fs/file tmp))]
        (f w))
      (fs/move tmp path {:atomic-move true :replace-existing true})
      (finally
        (fs/delete-if-exists tmp)))))

(defn user-error
  "Returns an exception for an error that sand reports to the user as a
   message, without a stack trace, and then exits with exit-code."
  [msg exit-code]
  (ex-info msg {::user-error true :exit-code exit-code}))

(defn user-error? [e]
  (boolean (::user-error (ex-data e))))

(defn with-file-lock
  "Calls f while holding an exclusive lock on the file at path, creating
   the file if needed. The lock file is never deleted, since deleting it
   would let two processes lock different files at the same path."
  [path f]
  (with-open [ch (FileChannel/open (fs/path path)
                   (into-array OpenOption
                     [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
    ; Closing the channel releases the lock
    (.lock ch)
    (f)))
