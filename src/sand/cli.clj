(ns sand.cli
  (:require
   [babashka.fs :as fs]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.java.process :as p]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [sand.core :as core]
   [sand.git :as git]
   [toml-clj.core :as toml])
  (:gen-class))

(def ^:const BIN-NAME "sand")
(def ^:const BIN-VERSION "0.1.0")

(def global-options
  [[nil "--debug"]
   ["-h" "--help"]])

(def cli-spec
  {nil
   {:description
    "A CLI for development environments."
    :options
    [[nil "--version"]]}
   "check"
   {:description "Check syntax of a config file."
    :options
    [["-f" "--file FILE" "Configuration file"
      :default "sand.toml"]]}
   "format"
   {:description "Format a source file."
    :options
    [["-f" "--file FILE" "Configuration file"
      :default "sand.toml"]
     [nil nil "File to format"
      :id :files-to-format
      :required "FILE"]]}
   "shell"
   {:description
    "Start a development shell."
    :options
    [["-f" "--file FILE" "Configuration file"
      :default "sand.toml"]]}})

(defn command-usage [action parsed-opts]
  (let [{:keys [description]} (cli-spec action)
        {:keys [summary]} parsed-opts]
    (str/join "\n"
      (concat
        [(str "Usage:\t" BIN-NAME " " (or action "[command]") " [options]")
         nil]
        (when description
          [description
           nil])
        ["Options:"
         summary]
        (when (nil? action)
          (concat
            [nil
             "Commands:"]
            (for [[k {:keys [description]}] cli-spec
                  :when k]
              (str "  " k
                (subs "                  " 0 (- 12 (count k)))
                description))))))))

(defn reorder-help-args
  "Moves one or more help args after the action, if there is one.
   This allows `sand --help bench` to work the same as
   `sand bench --help`."
  [args]
  (let [farg (first args)]
    (if (or (= "-h" farg) (= "--help" farg))
      (let [other-args (some->> args next reorder-help-args)]
        (if (some-> (first other-args) (str/starts-with? "-"))
          args
          (cons (first other-args)
            (cons farg (rest other-args)))))
      args)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with an error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [args (reorder-help-args args)
        maybe-action (first args)
        action (when-not (or (nil? maybe-action)
                           (str/starts-with? maybe-action "-"))
                 maybe-action)
        action-args (if action (next args) args)
        valid-action? (contains? cli-spec action)
        parsed-opts (when valid-action?
                      (parse-opts action-args
                        (concat
                          (:options (cli-spec action))
                          global-options)))
        {:keys [options errors]} parsed-opts]
    (when (:debug options)
      (print "parsed-opts: ")
      (prn parsed-opts))
    (cond
      (not valid-action?)
      {:exit-message (str "Unknown command: " action)
       :ok? false}

      (seq errors)
      {:exit-message (str/join \newline errors)
       :ok? false}

      (:help options)
      {:exit-message (command-usage action parsed-opts)
       :ok? true}

      (and (:version options) (nil? action))
      {:exit-message (str BIN-NAME " " BIN-VERSION)
       :ok? true}

      (nil? action)
      {:exit-message (command-usage nil parsed-opts)
       :ok? true}

      :else
      (assoc parsed-opts :action action))))

(defn ^:dynamic exit
  ([status] (System/exit status))
  ([status msg]
   (println msg)
   (System/exit status)))

(defn get-schema-file []
  (let [schema-file (System/getenv "SAND_SCHEMA")]
    (when (seq schema-file)
      (str "file://" schema-file))))

