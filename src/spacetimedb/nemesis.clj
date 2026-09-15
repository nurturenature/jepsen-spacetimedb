(ns spacetimedb.nemesis
  (:require [jepsen
             [control :as c]
             [db :as db]
             [generator :as gen]
             [lazyfs :as lazyfs]
             [nemesis :as nemesis]
             [net :as net]
             [random :as rand]]
            [jepsen.nemesis.combined :as nc]
            [spacetimedb.lazyfs :as stdb-lazyfs]
            [spacetimedb.db.spacetimedb :as stdb]))

(defn kill-node!
  "Killing a node is
   - no close, shutdown, etc
   - just kill it"
  [{:keys [db] :as test} node]
  (db/kill! db test node)
  :killed)

(defn start-node!
  [{:keys [db] :as test} node]
  ; will return :started or :already-running
  (db/start! db test node))

(defn kill-start-nemesis
  "A nemesis that kills and starts nodes.
   This nemesis responds to:
  ```
  {:f :kill-nodes  :value :node-spec}   ; target nodes as interpreted by `db-nodes`
  {:f :start-nodes :value nil}
   ```"
  [db]
  (reify
    nemesis/Reflection
    (fs [_this]
      #{:kill-nodes :start-nodes})

    nemesis/Nemesis
    (setup! [this _test]
      this)

    (invoke! [_this {:keys [nodes] :as test} {:keys [f value] :as op}]
      (let [result (case f
                     :kill-nodes  (let [; target nodes per db-spec
                                        targets (->> value
                                                     (nc/db-nodes test db)
                                                     (into #{}))]
                                    (c/on-nodes test targets kill-node!))
                     :start-nodes (let [; target all nodes
                                        targets (->> nodes
                                                     (into #{}))]
                                    (c/on-nodes test targets start-node!)))
            result (->> result
                        (into (sorted-map)))]
        (assoc op :value result)))

    (teardown! [_this _test]
      nil)))

(defn kill-start-package
  "A nemesis and generator package that kills and starts nodes.
   
   Opts:
   ```clj
   {:kill-start {:targets [...]}}  ; A collection of node specs, e.g. [:one, :all]
  ```"
  [{:keys [db faults interval kill-start] :as _opts}]
  (when (contains? faults :kill-start)
    (let [targets     (:targets kill-start (nc/node-specs db))
          kill-nodes  (fn kill-nodes [_ _]
                        {:type  :info
                         :f     :kill-nodes
                         :value (rand/nth targets)})
          start-nodes (repeat {:type  :info
                               :f     :start-nodes
                               :value nil})
          gen         (->> (gen/flip-flop
                            kill-nodes
                            start-nodes)
                           (gen/stagger (or interval nc/default-interval)))]
      {:generator       gen
       :final-generator (take 1 start-nodes)
       :nemesis         (kill-start-nemesis db)
       :perf            #{{:name  "kill-start"
                           :fs    #{}
                           :start #{:kill-nodes}
                           :stop  #{:start-nodes}
                           :color "#E8DBA0"}}})))

(defn network-nemesis
  "A nemesis that disrupts the network between server and client nodes.
   This nemesis responds to:
  ```
  {:f :disrupt-network :value :node-spec}   ; target nodes as interpreted by `db-nodes`
  {:f :heal-network    :value nil}
   ```"
  [db]
  (reify
    nemesis/Reflection
    (fs [_this]
      #{:disrupt-network :heal-network})

    nemesis/Nemesis
    (setup! [this {:keys [net] :as test}]
      ; start from known good state, no shaping
      (net/shape! net test nil nil)
      this)

    (invoke! [_this {:keys [net spacetimedb-node] :as test} {:keys [f value] :as op}]
      (let [result (case f
                     :disrupt-network (let [[targets behaviors] value
                                            ; target nodes per db-spec
                                            ; always include SpacetimeDB node
                                            targets (->> targets
                                                         (nc/db-nodes test db)
                                                         (into #{spacetimedb-node}))]
                                        (net/shape! net test targets behaviors))
                     :heal-network    (net/shape! net test nil nil))]
        (assoc op :value result)))

    (teardown! [_this {:keys [net] :as test}]
      ; leave in known good state, no shaping
      (net/shape! net test nil nil))))

(defn network-package
  "A nemesis and generator package that disrupts the network between the server and client nodes.
   
   Opts:
   ```clj
   {:network
    {:targets      ; A collection of node specs, e.g. [:one, :all]
     :behaviors [  ; A collection of network behaviors that disrupt packets, e.g.:
      {}                         ; no disruptions
      {:delay {}}                ; delay packets by default amount
      {:corrupt {:percent :33%}} ; corrupt 33% of packets
      ; delay packets by default values, plus duplicate 25% of packets
      {:delay {},
       :duplicate {:percent :25% :correlation :80%}}]}}
  ```
  See [[jepsen.net/all-packet-behaviors]].

  Additional options as for [[nemesis-package]]."
  [{:keys [db faults interval network] :as _opts}]
  (when (contains? faults :network)
    (let [targets         (:targets network (nc/node-specs db))
          behaviors       (:behaviors network [{}])
          disrupt-network (fn disrupt-network [_ _]
                            {:type  :info
                             :f     :disrupt-network
                             :value [(rand/nth targets) (rand/nth behaviors)]})
          heal-network    (repeat {:type  :info
                                   :f     :heal-network
                                   :value nil})
          gen             (->> (gen/flip-flop disrupt-network heal-network)
                               (gen/stagger (or interval nc/default-interval)))]
      {:generator       gen
       :final-generator (take 1 heal-network)
       :nemesis         (network-nemesis db)
       :perf            #{{:name  "network"
                           :fs    #{}
                           :start #{:disrupt-network}
                           :stop  #{:heal-network}
                           :color "#D1E8A0"}}})))

(def power-glitch-commands
  #{:checkpoint :noop :power-glitch :start-spacetimedb})

(defrecord PowerGlitchNemesis [db lazyfs-map]
  nemesis/Reflection
  (fs [_this]
    power-glitch-commands)

  nemesis/Nemesis
  (setup!
    [this _test]
    this)

  (invoke!
    [_this test {:keys [f] :as op}]
    (let [result (case f
                   :checkpoint
                   (c/with-node test stdb/spacetimedb-host-name
                     (stdb-lazyfs/unsynced-data-report! lazyfs-map)
                     (lazyfs/checkpoint! lazyfs-map))

                   :noop
                   :noop

                   :power-glitch
                   (c/with-node test stdb/spacetimedb-host-name
                     (db/kill! db test stdb/spacetimedb-host-name)
                     (stdb-lazyfs/unsynced-data-report! lazyfs-map)
                     (lazyfs/lose-unfsynced-writes! lazyfs-map)
                     (db/start! db test stdb/spacetimedb-host-name)
                     :power-restored)

                   :start-spacetimedb
                   (c/with-node test stdb/spacetimedb-host-name
                     (db/start! db test stdb/spacetimedb-host-name)))]
      (assoc op :value result)))

  (teardown!
    [_this _test]))

(defn power-glitch-package
  "A nemesis and generator package to simulate a power outage.
   
   ```bash
   --nemesis power-glitch
   ```
   "
  [{:keys [db faults interval] :as _opts}]
  (when (contains? faults :power-glitch)
    (let [gen        (->> (gen/phases
                           ; let db do work, i.e. writes
                           {:type  :info
                            :f     :noop
                            :value nil}

                           ; persist writes so far
                           {:type  :info
                            :f     :checkpoint
                            :value nil}

                           (gen/cycle
                            [; let db do work, i.e. writes
                             {:type  :info
                              :f     :noop
                              :value nil}

                             ; power glitch
                             {:type  :info
                              :f     :power-glitch
                              :value nil}]))
                          (gen/stagger (or interval nc/default-interval)))
          final-gen  {:type  :info
                      :f     :start-spacetimedb
                      :value nil}
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
          _          (assert lazyfs-map)
          nemesis    (PowerGlitchNemesis. db lazyfs-map)]
      {:generator       gen
       :final-generator final-gen
       :nemesis         nemesis
       :perf            #{{:name  "power-glitch"
                           :fs    power-glitch-commands
                           :start #{}
                           :stop  #{}
                           :color "#FFCCCC"}}})))

(defn nemesis-package
  "Constructs combined nemeses and generators into a nemesis package."
  [opts]
  (let [opts (update opts :faults set)]
    (->> [(kill-start-package opts)
          (network-package opts)
          (stdb-lazyfs/lazyfs-package opts)
          (power-glitch-package opts)]
         (concat (nc/nemesis-packages opts))
         (filter :generator)
         nc/compose-packages)))
