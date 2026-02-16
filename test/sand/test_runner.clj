(ns sand.test-runner
  (:require
   [babashka.fs :as fs]
   [clojure.java.process :as p]
   [clojure.string :as str]
   [clojure.test :as t]
   [clojure.tools.cli :as cli]
   [toml-clj.core :as toml]))

(defn load-test [path]
  (-> path slurp toml/read-string))

(defn find-tests [dir patterns]
  (let [globs (if (seq patterns)
                (map #(if (str/ends-with? % ".toml") % (str % ".toml")) patterns)
                ["*.toml"])]
    (->> globs
         (mapcat #(fs/glob dir %))
         (map str)
         distinct
         sort)))

(defn setup-files [dir files]
  (doseq [[path {:strs [content]}] files]
    (let [full-path (fs/path dir path)]
      (fs/create-dirs (fs/parent full-path))
      (spit (str full-path) content))))

(defn run-command [dir command {:keys [sand-bin]}]
  (let [args (str/split command #"\s+")
        args (if (and sand-bin (= "sand" (first args)))
               (cons sand-bin (rest args))
               args)
        env (dissoc (into {} (System/getenv)) "SAND_DATA_DIR" "SAND_SCHEMA")]
    (try
      (let [proc (apply p/start
                   {:dir (str dir)
                    :env env
                    :err :pipe
                    :out :pipe}
                   args)
            stdout (slurp (p/stdout proc))
            stderr (slurp (p/stderr proc))
            exit @(p/exit-ref proc)]
        {:stdout stdout
         :stderr stderr
         :exit exit})
      (catch java.io.IOException e
        {:stdout ""
         :stderr (str "Failed to start process: " (first args) "\n" (.getMessage e))
         :exit 127}))))

(defn check-file [dir path expected]
  (let [{:strs [content exists] :or {exists true}} expected
        full-path (fs/path dir path)
        file-exists? (fs/exists? full-path)]
    (cond
      (and (not exists) file-exists?)
      {:type :fail
       :message (str "File should not exist: " path)}

      (and exists (not file-exists?))
      {:type :fail
       :message (str "File does not exist: " path)}

      (and exists content)
      (let [actual (slurp (str full-path))]
        (if (= content actual)
          {:type :pass}
          {:type :fail
           :message (str "File content mismatch: " path)
           :expected content
           :actual actual}))

      :else
      {:type :pass})))

(defn check-expected [dir result expected]
  (let [{:strs [files stdout stderr exit] :or {exit 0}} expected
        file-results (for [[path exp] files]
                       (assoc (check-file dir path exp) :path path))
        checks (cond-> file-results
                 (contains? expected "stdout")
                 (conj (if (= stdout (:stdout result))
                         {:type :pass :check :stdout}
                         {:type :fail
                          :check :stdout
                          :message "stdout mismatch"
                          :expected stdout
                          :actual (:stdout result)}))

                 (contains? expected "stderr")
                 (conj (if (= stderr (:stderr result))
                         {:type :pass :check :stderr}
                         {:type :fail
                          :check :stderr
                          :message "stderr mismatch"
                          :expected stderr
                          :actual (:stderr result)}))

                 true
                 (conj (if (= exit (:exit result))
                         {:type :pass :check :exit}
                         {:type :fail
                          :check :exit
                          :message "exit code mismatch"
                          :expected exit
                          :actual (:exit result)})))]
    checks))

(defn run-one-turn-test [test-def opts]
  (let [{:strs [files run expected]} test-def]
    (fs/with-temp-dir [dir {:prefix "sand-test"}]
      (setup-files dir files)
      (let [result (run-command dir run opts)
            checks (check-expected dir result expected)]
        {:command run
         :result result
         :checks checks
         :pass? (every? #(= :pass (:type %)) checks)}))))

(defn run-test [test-def opts]
  (case (get test-def "format")
    "one-turn" (run-one-turn-test test-def opts)
    (throw (ex-info (str "Unknown test format: " (get test-def "format"))
                    {:format (get test-def "format")}))))

(defn format-multiline [s indent]
  (let [lines (str/split-lines s)]
    (str/join "\n" (map #(str indent %) lines))))

(defn print-diff [expected actual indent]
  (if (and (string? expected) (string? actual)
           (or (str/includes? expected "\n")
               (str/includes? actual "\n")))
    (do
      (println (str indent "expected:"))
      (println (format-multiline expected (str indent "  ")))
      (println (str indent "actual:"))
      (println (format-multiline actual (str indent "  "))))
    (do
      (println (str indent "expected: " (pr-str expected)))
      (println (str indent "actual:   " (pr-str actual))))))

(defn run-test-file [path opts]
  (let [test-def (load-test path)
        test-name (get test-def "name")]
    (println "Testing:" test-name)
    (let [{:keys [command result checks pass?] :as test-result} (run-test test-def opts)]
      (when-not pass?
        (println "  command:" command)
        (when (seq (:stderr result))
          (println "  stderr:" (:stderr result))))
      (doseq [check checks]
        (if (= :pass (:type check))
          (println "  ✓" (or (:path check) (:check check) "pass"))
          (do
            (println "  ✗" (or (:path check) (:check check) (:message check)))
            (when (or (contains? check :expected) (contains? check :actual))
              (print-diff (:expected check) (:actual check) "    ")))))
      test-result)))

(defn run-tests [test-dir patterns opts]
  (let [test-files (find-tests test-dir patterns)
        results (doall (map #(run-test-file % opts) test-files))
        passed (count (filter :pass? results))
        total (count results)]
    (println)
    (println (str passed "/" total " tests passed"))
    {:passed passed :failed (- total passed) :total total}))

(defn setup-test-to-dir [test-dir patterns target-dir]
  (let [target (fs/path target-dir)
        test-files (find-tests test-dir patterns)]
    (when (empty? test-files)
      (println "No tests found matching:" (str/join ", " patterns))
      (System/exit 1))
    (fs/create-dirs target)
    (doseq [path test-files]
      (let [test-def (load-test path)
            test-name (get test-def "name")
            files (get test-def "files")]
        (println "Setting up:" test-name)
        (setup-files target files)
        (doseq [f (sort (keys files))]
          (println "  " f))))
    (println)
    (println "Files written to" (str target))))

(def cli-options
  [[nil "--sand-bin PATH" "Path to sand binary"]
   [nil "--setup DIR" "Copy test input files into DIR instead of running"]
   [nil "--test-dir DIR" "Test directory" :default "test"]])

(defn -main [& args]
  (let [{:keys [options arguments errors]} (cli/parse-opts args cli-options)]
    (when errors
      (doseq [e errors] (println e))
      (System/exit 1))
    (let [{:keys [sand-bin setup test-dir]} options]
      (if setup
        (setup-test-to-dir test-dir arguments setup)
        (let [opts {:sand-bin (some-> sand-bin fs/absolutize str)}
              {:keys [failed]} (run-tests test-dir arguments opts)]
          (System/exit (if (zero? failed) 0 1)))))))
