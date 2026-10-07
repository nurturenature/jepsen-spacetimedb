(ns spacetimedb.workload
  (:require [jepsen
             [tests :as tests]]
            [jepsen.tests.cycle.append :as list-append]
            [spacetimedb
             [client :as stdb-client]
             [role :as stdb-role]]
            [spacetimedb.db
             [spacetimedb :as stdb-node]
             [client-node :as client-node]]))

(def default-workload
  "Default workload for test."
  :list-append)

(defn list-append
  "A SpacetimeDB workload where all reads/writes to a keyed append only list
   happen in a transaction in a Procedure.
   
   [[spacetimedb.client/dispatch-by-f]] will map op to uri for this workload."
  [{:keys [key-count lazyfs? min-txn-length max-txn-length max-writes-per-key universal-timeout] :as opts}]
  (assert (and key-count min-txn-length max-txn-length max-writes-per-key)
          (str "opts must specify {key-count min-txn-length max-txn-length max-writes-per-key}: " opts))
  (let [watchdog-timeout (+ 1000 universal-timeout)
        stdb (if lazyfs?
               (stdb-node/lazyfs-stdb)
               (stdb-node/stdb))
        stdb (stdb-node/watched-stdb stdb watchdog-timeout)
        cndb (client-node/client-node)
        cndb (client-node/watched-client-node cndb watchdog-timeout)
        roles        (stdb-role/roles-map opts)
        roles-db     (stdb-role/roles-based-db stdb cndb)
        roles-client (stdb-role/restricted-client (stdb-client/stdb-client universal-timeout))]
    (merge
     (list-append/test opts)
     {:roles  roles
      :db     roles-db
      :client roles-client})))

(defn list-append-all-functions
  "A [[list-append]] workload that uses:
   - reducer for all append txns
   - procedure for mixed append/read txns
   - local client cache for all read txns
   
   [[spacetimedb.client/dispatch-by-f]] will map op to uri for this workload."
  [opts]
  (list-append opts))

(def workloads
  "A map of workload names to functions that take CLI options and return
  workload maps."
  {:list-append               list-append
   :list-append-all-functions list-append-all-functions
   :none                      (fn [_] tests/noop-test)})