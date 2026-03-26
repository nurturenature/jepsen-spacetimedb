(ns spacetimedb.nemesis
  (:require [jepsen
             [control :as c]
             [db :as db]
             [generator :as gen]
             [nemesis :as nemesis]]
            [jepsen.nemesis.combined :as nc]))

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
                         :value (rand-nth targets)})
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

(defn nemesis-package
  "Constructs combined nemeses and generators into a nemesis package."
  [opts]
  (let [opts (update opts :faults set)]
    (->> [(kill-start-package opts)]
         (concat (nc/nemesis-packages opts))
         (filter :generator)
         nc/compose-packages)))