(defn check-config-str [config-str {:keys [debug]}]
  (let [schema-file (get-schema-file)
        _ (when debug
            (println "schema-file: " schema-file))
        args (concat
               ["taplo" "lint" "--no-auto-config" "-"]
               (when (seq schema-file)
                 ["--schema" schema-file]))
        p (apply p/start
            {:err :discard
             :in :pipe
             :out :discard}
            args)
        _ (with-open [stdin (p/stdin p)]
            (io/copy config-str stdin))
        exit-code @(p/exit-ref p)]
    ; If validation fails, we re-run it so that we can
    ; get taplo's output.
    (when-not (zero? exit-code)
      (let [p (apply p/start
                {:err :inherit
                 :in :pipe
                 :out :inherit}
                args)]
        (with-open [stdin (p/stdin p)]
          (io/copy config-str stdin)))
      (exit @(p/exit-ref p)))))

(defn shell [{:keys [options]}]
  (fs/with-temp-dir [_tmpdir {:prefix "sand"}]
    (let [; {:keys [file]} options
          ; TODO Use sand.toml if exists
          ; base-dir (fs/parent file)
          base-dir "."
          ; config-str (slurp file)
          ; _ (check-config-str config-str options)
          ; {:strs [shell]} (core/conform-config (toml/read-string config-str))
          shell {}
          nixpkgs-input (core/find-flake-nixpkgs base-dir)
          dot-sand-dir (core/write-dot-sand-dir! base-dir
                         {:nixpkgs-input nixpkgs-input
                          :packages []})]
      (p/exec
        {:dir base-dir
         :env (get shell "env")
         :err :inherit
         :in :inherit
         :out :inherit}
        "nix-shell" (str dot-sand-dir)))))

(defn check [{:keys [options]}]
  (let [{:keys [debug file]} options
        schema-file (get-schema-file)
        _ (when debug
            (println "schema-file: " schema-file))
        args (concat
               ["taplo" "lint" "--no-auto-config" file]
               (when schema-file
                 ["--schema" schema-file]))
        p (apply p/start
            {:err :inherit :out :inherit}
            args)
        exit-code @(p/exit-ref p)]
    (when-not (zero? exit-code)
      (exit exit-code))))

(defn format-paths [formatters repo paths]
  (let [actions (for [path paths
                      :let [formatter (core/formatter-for-file formatters (fs/file-name path))]
                      :when formatter]
                  {:fname (str path)
                   :formatter formatter
                   :repo repo})
        packages (set
                   (mapcat
                     (fn [{:keys [formatter]}]
                       (cons (get formatter "package")
                         (seq (get formatter "runtime-packages"))))
                     actions))
        nixpkgs-input (core/find-flake-nixpkgs repo)
        dot-sand-dir (core/write-dot-sand-dir! repo
                       {:nixpkgs-input nixpkgs-input
                        :packages packages})
        shell-nix (str (fs/path dot-sand-dir "shell.nix"))]
    (doseq [{:keys [fname formatter repo]} actions]
      (let [proc (apply p/start
                   {:dir (str repo) :err :inherit :out :inherit}
                   (core/formatter-args formatter fname shell-nix))
            exit-code @(p/exit-ref proc)]
        (when-not (zero? exit-code)
          (exit exit-code))))))

(defn fmt [{:keys [arguments]}]
  (let [formatters (-> "SAND_DATA_DIR"
                     System/getenv
                     (str "/formatters.toml")
                     slurp
                     toml/read-string
                     core/compile-formatters)
        repo->paths (->> (or (seq arguments) ["."])
                      (map fs/path)
                      git/group-by-repos
                      (reduce
                        (fn [m [repo paths]]
                          (assoc m repo
                            (if (nil? repo)
                              paths
                              (let [grouped (group-by fs/directory? paths)]
                                (concat
                                  (get grouped false)
                                  (map fs/path
                                    (git/list-unignored-files
                                      {:dir (str repo)}
                                      (map str (get grouped true)))))))))
                        {}))]
    (doseq [[repo paths] repo->paths]
      (format-paths formatters repo paths))))

(defn -main [& args]
  (let [parsed-opts (validate-args args)
        {:keys [action exit-message ok?]} parsed-opts]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (case action
        "check" (check parsed-opts)
        "format" (fmt parsed-opts)
        "shell" (shell parsed-opts)))))
