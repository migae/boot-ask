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
        nspath (str (-> nss (str/replace \- \_) (str/replace "." "/")) ".clj")]
    (println "nspath: " nspath)
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
                     (let [urls (into [] (for [url (:urls config)]
                                           {:url (str url)}))
                           ns (if (:servlet config) (-> config :servlet :ns) (:ns config)) ]
                       ;; (println "URLS: " urls)
                       (merge config {:urls urls
                                      :ns ns})))))})

(boot/deftask speechlets
  "AOT-compile alexa Speechlets"
  [k keep bool "keep intermediate .clj files"
   ;; n gen-speechlets-ns NS str "namespace to generate and aot; default: 'speechlets"
   v verbose bool "Print trace messages."]
  (let [workspace (boot/tmp-dir!)
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
               ;; (println "template: " gen-speechlets-content)
               ;; step 5: write new files
               (io/make-parents boot-config-edn-out-file)
               ;; (spit boot-config-edn-out-file master-config)
               (spit gen-speechlets-out-file gen-speechlets-content)))))

       (if verbose (util/info (str "Configuring speechlets\n")))

       ;; step 6: commit files to fileset
       (reset! prev-pre
               (-> fileset
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

(boot/deftask speechlet-lambdas
  "AOT-compile SpeechletRequestStreamHandler for Lambda skill implementation"
  [k keep bool "keep intermediate .clj files"
   ;; n gen-speechlets-ns NS str "namespace to generate and aot; default: 'speechlets"
   v verbose bool "Print trace messages."]
  (let [workspace (boot/tmp-dir!)
        prev-pre (atom nil)

        gen-handlers-workspace (boot/tmp-dir!)
        gen-handlers-ns        (gensym "speechlethandlerssgen")
        gen-handlers-path      (str gen-handlers-ns ".clj")]
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
                                                       boot-config-edn-f))

                   ;; step 4: create speechlet generator
                   gen-handlers-content (stencil/render-file
                                         "migae/templates/gen-speechlet-lambdas.mustache"
                                         (assoc speechlets-config-map
                                                :gen-speechlet-handlers-ns
                                                gen-handlers-ns
                                                :handler-ns
                                                (-> speechlets-config-map :lambda :handler :ns)))
                   gen-handlers-out-file (doto (io/file gen-handlers-workspace gen-handlers-path)
                                             io/make-parents)]
               ;; (println "template: " gen-handlers-content)
               ;; step 5: write new files
               (io/make-parents boot-config-edn-out-file)
               (spit boot-config-edn-out-file master-config)
               (spit gen-handlers-out-file gen-handlers-content)))))

       (if verbose (util/info (str "Configuring speechlet handlers\n")))

       ;; step 6: commit files to fileset
       (reset! prev-pre
               (-> fileset
                   (boot/add-source workspace)
                   (boot/add-source gen-handlers-workspace)
                   boot/commit!)))

     (builtin/aot :namespace #{gen-handlers-ns})

     (if keep
       identity
       (builtin/sift :include #{(re-pattern (str gen-handlers-ns ".*.class"))}
                     :invert true))
     (if keep
       (comp
        (builtin/sift :to-asset #{(re-pattern gen-handlers-path)}))
       identity)
     )))

(boot/deftask speechlet-servlets
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
             ;; step 2:  inject speechlet config stanza to master config map
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
                   ;; _ (println "speechlets-config-map: " speechlets-config-map)

                   ;; this replaces ns with servlet.ns, for use by gae/webxml task
                   servlets-config-map (normalize-speechlet-configs speechlets-config-map)

                   master-config (-> boot-config-edn-map
                                     (assoc-in [:servlets] (concat (:servlets boot-config-edn-map)
                                                                   (:speechlets servlets-config-map))))
                   master-config (with-out-str (pp/pprint master-config))

                   ;; step 3: create new master config file
                   boot-config-edn-out-file (io/file workspace
                                                     (if (instance? boot.tmpdir.TmpFile boot-config-edn-f)
                                                       (boot/tmp-path boot-config-edn-f)
                                                       boot-config-edn-f))]

               ;; step 4: for each speechlet, generate the implementation
               ;; (println "speechlets map: " (:speechlets speechlets-config-map))
               (doseq [speechlet (:speechlets speechlets-config-map)]
                 ;; (println "SPEECHLET: " speechlet)
                 (let [gen-servlets-ns (-> speechlet :servlet :ns)
                       ;;_ (println "gen-servlets-ns: " gen-servlets-ns)
                       gen-servlets-content (stencil/render-file
                                             "migae/templates/gen-speechlet-servlets.mustache"
                                             (assoc speechlet ;; s-config-map
                                                    :gen-speechlet-servlets-ns
                                                    gen-servlets-ns))
                       gen-servlets-path (ns->path (-> speechlet :servlet :ns))
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

     identity

     #_(boot/with-pre-wrap [fileset]
     ;(do (util/info (str "GEN SERVLETS NS: " gen-servlets-ns "\n")) identity)
       #_(builtin/aot :namespace #{gen-servlets-ns})
       identity)

     #_(if keep
       identity
       (builtin/sift :include #{(re-pattern (str #_gen-servlets-path ".class"))}
                     :invert true))
     #_(if keep
       (builtin/sift :to-asset #{(re-pattern (str #_gen-servlets-path ".*"))})
       identity)
     )))
