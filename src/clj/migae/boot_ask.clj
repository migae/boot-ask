(ns migae.boot-ask
  {:boot/export-tasks true}
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.pprint :as pp]
            [me.raynes.fs :as fs]
            [stencil.core :as stencil]
            [boot.user]
            [boot.pod :as pod]
            [boot.core :as boot]
            [boot.util :as util]
            [boot.task.built-in :as builtin]
            [boot.file            :as file]
            [boot.from.digest     :as digest])
  (:import [com.amazonaws.auth DefaultAWSCredentialsProviderChain]
           [com.amazonaws.services.lambda.model CreateFunctionRequest
                                                GetFunctionConfigurationRequest
                                                GetFunctionConfigurationResult
                                                InvokeRequest InvokeResult
                                                UpdateFunctionCodeRequest UpdateFunctionCodeResult
                                                FunctionCode
                                                Environment]
           [com.amazonaws.services.lambda AWSLambdaClient AWSLambdaClientBuilder]
           ;; [com.amazonaws.services.s3 AmazonS3Client]
           [com.amazonaws.regions Regions]
           [java.io File FileInputStream IOException RandomAccessFile]
           [java.nio ByteBuffer MappedByteBuffer]
           [java.nio.channels
            Channels FileChannel FileChannel$MapMode
            ReadableByteChannel WritableByteChannel]
           [java.util.zip GZIPInputStream]
           ))
           ;; [java.net URL URLClassLoader]))

(defn ns->path
  [n]
  (let [nss (str n)
        nspath (str (-> nss (str/replace \- \_) (str/replace "." "/")))]
    ;; (println "nspath: " nspath)
    nspath))

;; master connfig file
(def boot-config-edn "_boot_config.edn")

(def speechlets-edn "speechlets.edn")

;; (def aws-credentials
;;   (.getCredentials (DefaultAWSCredentialsProviderChain.)))

;; (defn- create-lambda-client [region]
;;   (-> (AWSLambdaClient. aws-credentials)
;;       (.withRegion (Regions/fromName region))))

(defn expand-home [s]
  (if (or (.startsWith s "~") (.startsWith s "$HOME"))
    (str/replace-first s "~" (System/getProperty "user.home"))
    s))

(def config-map (boot/get-env))

;; for multiple subprojects:
(def root-dir "")
(def root-project "")

(def project-dir (System/getProperty "user.dir"))
(def build-dir (str/join "/" [project-dir "target"]))

(defn output-libs-dir [nm] (str/join "/" [build-dir "libs"]))
(defn output-resources-dir [nm] (str/join "/" [build-dir "resources" nm]))

(defn java-source-dir [nm] (str/join "/" [project-dir "src" nm "java"]))
(defn input-resources-dir [nm] (str/join "/" [project-dir "src" nm "resources"]))

(def java-classpath-sys-prop-key "java.class.path")

(defn print-task
  [task opts]
  (if (or (:verbose opts) (:verbose config-map) (:list-tasks config-map))
    (println "TASK: " task)))

(defn- find-mainfiles [fs]
  (->> fs
       boot/input-files
       (boot/by-ext [".clj"])))

;; (declare libs logging reloader servlets webxml)

(defn- normalize-speechlet-configs
  ;; NB: only needed for servlets?
  [configs]
  {:speechlets
   (vec (flatten (for [config (:speechlets configs)]
                   ;;(println "CONFIG: " config)
                     (let [urls (into '() (for [url (-> config :servlet :urls)]
                                           {:url (str url)}))
                           name (-> config :skill :name)
                           ;; ns (if (:servlet config) (-> config :servlet :ns) (:ns config))
                           ]
                       ;; (println "URLS: " urls)
                       (merge config {:urls [{:path (-> config :servlet :url :path)
                                              :name (-> config :skill :name)}]
                                      :name name
                                      ;; :ns ns
                                      })))))})

