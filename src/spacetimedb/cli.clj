(ns spacetimedb.cli
  "Command-line entry point for SpacetimeDB tests."
  (:require [clojure.string :as str]
            [elle.consistency-model :refer [all-anomalies all-models]]
            [jepsen
             [checker :as checker]
             [cli :as cli]
             [generator :as gen]
             [tests :as tests]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.os.debian :as debian]
            [spacetimedb
             [nemesis :as nemesis]
             [workload :as workload]]
            [spacetimedb.db
             [client-node :as client-node]
             [spacetimedb :as stdb]]))

(def workloads
  "A map of workload names to functions that take CLI options and return
  workload maps."
  {:ledger-procedure      workload/ledger-procedure
   :wr-register-procedure workload/wr-register-procedure
   :none                  (fn [_] tests/noop-test)})

(def all-workloads
  "Default collection of workloads for test-all."
  [:ledger-procedure :wr-register-procedure])

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

(defn parse-keywords-spec
  "Takes a comma-separated string of models and returns a collection of consistency models."
  [spec]
  (->> (str/split spec #",")
       (mapv keyword)))

(defn parse-longs-spec
  "Takes a comma-separated string of longs and returns a collection of the longs."
  [spec]
  (->> (str/split spec #",")
       (map parse-long)
       (into #{})))

(defn test-name
  "Given opts, returns a meaningful test name."
  [{:keys [concurrency nemesis nodes rate spacetimedb-node time-limit workload] :as _opts}]
  (let [nodes (into #{} nodes)]
    (str (name workload)
         "-" (str/join "," (map name nemesis))
         "-" (->> spacetimedb-node
                  (disj nodes)
                  (count)) "c" ; SpacetimeDB server doesn't count as a client
         "-" concurrency "w"
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
            :roles     (:roles workload)
            :os        debian/os
            :db        db
            :checker   (checker/compose
                        {:perf               (checker/perf
                                              {:nemeses (:perf nemesis)})
                         :timeline           (timeline/html)
                         :stats              (checker/stats)
                         :exceptions         (checker/unhandled-exceptions)
                         :logs-client        (checker/log-file-pattern #"(?i)(ERROR)" client-node/log-file-short)
                         :logs-spacetimedb   (checker/log-file-pattern #"(?i)(ERROR)" stdb/log-file-short)
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
            :spacetimedb (:spacetimedb workload)})))

(def cli-opts
  "Command line options"
  [[nil "--accounts ACCOUNTS" "(ledger) A collection of account identifiers.."
    :default  (into #{} (range 1 11))
    :parse-fn parse-longs-spec
    :validate [(partial every? pos?) "Must be a collection of positive integers."]]

   [nil "--anomalies ANOMALIES" "A list of additional anomalies to check for."
    :default  []
    :parse-fn parse-keywords-spec
    :validate [(partial every? all-anomalies) (str "Must be a collection of anomalies: " all-anomalies ".")]]

   [nil "--client-timeout SECS" "The number of seconds to wait before timing out a client connection."
    :default  3
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer"]]

   [nil "--consistency-models MODELS" "A list of consistency models we expect the transaction history to obey."
    :default  [:strict-serializable]
    :parse-fn parse-keywords-spec
    :validate [(partial every? all-models) (str "Must be a collection of consistency models: " all-models ".")]]

   [nil "--key-dist DISTRIBUTION" "Probability distribution for keys being selected for a given operation."
    :default  :exponential
    :parse-fn keyword
    :validate [#{:exponential :uniform} "Must be one of exponential or uniform."]]

   [nil "--key-count NUMBER" "Number of distinct keys at any point."
    :default  10
    :parse-fn parse-long
    :validate [pos? "Must be a positive number."]]

   [nil "--keys-txn NUM" "The number of keys to act on in a transactions."
    :default  4
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]

   [nil "--linearizable-keys? BOOLEAN" "Assume that each key is independently linearizable."
    :default  true
    :parse-fn parse-boolean
    :validate [boolean? "Must be a Boolean."]]

   [nil "--max-transfer NUM" "(ledger) The largest transfer we'll try to execute."
    :default  10
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]

   [nil "--max-txn-length NUM" "Maximum number of operations per txn."
    :default  4
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]

   [nil "--min-txn-length NUM" "Minimum number of operations per txn."
    :default  2
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]

   [nil "--nemesis FAULTS" "A comma-separated list of nemesis faults to enable"
    :parse-fn parse-nemesis-spec
    :validate [(partial every? nemeses)
               (str "Faults must be " nemeses ", or the special faults all or none.")]]

   [nil "--nemesis-interval SECS" "Roughly how long between nemesis operations."
    :default 5
    :parse-fn parse-long
    :validate [pos? "Must be a positive number."]]

   [nil "--no-wipe" "If set, do not wipe files when tearing down nodes."
    :default false]

   ["-r" "--rate HZ" "Approximate request rate, in hz"
    :default 100
    :parse-fn parse-long
    :validate [pos? "Must be a positive number."]]

   [nil "--sequential-keys? BOOLEAN" "Assume that each key is independently sequentially consistent."
    :default  true
    :parse-fn parse-boolean
    :validate [boolean? "Must be a Boolean."]]

   [nil "--spacetimedb-node NODE" "Node to install SpacetimeDB on."
    :default  "spacetimedb"
    :parse-fn str
    :validate [string? "Must be a String."]]

   [nil "--total-amount NUM" "(ledger) Total amount to allocate."
    :default 100
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]

   [nil "--wfr-keys? BOOLEAN" "Assume that within each transaction, writes follow reads."
    :default  true
    :parse-fn parse-boolean
    :validate [boolean? "Must be a Boolean."]]

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
