(defproject juxt/crux-sql "derived-from-git"
  :description "SQL for Crux using Apache Calcite"
  :url "https://github.com/juxt/crux"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [juxt/crux-core "derived-from-git"]
                 [cheshire "5.10.0"]
                 [org.apache.calcite/calcite-core "1.22.0"]
                 [org.apache.calcite.avatica/avatica-server "1.16.0"]

                 ;; remove illegal reflective warnings:
                 [com.google.protobuf/protobuf-java "3.9.2"]

                 ;; dependency conflict resolution:
                 [org.apache.calcite.avatica/avatica-core "1.16.0"]
                 [com.fasterxml.jackson.core/jackson-annotations "2.10.0"]
                 [com.fasterxml.jackson.core/jackson-databind "2.10.0"]
                 [commons-logging "1.2"]]
  :profiles {:dev {:dependencies [[ch.qos.logback/logback-classic "1.2.3"]
                                  [io.airlift.tpch/tpch "0.10"]

                                  ;; dependency conflict resolution:
                                  [com.google.guava/guava "26.0-jre"]]}}
  :middleware [leiningen.project-version/middleware]
  :java-source-paths ["src"]
  :javac-options ["-source" "8" "-target" "8"
                  "-XDignore.symbol.file"
                  "-Xlint:all,-options,-path"
                  "-Werror"
                  "-proc:none"]
  :pedantic? :warn)