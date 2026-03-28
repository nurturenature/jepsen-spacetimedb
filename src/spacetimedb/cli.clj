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
  {:list-append workload/list-append
   :none        (fn [_] tests/noop-test)})

(def all-workloads
  "Default collection of workloads for test-all."
  [:list-append])

(def nemeses
  "A collection of valid nemeses."
  #{:clock       ; requires real VMs
    :kill-start
    :network
    :partition
    :pause})

(def all-nemeses
  "Combinations of nemeses for test-all"
  [[]
   [:kill-start]
   [:network]
   [:partition]
   [:pause]
   [:kill-start :partition]
   [:pause :partition]
   [:kill-start :network]
   [:pause :network]])

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

(defn test-name
  "Given opts, returns a meaningful test name."
  [{:keys [concurrency lazyfs? nemesis nodes rate spacetimedb-node time-limit workload] :as _opts}]
  (let [nodes (into #{} nodes)]
    (str (name workload)
         "-" (str/join "," (map name nemesis))
         (when lazyfs? "-lazyfs")
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
                  {:db         db
                   :nodes      (:nodes opts)
                   :faults     (:nemesis opts)
                   :clock      {:targets [nil]}
                   :kill-start {:targets [nil]}
                   :network    {:targets   [nil]
                                :behaviors [{:delay {}} {:corrupt {}}]}
                   :partition  {:targets [:majority]}
                   :pause      {:targets [nil]}})]
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
                         :logs-client        (if (:ignore-logs? opts)
                                               (checker/unbridled-optimism)
                                               (checker/log-file-pattern #"(ERROR)" client-node/log-file-short))
                         :logs-spacetimedb   (if (:ignore-logs? opts)
                                               (checker/unbridled-optimism)
                                               (checker/log-file-pattern #"(ERROR)" stdb/log-file-short))
                         :workload           (:checker workload)
                         :clock              (checker/clock-plot)})
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
  [[nil "--anomalies ANOMALIES" "A list of additional anomalies to check for."
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

   [nil "--directory DIRECTORY" "Directory to store Elle's analysis."
    :default  "elle"
    :parse-fn str
    :validate [string? "Must be a String."]]

   ;; TODO: bug in SpacetimeDB is logging spurious ERRORs in client logs
   ;;       server logs errors but they don't affect db correctness
   [nil "--ignore-logs? BOOLEAN" "Ignore logs when looking for errors?"
    :default  true
    :parse-fn parse-boolean
    :validate [boolean? "Must be a boolean."]]

   [nil "--key-count NUMBER" "Number of distinct keys at any point."
    :default  10
    :parse-fn parse-long
    :validate [pos? "Must be a positive number."]]

   [nil "--key-dist DISTRIBUTION" "Probability distribution for keys being selected for a given operation."
    :default  :exponential
    :parse-fn keyword
    :validate [#{:exponential :uniform} "Must be one of exponential or uniform."]]

   [nil "--keys-txn NUM" "The number of keys to act on in a transactions."
    :default  4
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]

   [nil "--lazyfs? BOOLEAN" "Mount data dir in a lazy filesystem that can lose non fsync'd writes?"
    :default  false
    :parse-fn parse-boolean
    :validate [boolean? "Must be a boolean."]]

   [nil "--max-txn-length NUM" "Maximum number of operations per txn."
    :default  4
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]

   [nil "--max-writes-per-key NUM" "Maximum number of writes to an individual key."
    :default  256
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

   [nil "--no-wipe? BOOLEAN" "Do not wipe files when tearing down nodes?"
    :default false
    :parse-fn parse-boolean
    :validate [boolean? "Must be a boolean."]]

   ["-r" "--rate HZ" "Approximate request rate, in hz"
    :default 100
    :parse-fn parse-long
    :validate [pos? "Must be a positive number."]]

   [nil "--spacetimedb-node NODE" "Node to install SpacetimeDB on."
    :default  "spacetimedb"
    :parse-fn str
    :validate [string? "Must be a String."]]

   [nil "--spacetimedb-version VERSION" "Version of SpacetimeDB to install."
    :default  "2.1.0"
    :parse-fn str
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
