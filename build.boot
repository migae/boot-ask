(def +project+ 'migae/boot-ask)
(def +version+ "0.1.0-SNAPSHOT")

(def aws-version "1.11.78")

(set-env!
 ;; :source-paths #{"src/java"}
 :resource-paths #{"src/clj"}
 ;; :asset-paths #{"src"}

 :repositories #(conj % ["maven-central" {:url "http://mvnrepository.com"}]
                      ["central" "http://repo1.maven.org/maven2/"])

 :dependencies   `[[org.clojure/clojure "1.8.0" :scope "provided"]
                   [boot/core "2.7.1" :scope "provided"]
                   [boot/pod "2.7.1" :scope "provided"]
                   [me.raynes/fs "1.4.6"]
                   [stencil/stencil "0.5.0"]
                   [adzerk/boot-test "1.0.7" :scope "test"]

                   [com.amazonaws/aws-java-sdk ~aws-version]
                   [com.amazonaws/aws-java-sdk-lambda ~aws-version]
                   [com.amazonaws/aws-java-sdk-iam ~aws-version]

                   [com.amazonaws/aws-lambda-java-core "1.1.0"]
                   [com.amazon.alexa/alexa-skills-kit "1.2" :scope "compile"]
                  ])

(task-options!
 ;; target {:dir "build"}
 pom  {:project     +project+
       :version     +version+
       :description "Boot for AWS Lambda"
       :url         "https://github.com/migae/boot-aws"
       :scm         {:url "https://github.com/migae/boot-aws"}
       :license     {"EPL" "http://www.eclipse.org/legal/epl-v10.html"}})

(deftask monitor
  "watch etc."
  [v verbose bool "verbose"]
  (comp (watch)
        (notify :audible true)
        ;; (javac)
        (pom)
        (jar)
        (install)))
