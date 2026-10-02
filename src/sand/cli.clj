(ns sand.cli
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.java.process :as p]
   [clojure.string :as str]
   [sand.core :as core]
   [sand.git :as git]
   [sand.log :as log]
   [sand.shell-env :as shell-env]
   [sand.util :as u]
   [toml-clj.core :as toml])
  (:gen-class))

(def ^:const BIN-NAME "sand")
(def ^:const BIN-VERSION "0.1.0")

(def global-spec
  "Options accepted by every command, before or after the command name."
  {:debug {:coerce :boolean :desc "Print debugging information"}
   :help {:alias :h :coerce :boolean :desc "Show help"}})

(def file-spec
  {:file {:alias :f :default "sand.toml" :ref "FILE" :desc "Configuration file"}})

(def root-command
  {:description "A CLI for development environments."
   :spec {:version {:coerce :boolean :desc "Print the version"}}})

(def commands
  "The commands, in the order to list them in help."
  [{:name "check"
    :description "Check syntax of a config file."
    :spec file-spec}
   {:name "format"
    :aliases ["fmt"]
    :args-usage "[FILE...]"
    :description "Format source files."
    :spec file-spec}
   {:name "shell"
    :description "Start a development shell."
    :spec file-spec}])

(defn- command-named [action]
  (some #(when (= action (:name %)) %) commands))

(defn- parse-cli
  "Parses args, returning a map with the :action (nil for no command),
   and the :opts and :args parsed by babashka.cli. Throws an ExceptionInfo
   for an invalid option."
  [args]
  (cli/dispatch
    (concat
      (for [{:keys [aliases args-usage name spec]} commands
            cmd (cons name aliases)]
        (cond-> {:cmds [cmd]
                 :fn #(assoc % :action name)
                 :restrict true
                 :spec spec}
          ; Binding positional args to an option lets options come after
          ; them, like `sand format a.rs --debug`.
          args-usage (assoc :args->opts (repeat :files)
                       :spec (assoc spec :files {:coerce []}))))
      [{:cmds []
        :fn #(assoc % :action nil)
        :restrict true
        :spec (:spec root-command)}])
    args
    {:spec global-spec}))

(defn command-usage [action]
  (let [{:keys [args-usage description spec]} (or (command-named action) root-command)]
    (str/join "\n"
      (concat
        [(str "Usage:\t" BIN-NAME " " (or action "[command]") " [options]"
           (when args-usage (str " " args-usage)))
         nil
         description
         nil
         "Options:"
         (cli/format-opts {:spec (merge spec global-spec)
                           :order (vec (concat (keys spec) (keys global-spec)))})]
        (when (nil? action)
          (concat
            [nil
             "Commands:"]
            (for [{:keys [aliases description name]} commands]
              (str "  " name
                (subs "            " 0 (- 12 (count name)))
                description
                (when (seq aliases)
                  (str " [alias: " (str/join ", " aliases) "]"))))))))))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with an error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [action error opts] :as parsed}
        (try
          (parse-cli args)
          (catch clojure.lang.ExceptionInfo e
            {:error (ex-message e)}))
        ; Args after -- aren't parsed as options
        arguments (vec (concat (:files opts) (:args parsed)))]
    (when (:debug opts)
      (print "parsed-opts: ")
      (prn parsed))
    (cond
      error
      {:exit-message error
       :ok? false}

      (and (nil? action) (seq arguments))
      {:exit-message (str "Unknown command: " (first arguments))
       :ok? false}

      (:help opts)
      {:exit-message (command-usage action)
       :ok? true}

      (and (:version opts) (nil? action))
      {:exit-message (str BIN-NAME " " BIN-VERSION)
       :ok? true}

      (nil? action)
      {:exit-message (command-usage nil)
       :ok? true}

      :else
      {:action action
       :arguments arguments
       :options (dissoc opts :files)})))

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

(defn- warn-missing-packages
  "Warns about the packages that the shell couldn't find, and returns
   shell-env with them as :missing-packages, and without the variable
   that listed them in its :env."
  [shell-env]
  (let [{:strs [nixpkgs packages]} (some-> (get-in shell-env [:env "SAND_MISSING_PKGS"])
                                     json/read-str)]
    (binding [*out* *err*]
      (doseq [package packages]
        (println (str "sand: warning: package '" package "' isn't in " nixpkgs))))
    (-> shell-env
      (update :env dissoc "SAND_MISSING_PKGS")
      (assoc :missing-packages (set packages)))))

(defn- load-shell-env!
  "Returns the shell environment for dot-sand-dir, as returned by
   shell-env/shell-env!, with the packages and nixpkgs input in
   sand-json-opts added. Packages that aren't in nixpkgs are left out of
   the shell with a warning, and returned as :missing-packages. sand.json
   is only updated once the shell has been evaluated successfully, and
   without the missing packages, so they don't break later runs."
  [dot-sand-dir temp? sand-json-opts]
  (let [sand-json (-> (core/read-sand-json dot-sand-dir)
                    (core/generate-sand-json sand-json-opts)
                    core/sand-json-str)
        result (-> (shell-env/shell-env! dot-sand-dir sand-json {:temp? temp?})
                 warn-missing-packages)]
    (core/update-sand-json! dot-sand-dir
      (assoc sand-json-opts :missing-packages (:missing-packages result)))
    result))

