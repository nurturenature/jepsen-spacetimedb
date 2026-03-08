(defproject spacetimedb "0.0.1-SNAPSHOT"
  :description "Jepsen Tests for SpacetimeDB."
  :url "https://github.com/nurturenature/jepsen-spacetimedb"
  :license {:name "Apache License Version 2.0, January 2004"
            :url "http://www.apache.org/licenses/"}
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [jepsen "0.3.11-SNAPSHOT"]
                 [causal "0.1.0-SNAPSHOT"]
                 [cheshire "6.1.0"]
                 [clj-http "3.13.1"]]
  :jvm-opts ["-Xmx8g"
             "-Djava.awt.headless=true"
             "-server"]
  :main spacetimedb.cli
  :repl-options {:init-ns spacetimedb.repl}
  :plugins [[lein-codox "0.10.8"]
            [lein-localrepo "0.5.4"]]
  :codox {:output-path "target/doc/"
          :source-uri "../../{filepath}#L{line}"
          :metadata {:doc/format :markdown}})
