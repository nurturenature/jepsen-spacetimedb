(ns spacetimedb.workload
  (:require [jepsen.client :refer [timeout]]
            [jepsen.tests.cycle.append :as list-append]
            [spacetimedb.role :as role]
            [spacetimedb.db
             [spacetimedb :as stdb]
             [client-node :as client-node]]))

(def final-generator
  "Final generator that just reads sequential keys."
  (->> (range)
       (map (fn [k]
              {:type :invoke
               :f    :txn
               :value [[:r k nil]]}))))

(def default-workload
  "Default workload for test."
  :list-append)

(defn list-append
  "A SpacetimeDB workload where all reads/writes to a keyed append only list
   happen in a transaction in a Procedure.
   
   [[spacetimedb.client/dispatch-by-f]] will map op to uri for this workload."
  [{:keys [key-count lazyfs? min-txn-length max-txn-length max-writes-per-key spacetimedb-version universal-timeout] :as opts}]
  (assert (and key-count min-txn-length max-txn-length max-writes-per-key)
          (str "opts must specify {key-count min-txn-length max-txn-length max-writes-per-key}: " opts))
  (let [watchdog-timeout (+ 1000 universal-timeout)
        stdb     (if lazyfs?
                   (stdb/lazyfs-stdb spacetimedb-version)
                   (stdb/stdb        spacetimedb-version))
        stdb     (stdb/watched-stdb stdb watchdog-timeout)
        cndb     (client-node/client-node)
        cndb     (client-node/watched-client-node cndb watchdog-timeout)
        roles-db (role/roles-based-db stdb cndb)]
    (merge
     (list-append/test opts)
     {:final-generator final-generator}
     {:db              roles-db
      :client          (timeout universal-timeout (role/restricted-client))
      :roles           (role/roles-map opts)})))

(defn list-append-all-functions
  "A [[list-append]] workload that uses:
   - reducer for all append txns
   - procedure for mixed append/read txns
   - local client cache for all read txns
   
   [[spacetimedb.client/dispatch-by-f]] will map op to uri for this workload."
  [opts]
  (list-append opts))
