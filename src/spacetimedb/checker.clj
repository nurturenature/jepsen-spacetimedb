(ns spacetimedb.checker
  (:require [jepsen
             [checker :as checker]
             [client :as client]
             [history :as h]
             [role :as role]]
            [spacetimedb.role :as stdb-role]))

(defn final-txn-ok
  "Did the last transaction on each node succeed?
   
   Expect all nodes to be up and successfully doing transactions."
  []
  (reify checker/Checker
    (check [_this test history _opts]
      (let [nodes      (->> stdb-role/client-role
                            (role/nodes test)
                            (into #{}))
            history-r  (->> history
                            h/client-ops
                            (h/filter (fn [{:keys [type] :as _op}]
                                        (not= :invoke type)))
                            reverse)
            [finals
             remaining] (->> history-r
                             (reduce
                              (fn [[finals remaining :as acc] {:keys [error node] :as op}]
                                (cond
                                  ; timeout error
                                  (and (not node) (= client/timeout error))
                                  acc

                                  ; node remains to be done?
                                  (contains? remaining node)
                                  (let [finals    (assoc finals node op)
                                        remaining (disj remaining node)]
                                    (if (empty? remaining)
                                      (reduced [finals remaining])
                                      [finals remaining]))

                                  ; node already was done, no-op
                                  :else
                                  acc))
                              [{} nodes]))

            finals-not-ok (->> finals
                               (filter (fn [[_node {:keys [type] :as _op}]]
                                         (not= :ok type)))
                               (into (sorted-map)))
            remaining     (->> remaining
                               (into (sorted-set)))]
        ; result map
        (cond-> {:valid? true}
          ; all transactions should be ok
          (seq finals-not-ok)
          (assoc :valid? false
                 :non-ok-final-txns finals-not-ok)

          ; all nodes should have a txn
          (seq remaining)
          (assoc :valid? false
                 :nodes-missing-final-txns remaining))))))
