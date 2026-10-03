(ns spacetimedb.lazyfs
  "Nemeses that test SpacetimeDB's syncing of data using LazyFS."
  (:require [jepsen
             [control :as c]
             [generator :as gen]
             [lazyfs :as lazyfs]
             [nemesis :as nemesis]]
            [jepsen.random :as random]
            [jepsen.nemesis.combined :as nc]))

(def lazyfs-commands
  #{:lose-unfsynced-writes :unsynced-data-report})

(defn unsynced-data-report!
  "Generate a report of the unsynced data for the given `lazyfs-map` in the lazyfs log file."
  [lazyfs-map]
  (lazyfs/fifo! lazyfs-map "lazyfs::unsynced-data-report"))

(defrecord LazyFSNemesis [lazyfs-map]
  nemesis/Reflection
  (fs [_this]
    lazyfs-commands)

  nemesis/Nemesis
  (setup!
    [this _test]
    this)

  (invoke!
    [_this test {:keys [f value] :as op}]
    (let [result (case f
                   :lose-unfsynced-writes
                   (c/with-nodes test value
                     (lazyfs/lose-unfsynced-writes! lazyfs-map))

                   :unsynced-data-report
                   (c/with-nodes test value
                     (unsynced-data-report! lazyfs-map)))
          result (->> result
                      (into (sorted-map)))]
      (assoc op :value result)))

  (teardown!
    [_this _test]
    nil))

(defn lazyfs-package
  "A nemesis and generator package for injecting storage faults using LazyFS.
   
   Opts:
   ```clj
   {:lazyfs
    {:targets   sequence-nodes     ; nodes to target
     :behaviors lazyfs-behaviors}} ; collection of behaviors
   ```"
  [{:keys [db faults interval lazyfs] :as _opts}]
  (when (contains? faults :lazyfs)
    (let [targets    (:targets   lazyfs)
          behaviors  (:behaviors lazyfs)
          _          (assert (seq targets)   "Must specify at least one target for lazyfs.")
          _          (assert (seq behaviors) "Must specify at least one behavior for lazyfs.")
          behaviors  (->> behaviors (into [])) ; random/nth doesn't work on sets
          gen        (->> (repeatedly
                           (fn []
                             (let [behavior (random/nth behaviors)]
                               {:type  :info
                                :f     behavior
                                :value targets})))
                          (gen/stagger (or interval nc/default-interval)))
          final-gen  (gen/phases
                      (gen/log (str "final " behaviors " for " targets))
                      (->> behaviors
                           (map (fn [behavior]
                                  {:type  :info
                                   :f     behavior
                                   :value targets}))))
          lazyfs-map (loop [db db]
                       (cond
                         ; lazyfs DB
                         (contains? db :lazyfs-map)
                         (:lazyfs-map db)

                         ; wrapped DB
                         (contains? db :db)
                         (recur (:db db))

                         ; role'd DB
                         (contains? db :dbs)
                         (recur (->> db
                                     :dbs
                                     :spacetimedb))

                         :else
                         (throw (Exception. (str "Unknown type of db: " db)))))
          _          (assert lazyfs-map)]
      {:generator       gen
       :final-generator final-gen
       :nemesis         (LazyFSNemesis. lazyfs-map)
       :perf            #{{:name  "lazyfs"
                           :fs    lazyfs-commands
                           :start #{}
                           :stop  #{}
                           :color "#FFCCCC"}}})))
