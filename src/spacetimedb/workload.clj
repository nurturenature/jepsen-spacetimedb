(ns spacetimedb.workload
  (:require [jepsen.tests.bank :as bank]
            [jepsen.tests.cycle.wr :as wr]
            [spacetimedb.role :as role]))

(defn wr-register-procedure
  "A SpacetimeDB workload where all reads/writes to an Integer/Integer key/value register
   happen in a transaction in a Procedure."
  [opts]
  (merge
   (wr/test opts)
   {:db     (role/roles-based-db opts)
    :client (role/restricted-client)
    :roles  (role/roles-map opts)}))

(defn ledger-procedure
  "A SpacetimeDB workload where all reads/writes to an i32/i32 account/balance ledger
   happen in a transaction in a Procedure."
  [opts]
  (merge
   (bank/test opts)
   {:db     (role/roles-based-db opts)
    :client (role/restricted-client)
    :roles  (role/roles-map opts)}))