(boot/deftask deploy-lambda
  "Install service component"
  [p project PROJECT sym "project name"
   r version VERSION str "project version string"
   v verbose bool "Print trace messages"]
  (fn middleware [next-handler]
    (fn handler [fileset]
      (let [e (boot/get-env)
            ;;target-dir (get-target-dir fileset false)
            jarname (util/jarname project version)
            ;;jarpath (str target-dir "/" jarname)
            target-middleware (comp
                               (builtin/pom)
                               (builtin/uber)
                               (builtin/jar)
                               #_(install-jarfile-internal jarname))
            target-handler (target-middleware next-handler)]
        (target-handler fileset)))))

(boot/deftask keep-config
  "Retain master config file"
  []
  (builtin/sift :to-resource #{(re-pattern (str ".*" boot-config-edn))}))

(boot/deftask lambdas
  "AOT-compile SpeechletRequestStreamHandler for Lambda skill implementation"
  [k keep bool "keep intermediate .clj files"
   ;; n gen-speechlets-ns NS str "namespace to generate and aot; default: 'speechlets"
   v verbose bool "Print trace messages."]
  (let [workspace (boot/tmp-dir!)
        prev-pre (atom nil)
        gen-handlers-workspace (boot/tmp-dir!)]
    (if verbose (util/info (str "Configuring speechlet lambda handlers\n")))
    (comp
     (boot/with-pre-wrap [fileset]
       ;; step 1: master config file
       (let [boot-config-edn-files (->> (boot/fileset-diff @prev-pre fileset)
                                        boot/input-files
                                        (boot/by-re [(re-pattern (str boot-config-edn "$"))]))
             boot-config-edn-f (condp = (count boot-config-edn-files)
                                 0 (do (if verbose (util/info (str "Creating " boot-config-edn "\n")))
                                       (io/file boot-config-edn)) ;; this creates a java.io.File
                                 1 (first boot-config-edn-files)  ;; this is a boot.tmpdir.TmpFile
                                 (throw (Exception.
                                         (str "Only one " boot-config-edn " file allowed; found "
                                              (count boot-config-edn-files)))))
             boot-config-edn-map (if (instance? boot.tmpdir.TmpFile boot-config-edn-f)
                                   (-> (boot/tmp-file boot-config-edn-f) slurp read-string)
                                   {})]
         ;; (println "boot-config-edn-map: " boot-config-edn-map)

         (if (:speechlets boot-config-edn-map)
           fileset
           (do
             ;; step 1: read the edn config file and construct map
             (let [speechlets-edn-files (->> (boot/input-files fileset)
                                             (boot/by-name [speechlets-edn]))
                   speechlets-edn-f (condp = (count speechlets-edn-files)
                                      0 (throw (Exception. (str "cannot find " speechlets-edn)))
                                      1 (first speechlets-edn-files)
                                      ;; > 1
                                      (throw (Exception.
                                              (str "only one " speechlets-edn "file allowed; found "
                                                   (count speechlets-edn-files)))))
                   speechlets-edn-map (-> (boot/tmp-file speechlets-edn-f) slurp read-string)
                   speechlets-config-map (normalize-speechlet-configs speechlets-edn-map)

                   ;; step 2:  inject speechlet config stanza to master config map
                   master-config (-> boot-config-edn-map
                                     (assoc-in [:servlets] (concat (:servlets boot-config-edn-map)
                                                                   (:speechlets speechlets-config-map))))
                   master-config (with-out-str (pp/pprint master-config))

                   ;; step 3: create new master config file
                   boot-config-edn-out-file (io/file workspace
                                                     (if (instance? boot.tmpdir.TmpFile boot-config-edn-f)
                                                       (boot/tmp-path boot-config-edn-f)
                                                       boot-config-edn-f))]

               ;; step 4: for each speechlet, generate the implementation
               ;;(println "speechlets map: " (:speechlets speechlets-config-map))
               (doseq [speechlet (filter #(:lambda %) (:speechlets speechlets-config-map))]
                 (let [gen-handler-ns (-> speechlet :lambda :ns)
                       _ (println "handler ns: " gen-handler-ns)
                       gen-handlers-content (stencil/render-file
                                             "migae/templates/gen-speechlet-lambdas.mustache"
                                             speechlet)
                                             ;; (assoc speechlet
                                             ;;        :gen-speechlet-handler-ns
                                             ;;        gen-handler-ns))
                                             ;;        ;; :handler-ns
                                             ;;        ;; (-> speechlets-config-map :lambda :handler :ns)))
                       gen-handlers-path (str (ns->path (-> speechlet :lambda :ns)) ".cjl")
                       gen-handlers-out-file (doto (io/file gen-handlers-workspace gen-handlers-path)
                                               io/make-parents)]
                   (spit gen-handlers-out-file gen-handlers-content)))

               (io/make-parents boot-config-edn-out-file)
               (spit boot-config-edn-out-file master-config)

               ;; step 6: commit files to fileset
               (reset! prev-pre
                       (if keep
                       (-> fileset
                           (boot/add-resource workspace)
                           (boot/add-resource gen-handlers-workspace)
                           boot/commit!)
                       (-> fileset
                           (boot/add-source workspace)
                           (boot/add-source gen-handlers-workspace)
                           boot/commit!)))

               (boot/empty-dir! workspace)
               (let [pod-env (update-in (boot/get-env) [:directories] conj (.getPath workspace))
                     compile-pod (future (pod/make-pod pod-env))
                     tgt (.getPath workspace)]
                 ;;(println "COMPILE PATH: " tgt)
                 ;;(println "SPEECHLETS map: " (:speechlets speechlets-config-map))
                 ;;(pod/with-eval-in @compile-pod
                 (binding [*compile-path* tgt]
                     (doseq [speechlet (filter #(:lambda %) (:speechlets speechlets-config-map))]
                       (let [speechlet-ns (-> speechlet :lambda :ns)]
                         (println (str "AOT compiling ns: " speechlet-ns))
                         (compile speechlet-ns)))))
               (reset! prev-pre
                         (-> @prev-pre
                             (boot/add-resource workspace)
                             boot/commit!)))
               )))
       @prev-pre)

     identity)))

(boot/deftask lambda-update
  ""
  [p project PROJECT sym "project name"
   r version VERSION str "project version string"
   v verbose bool "Print trace messages."
   x dry-run bool "dry run"
   z zipfile ZIPFILE str "zipfile containing source"]
  (if verbose (util/info (str "Updating lambda code\n")))
  (boot/with-pre-wrap [fileset]
    ;; step 0: read the edn files
    (let [speechlets-edn-files (->> (boot/input-files fileset)
                                 (boot/by-name [speechlets-edn]))]
      (if (> (count speechlets-edn-files) 1)
        (throw (Exception. "only one speechlets.edn file allowed")))
      (if (= (count speechlets-edn-files) 0)
        (throw (Exception. "cannot find speechlets.edn")))

      (let [edn-speechlets-f (first speechlets-edn-files)
            speechlet-configs (-> (boot/tmp-file edn-speechlets-f) slurp read-string)
            ;;speechlet-configs (normalize-speechlet-configs speechlet-configs)
            ]
        ;; (println "new boot-config-edn: " master-config)
        ;; (println "clj-speechlets: " clj-speechlets)

        (doseq [speechlet (filter #(:lambda %) (:speechlets speechlet-configs))]
          (let [zipfile (str "target/" (util/jarname project version))
                raf (RandomAccessFile. zipfile "r")
                channel (.getChannel raf)
                buffer (.map channel FileChannel$MapMode/READ_ONLY 0 (.size channel))
                ]
            (if (or verbose dry-run)
              (do
                (println "name:   " (-> speechlet :lambda :name))
                (println "zipfile: " zipfile)))
            (if (not dry-run)
              (let [client  (AWSLambdaClientBuilder/defaultClient)
                    ^UpdateFunctionCodeResult
                    result (.updateFunctionCode
                            client
                            (-> (UpdateFunctionCodeRequest.)
                                (.withFunctionName (-> speechlet :lambda :name))
                                (.withZipFile (.load buffer))))]
                (println "RESULT: " (.toString result))
                ))))))
        fileset))

(boot/deftask security
  "Configure security for Alexa skill implementation"
  ;; default is prod security:
  ;;   omit com.amazon.speech.speechlet.servlet.disableRequestSignatureCheck
  ;;   pull com.amazon.speech.speechlet.servlet.supportedApplicationIds from speechlets.edn
  [i id-verification bool "enable supportedApplicationIds verification"
   t test bool "test security - no sigcheck, not app id verification"
   v verbose bool "Print trace messages."]
  (let [workspace (boot/tmp-dir!)
        prev-pre (atom nil)]
    (comp
     (boot/with-pre-wrap [fileset]
       (let [boot-config-edn-files (->> (boot/fileset-diff @prev-pre fileset)
                                        boot/input-files
                                        (boot/by-re [(re-pattern (str boot-config-edn "$"))]))
             boot-config-edn-f (condp = (count boot-config-edn-files)
                                 0 (do (if verbose (util/info (str "Creating " boot-config-edn "\n")))
                                       (io/file boot-config-edn)) ;; this creates a java.io.File
                                 1 (first boot-config-edn-files)  ;; this is a boot.tmpdir.TmpFile
                                 (throw (Exception.
                                         (str "Only one " boot-config-edn " file allowed; found "
                                              (count boot-config-edn-files)))))
             boot-config-edn-map (if (instance? boot.tmpdir.TmpFile boot-config-edn-f)
                                   (-> (boot/tmp-file boot-config-edn-f) slurp read-string)
                                   {})
             speechlets-edn-files (->> (boot/input-files fileset)
                                       (boot/by-name [speechlets-edn]))
             speechlets-edn-f (condp = (count speechlets-edn-files)
                                0 (throw (Exception. (str "cannot find " speechlets-edn)))
                                1 (first speechlets-edn-files)
                                ;; > 1
                                (throw (Exception.
                                        (str "only one " speechlets-edn "file allowed; found "
                                             (count speechlets-edn-files)))))
             speechlets-config-map (-> (boot/tmp-file speechlets-edn-f) slurp read-string)

             speechlets (:speechlets speechlets-config-map)
             skill-ids (for [speechlet speechlets] (-> speechlet :skill :id))

             ;; maybe inject skill ids into master config map
             master-config (if test
                             (-> boot-config-edn-map
                                 (assoc-in [:system-properties]
                                           (vec (into (:system-properties boot-config-edn-map)
                                                      [{:name "com.amazon.speech.speechlet.servlet.disableRequestSignatureCheck"
                                                        :value true}]))))
                             (-> boot-config-edn-map
                                 (assoc-in [:system-properties]
                                           (vec (into (:system-properties boot-config-edn-map)
                                                      [{:name "com.amazon.speech.speechlet.servlet.supportedApplicationIds"
                                                        :value (str/join "," skill-ids)}])))))


             master-config (with-out-str (pp/pprint master-config))
             ;; _ (println "master-config: " master-config)

             boot-config-edn-out-file (io/file workspace
                                               (if (instance? boot.tmpdir.TmpFile boot-config-edn-f)
                                                 (boot/tmp-path boot-config-edn-f)
                                                 boot-config-edn-f))]

         (io/make-parents boot-config-edn-out-file)
         (spit boot-config-edn-out-file master-config)

         (reset! prev-pre
               (-> fileset
                   (boot/add-resource workspace)
                   boot/commit!)))
       @prev-pre))))

(boot/deftask servlets
  "AOT-compile SpeechletServlets"
  [k keep bool "keep intermediate .clj files"
   ;; n gen-speechlets-ns NS str "namespace to generate and aot; default: 'speechlets"
   v verbose bool "Print trace messages."]
  (let [workspace (boot/tmp-dir!)
        prev-pre (atom nil)

        gen-servlets-workspace (boot/tmp-dir!)
        ;;gen-servlets-ns        (:ns servlets-config-map)
        ;;gen-servlets-path      "helloworld/speechlet/servlet.clj" ;; (str gen-servlets-ns ".clj")]
        ]

    (if verbose (util/info (str "Configuring speechlet servlets\n")))
    (comp
     (boot/with-pre-wrap [fileset]
       ;; step 1: master config file
       (let [boot-config-edn-files (->> (boot/fileset-diff @prev-pre fileset)
                                        boot/input-files
                                        (boot/by-re [(re-pattern (str boot-config-edn "$"))]))
             boot-config-edn-f (condp = (count boot-config-edn-files)
                                 0 (do (if verbose (util/info (str "Creating " boot-config-edn "\n")))
                                       (io/file boot-config-edn)) ;; this creates a java.io.File
                                 1 (first boot-config-edn-files)  ;; this is a boot.tmpdir.TmpFile
                                 (throw (Exception.
                                         (str "Only one " boot-config-edn " file allowed; found "
                                              (count boot-config-edn-files)))))
             boot-config-edn-map (if (instance? boot.tmpdir.TmpFile boot-config-edn-f)
                                   (-> (boot/tmp-file boot-config-edn-f) slurp read-string)
                                   {})]
         ;; (println "boot-config-edn-map: " boot-config-edn-map)

         (if (:speechlets boot-config-edn-map)
           fileset
           (do
             ;; step 1: read the servlets edn config file and construct map
             (let [speechlets-edn-files (->> (boot/input-files fileset)
                                             (boot/by-name [speechlets-edn]))
                   speechlets-edn-f (condp = (count speechlets-edn-files)
                                      0 (throw (Exception. (str "cannot find " speechlets-edn)))
                                      1 (first speechlets-edn-files)
                                      ;; > 1
                                      (throw (Exception.
                                              (str "only one " speechlets-edn "file allowed; found "
                                                   (count speechlets-edn-files)))))
                   speechlets-edn-map (-> (boot/tmp-file speechlets-edn-f) slurp read-string)
                   speechlets-config-map (normalize-speechlet-configs speechlets-edn-map)

                   ;; step 2:  inject speechlet config stanza to master config map
                   master-config (-> boot-config-edn-map
                                     (assoc-in [:servlets] (concat (:servlets boot-config-edn-map)
                                                                   (:speechlets speechlets-config-map))))
                   master-config (with-out-str (pp/pprint master-config))

                   ;; step 3: create new master config file
                   boot-config-edn-out-file (io/file workspace
                                                     (if (instance? boot.tmpdir.TmpFile boot-config-edn-f)
                                                       (boot/tmp-path boot-config-edn-f)
                                                       boot-config-edn-f))]

               ;; step 4: for each speechlet, generate the implementation
               ;;(println "speechlets map: " (:speechlets speechlets-config-map))
               (doseq [speechlet (:speechlets speechlets-config-map)]
                 (let [gen-servlets-ns (-> speechlet :servlet :ns)
                       gen-servlets-content (stencil/render-file
                                             "migae/templates/gen-speechlet-servlets.mustache"
                                             (assoc speechlet ;; s-config-map
                                                    :gen-speechlet-servlets-ns
                                                    gen-servlets-ns))
                       gen-servlets-path (str (ns->path (-> speechlet :servlet :ns)) ".clj")
                       ;; _ (println "gen-servlets-path: " gen-servlets-path)

                       gen-servlets-out-file (doto (io/file gen-servlets-workspace gen-servlets-path)
                                               io/make-parents)]

                   ;;(println "gen-servlets-out-file: " gen-servlets-out-file)
                   (spit gen-servlets-out-file gen-servlets-content)))

               ;; step 6: write new master config files
               (io/make-parents boot-config-edn-out-file)
               (spit boot-config-edn-out-file master-config)

               ;; step 6: commit files to fileset
               (reset! prev-pre
                       (if keep
                         (-> fileset
                             (boot/add-resource gen-servlets-workspace)
                             (boot/add-resource workspace)
                             boot/commit!)
                         (-> fileset
                             (boot/add-source gen-servlets-workspace)
                             (boot/add-source workspace)
                             boot/commit!)))

               ;; now aot-compile
               (boot/empty-dir! workspace)
               (let [pod-env (update-in (boot/get-env) [:directories] conj (.getPath workspace))
                     compile-pod (future (pod/make-pod pod-env))
                     tgt (.getPath workspace)]
                 ;;(println "COMPILE PATH: " tgt)
                 ;;(println "SPEECHLETS map: " (:speechlets speechlets-config-map))
                 ;;(pod/with-eval-in @compile-pod
                   (binding [*compile-path* tgt]
                     (doseq [speechlet (:speechlets speechlets-config-map)]
                       ;; (println (str "AOT: " speechlet)) ;; (-> speechlet :servlet :ns)))
                       (compile (-> speechlet :servlet :ns)))))
               (reset! prev-pre
                         (-> @prev-pre
                             (boot/add-resource workspace)
                             boot/commit!)))
               )))
       @prev-pre)

     identity)))

(boot/deftask speechlets
  "AOT-compile alexa Speechlets"
  [k keep bool "keep intermediate .clj files"
   ;; n gen-speechlets-ns NS str "namespace to generate and aot; default: 'speechlets"
   v verbose bool "Print trace messages."]
  (let [workspace (boot/tmp-dir!)
        intents-workspace (boot/tmp-dir!)
        prev-pre (atom nil)
        gen-speechlets-workspace (boot/tmp-dir!)
        gen-speechlets-ns        (gensym "speechletsgen")
        gen-speechlets-path      (str gen-speechlets-ns ".clj")]
    (comp
     (boot/with-pre-wrap [fileset]
       ;; step 1: master config file
       (let [boot-config-edn-files (->> (boot/fileset-diff @prev-pre fileset)
                                        boot/input-files
                                        (boot/by-re [(re-pattern (str boot-config-edn "$"))]))
             boot-config-edn-f (condp = (count boot-config-edn-files)
                                 0 (do (if verbose (util/info (str "Creating " boot-config-edn "\n")))
                                       (io/file boot-config-edn)) ;; this creates a java.io.File
                                 1 (first boot-config-edn-files)  ;; this is a boot.tmpdir.TmpFile
                                 (throw (Exception.
                                         (str "Only one " boot-config-edn " file allowed; found "
                                              (count boot-config-edn-files)))))
             boot-config-edn-map (if (instance? boot.tmpdir.TmpFile boot-config-edn-f)
                                   (-> (boot/tmp-file boot-config-edn-f) slurp read-string)
                                   {})]
         ;; (println "boot-config-edn-map: " boot-config-edn-map)

         (if (:speechlets boot-config-edn-map)
           fileset
           (do
             ;; step 1: read the edn config file and construct map
             (let [speechlets-edn-files (->> (boot/input-files fileset)
                                             (boot/by-name [speechlets-edn]))
                   speechlets-edn-f (condp = (count speechlets-edn-files)
                                      0 (throw (Exception. (str "cannot find " speechlets-edn)))
                                      1 (first speechlets-edn-files)
                                      ;; > 1
                                      (throw (Exception.
                                              (str "only one " speechlets-edn "file allowed; found "
                                                   (count speechlets-edn-files)))))
                   speechlets-config-map (-> (boot/tmp-file speechlets-edn-f) slurp read-string)
                   ;;speechlets-config-map (normalize-speechlet-configs speechlets-edn-map)

                   ;; step 2:  inject speechlet config stanza to master config map
                   ;; master-config (-> boot-config-edn-map
                   ;;                   (assoc-in [:servlets] (concat (:servlets boot-config-edn-map)
                   ;;                                                 (:speechlets speechlets-config-map))))
                   ;; master-config (with-out-str (pp/pprint master-config))

                   ;; step 3: create new master config file
                   boot-config-edn-out-file (io/file workspace
                                                     (if (instance? boot.tmpdir.TmpFile boot-config-edn-f)
                                                       (boot/tmp-path boot-config-edn-f)
                                                       boot-config-edn-f))

                   ;; step 4: create speechlet generator
                   gen-speechlets-content (stencil/render-file "migae/templates/gen-speechlets.mustache"
                                                               (assoc speechlets-config-map
                                                                      :gen-speechlets-ns
                                                                      gen-speechlets-ns))
                   gen-speechlets-out-file (doto (io/file gen-speechlets-workspace gen-speechlets-path)
                                             io/make-parents)]

               ;; step 5: create intents.clj files
               (doseq [speechlet (:speechlets speechlets-config-map)]
                 (let [intents-content (stencil/render-file
                                        "migae/templates/intents.mustache"
                                        speechlet)
                       intents-out-file (doto (io/file intents-workspace
                                                       (ns->path (:ns speechlet))
                                                       "intents.clj")
                                             io/make-parents)]
                   (spit intents-out-file intents-content)))

               ;; step 5: write new files
               ;; (io/make-parents boot-config-edn-out-file)
               ;; (spit boot-config-edn-out-file master-config)
               (spit gen-speechlets-out-file gen-speechlets-content)))))

       (if verbose (util/info (str "Configuring speechlets\n")))

       ;; step 6: commit files to fileset
       (reset! prev-pre
               (-> fileset
                   (boot/add-asset intents-workspace)
                   (boot/add-source workspace)
                   (boot/add-source gen-speechlets-workspace)
                   boot/commit!)))
     (builtin/aot :namespace #{gen-speechlets-ns})

     (if keep
       identity
       (builtin/sift :include #{(re-pattern (str gen-speechlets-ns ".*.class"))}
                     :invert true))
     (if keep
       (comp
        (builtin/sift :to-asset #{(re-pattern gen-speechlets-path)}))
       identity)
     )))

