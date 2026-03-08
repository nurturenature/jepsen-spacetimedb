(ns spacetimedb.cli
  "Command-line entry point for SpacetimeDB tests."
  (:require [clojure.string :as str]
            [jepsen
             [checker :as checker]
             [cli :as cli]
             [generator :as gen]
             [tests :as tests]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.os.debian :as debian]
            [spacetimedb
             [db :as db]
             [nemesis :as nemesis]
             [workload :as workload]]))

(def workloads
  "A map of workload names to functions that take CLI options and return
  workload maps."
  {:procedures workload/procedures
   :none       (fn [_] tests/noop-test)})

(def all-workloads
  "Default collection of workloads for test-all."
  [:procedures])

(def nemeses
  "A collection of valid nemeses."
  #{:disconnect
    :stop-start
    :partition-stdb
    :pause :kill})

(def all-nemeses
  "Combinations of nemeses for test-all"
  [[]
   [:disconnect]
   [:stop-start]
   [:pause]
   [:partition-stdb]
   [:pause :partition-stdb]
   [:kill]])

(def special-nemeses
  "A map of special nemesis names to collections of faults"
  {:none []
   :all  nemeses})

(defn parse-nemesis-spec
  "Takes a comma-separated nemesis string and returns a collection of keyword
  faults."
  [spec]
  (->> (str/split spec #",")
       (map keyword)
       (mapcat #(get special-nemeses % [%]))))

(defn test-name
  "Given opts, returns a meaningful test name."
  [{:keys [nemesis nodes rate time-limit workload] :as _opts}]
  (let [nodes (into #{} nodes)]
    (str (name workload)
         " " (str/join "," (map name nemesis))
         " " (count nodes) "n"
         "-" rate "tps"
         "-" time-limit "s")))

(defn spacetimedb-test
  "Given options from the CLI, constructs a test map."
  [opts]
  (let [workload-name (:workload opts)
        workload ((workloads workload-name) opts)
        db       (:db workload)
        nemesis  (nemesis/nemesis-package
                  {:db                 db
                   :nodes              (:nodes opts)
                   :faults             (:nemesis opts)
                   :disconnect         {:targets [nil]}
                   :stop-start         {:targets [nil]}
                   :partition-stdb     {:targets [nil]}
                   :pause              {:targets [nil]}
                   :kill               {:targets [:majority]}
                   :interval           (:nemesis-interval opts)})]
    (merge tests/noop-test
           opts
           {:name      (test-name opts)
            :os        debian/os
            :db        db
            :checker   (checker/compose
                        {:perf               (checker/perf
                                              {:nemeses (:perf nemesis)})
                         :timeline           (timeline/html)
                         :stats              (checker/stats)
                         :exceptions         (checker/unhandled-exceptions)
                         :logs-ps-client     (checker/log-file-pattern #"(SEVERE)|(ERROR)" db/log-file-short)
                         :workload           (:checker workload)})
            :client    (:client workload)
            :nemesis   (:nemesis nemesis)
            :generator (gen/phases
                        (gen/log "Workload with nemesis")
                        (->> (:generator workload)
                             (gen/stagger    (/ (:rate opts)))
                             (gen/nemesis    (:generator nemesis))
                             (gen/time-limit (:time-limit opts)))

                        (gen/log "Final nemesis")
                        (gen/nemesis (:final-generator nemesis))

                        (gen/log "Final workload")
                        (->> (:final-generator workload)
                             (gen/stagger (/ (:rate opts)))))
            :roles     (:roles workload)})))

(def cli-opts
  "Command line options"
  [[nil "--client-timeout SECS" "The number of seconds to wait before timing out a client connection."
    :default  3
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer"]]

   [nil "--keys-txn NUM" "The number of keys to act on in a transactions."
    :default  4
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer"]]

   [nil "--nemesis FAULTS" "A comma-separated list of nemesis faults to enable"
    :parse-fn parse-nemesis-spec
    :validate [(partial every? nemeses)
               (str "Faults must be " nemeses ", or the special faults all or none.")]]

   [nil "--nemesis-interval SECS" "Roughly how long between nemesis operations."
    :default 5
    :parse-fn read-string
    :validate [pos? "Must be a positive number."]]

   ["-r" "--rate HZ" "Approximate request rate, in hz"
    :default 100
    :parse-fn read-string
    :validate [name pos? "Must be a positive number."]]

   [nil "--spacetimedb-node NODE" "Node to install SpacetimeDB on."
    :default  "spacetimedb"
    :parse-fn read-string
    :validate [string? "Must be a String."]]

   ["-w" "--workload NAME" "What workload should we run?"
    :parse-fn keyword
    :missing  (str "Must specify a workload: " (cli/one-of workloads))
    :validate [workloads (cli/one-of workloads)]]])

(defn all-tests
  "Turns CLI options into a sequence of tests."
  [opts]
  (let [nemeses   (if-let [n (:nemesis opts)] [n] all-nemeses)
        workloads (if-let [w (:workload opts)] [w] all-workloads)]
    (for [n nemeses, w workloads, _i (range (:test-count opts))]
      (spacetimedb-test (assoc opts :nemesis n :workload w)))))

(defn opt-fn
  "Transforms CLI options before execution."
  [parsed]
  parsed)

(defn -main
  "CLI.
   `lein run` to list commands."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  spacetimedb-test
                                         :opt-spec cli-opts
                                         :opt-fn   opt-fn})
                   (cli/test-all-cmd {:tests-fn all-tests
                                      :opt-spec cli-opts
                                      :opt-fn   opt-fn})
                   (cli/serve-cmd))
            args))
