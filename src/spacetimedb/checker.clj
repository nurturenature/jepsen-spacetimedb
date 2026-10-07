(ns spacetimedb.checker
  (:require [clojure.set :as set]
            [jepsen
             [checker :as checker]
             [history :as h]
             [role :as role]]
            [spacetimedb.role :as stdb-role]))

(defn final-txn-ok
  "Did the last transaction on each node succeed?
   
   Expect all nodes to be up and successfully doing transactions."
  []
  (reify checker/Checker
    (check [_this test history _opts]
      (let [client-nodes      (->> stdb-role/client-role
                                   (role/nodes test)
                                   (into #{}))
            history           (->> history
                                   h/client-ops
                                   (h/remove #(= :invoke (:type %))))
            node->final-type  (->> history
                                   (reduce (fn [acc {:keys [node type] :as _op}]
                                             (update acc node type))
                                           (sorted-map)))
            final-nodes       (->> node->final-type keys (into #{}))
            missing-nodes     (->> (set/difference client-nodes final-nodes) (into (sorted-set)))
            final-type->nodes (->> node->final-type
                                   (group-by val))
            final-types       (->> final-type->nodes
                                   keys
                                   (into #{}))]
        ; result map
        (merge {:valid?     true
                :final-txns node->final-type}
               (when (seq missing-nodes)
                 {:valid? false
                  :error  (str "Missing final transactions for nodes: " missing-nodes)})
               (when-not (= final-types #{:ok})
                 {:valid? false
                  :error  "All final transactions are not :ok for each node"}))))))
