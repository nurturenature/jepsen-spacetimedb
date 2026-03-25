(ns spacetimedb.workload
  (:require [jepsen.client :refer [timeout]]
            [jepsen.tests.cycle.append :as list-append]
            [spacetimedb.role :as role]))

(defn list-append
  "A SpacetimeDB workload where all reads/writes to a keyed append only list
   happen in a transaction in a Procedure."
  [{:keys [client-timeout key-count min-txn-length max-txn-length max-writes-per-key] :as opts}]
  (assert (and key-count min-txn-length max-txn-length max-writes-per-key)
          (str "opts must specify {key-count min-txn-length max-txn-length max-writes-per-key}: " opts))
  (merge
   (list-append/test opts)
   {:db     (role/roles-based-db opts)
    :client (timeout (* 1000 client-timeout) (role/restricted-client))
    :roles  (role/roles-map opts)
    :spacetimedb {:table "lists" :dispatch-by-f {:txn ["procedure"]}}}))


