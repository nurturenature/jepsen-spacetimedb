(ns spacetimedb.workload
  (:require [jepsen.tests.cycle.append :as list-append]
            [spacetimedb.role :as role]))

(defn list-append
  "A SpacetimeDB workload where all reads/writes to a keyed append only list
   happen in a transaction in a Procedure."
  [{:keys [key-count min-txn-length max-txn-length max-writes-per-key] :as opts}]
  (assert (and key-count min-txn-length max-txn-length max-writes-per-key)
          (str "opts must specify {key-count min-txn-length max-txn-length max-writes-per-key}: " opts))
  (merge
   (list-append/test opts)
   {:db     (role/roles-based-db opts)
    :client (role/restricted-client)
    :roles  (role/roles-map opts)
    :spacetimedb {:table "list-append" :dispatch-by-f {:txn ["procedure"]}}}))