(defn shell [{:keys [options]}]
  (let [; {:keys [file]} options
        ; TODO Use sand.toml if exists
        ; base-dir (fs/parent file)
        base-dir "."
        ; config-str (slurp file)
        ; _ (check-config-str config-str options)
        ; {:strs [shell]} (core/conform-config (toml/read-string config-str))
        shell {}
        nixpkgs-input (core/find-flake-nixpkgs base-dir)]
    (core/with-dot-sand-dir [{:keys [dot-sand-dir temp?]} base-dir]
      ; Registers GC roots for the shell's inputs, if not already done.
      (shell-env/finish!
        (load-shell-env! dot-sand-dir temp? {:nixpkgs-input nixpkgs-input :packages []}))
      (p/exec
        {:dir base-dir
         :env (merge (shell-env/nix-env) (get shell "env"))
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

(defn format-paths [{:keys [debug]} formatters repo paths]
  (let [dir (or repo (fs/path "."))
        actions (for [path paths
                      :let [file-dir (-> path fs/canonicalize fs/parent)
                            formatter (core/formatter-for-file formatters file-dir (fs/file-name path))]
                      :when formatter]
                  {:fname (str path)
                   :formatter-id (get formatter "id")
                   :formatter formatter})
        formatter-packages (fn [formatter]
                             (cons (get formatter "package")
                               (get formatter "runtime-packages")))
        packages (set (mapcat (comp formatter-packages :formatter) actions))
        nixpkgs-input (core/find-flake-nixpkgs dir)]
    (core/with-dot-sand-dir [{:keys [dot-sand-dir temp?]} dir]
      (let [groups (for [[_ actions] (group-by :formatter-id actions)
                         :let [{:keys [formatter]} (first actions)]]
                     {:cmds (core/formatter-args formatter (map :fname actions))
                      :fnames (map :fname actions)
                      :formatter formatter})
            shell-env (when (seq groups)
                        (load-shell-env! dot-sand-dir temp?
                          {:nixpkgs-input nixpkgs-input :packages packages}))
            env (:env shell-env)
            {skipped true runnable false}
            #__ (group-by
                  #(boolean (some (:missing-packages shell-env)
                              (formatter-packages (:formatter %))))
                  groups)
            cmds (mapcat :cmds runnable)
            exit-code (try
                        (some
                          (fn [[cmd & args]]
                            (when debug
                              (log/debug (str "Running command: " (str/join " " (map u/shell-quote (cons cmd args))))))
                            (if-let [resolved (shell-env/resolve-command env cmd)]
                              (let [proc (apply p/start
                                           {:dir (str dir) :env env :err :inherit :out :inherit}
                                           resolved
                                           args)
                                    exit-code @(p/exit-ref proc)]
                                (when-not (zero? exit-code)
                                  exit-code))
                              (do
                                (binding [*out* *err*]
                                  (println (str "sand: " cmd ": command not found")))
                                ; The exit code a shell uses for this
                                127)))
                          cmds)
                        (finally
                          (shell-env/finish! shell-env)))
            skipped-count (count (mapcat :fnames skipped))]
        (when (pos? skipped-count)
          (binding [*out* *err*]
            (println (str "sand: skipped " skipped-count " file"
                       (when (not= 1 skipped-count) "s")
                       " that need a missing package"))))
        (cond
          exit-code (exit exit-code)
          (pos? skipped-count) (exit 1))))))

(defn fmt [{:keys [arguments options]}]
  (let [{:keys [debug]} options
        formatters (-> "SAND_DATA_DIR"
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
                              (let [{dirs true files false} (group-by fs/directory? paths)]
                                (concat
                                  files
                                  ; git ls-files with no pathspec lists the whole
                                  ; repo, so only call it when dirs were given.
                                  (when (seq dirs)
                                    ; git lists paths relative to the repo root
                                    (map #(fs/path repo %)
                                      (git/list-unignored-files
                                        {:dir (str repo)}
                                        (map str dirs)))))))))
                        {}))]
    (doseq [[repo paths] repo->paths]
      (when debug
        (if repo
          (log/debug (str "Running formatters in git repo " (pr-str (str repo))))
          (log/debug "Running formatters with no git repo")))
      (format-paths options formatters repo paths))))

(defn -main [& args]
  (let [parsed-opts (validate-args args)
        {:keys [action exit-message ok?]} parsed-opts]
    (if exit-message
      (if ok?
        (exit 0 exit-message)
        (binding [*out* *err*]
          (exit 1 exit-message)))
      (try
        (case action
          "check" (check parsed-opts)
          "format" (fmt parsed-opts)
          "shell" (shell parsed-opts))
        (catch clojure.lang.ExceptionInfo e
          (if (u/user-error? e)
            (do
              (binding [*out* *err*]
                (println (str "sand: " (ex-message e))))
              (exit (:exit-code (ex-data e))))
            (throw e)))))))
