(ns spacetimedb.workload
  (:require [jepsen.tests.cycle.wr :as wr]
            [spacetimedb.role :as role]))

(defn procedures
  "A SpacetimeDB workload where all reads/writes to an Integer/Integer key/value register
   happen in a transaction in a Procedure."
  [opts]
  (merge
   (wr/test opts)
   {:db     (role/roles-based-db opts)
    :client (role/restricted-client)
    :roles  (role/roles-map opts)}))


