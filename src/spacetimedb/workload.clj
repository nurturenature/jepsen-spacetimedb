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
  "A SpacetimeDB workload where all appends/reads to a keyed append only list
   happen in a transaction in a `procedure`."
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
      :client roles-client
      :fs->stdb {#{:append}    :procedure
                 #{:r}         :procedure
                 #{:append :r} :procedure}})))

(defn list-append-all-functions
  "A [[list-append]] workload that uses:
   - reducer for all append txns
   - procedure for mixed append/read txns
   - local client cache for all read txns"
  [opts]
  (merge
   (list-append opts)
   {:fs->stdb {#{:append}     :reducer
               #{:r}          :local-cache
               #{:append :r}  :procedure}}))

(defn- seq-mops
  "Takes a `wr` `generator` with single mop txns and returns a lazy seq of mops."
  [generator]
  (->> generator
       (map :value)
       (map first)))

(defn- batch-generator
  "Given a `wr` `generator`, returns a seq of ops that batch the `:append`s and `:r`eads.
   i.e. each txn is exclusively `:append`s or `:r`eads."
  [generator]
  (->> {:mops (seq-mops generator)}
       (iterate (fn batcher [{:keys [mops] :as _state}]
                  (loop [mops mops
                         on-f nil
                         txn  nil]
                    (let [[f _k _v :as mop] (first mops)
                          mops'             (rest  mops)]
                      (cond
                        ; first mop in txn
                        (nil? on-f)
                        (recur mops'  ; consume new mop
                               f      ; txn of f's
                               [mop]) ; start txn

                        ; new mop has same f as as current txn
                        (= f on-f)
                        (recur mops'           ; consume new mop
                               on-f            ; same f
                               (conj txn mop)) ; add new mop to txn

                        ; new f should start a new txn
                        (not= f on-f)
                        {:mops mops      ; do not consume new mop
                         :on-f nil       ; start a new txn
                         :txn  txn}))))) ; txn of all :append or :r

       (map :txn)
       (drop 1) ; 1st is nil, just initial `state` to `iterate`
       (map (fn [txn]
              {:type  :invoke
               :f     :txn
               :value txn}))))

(defn reducer-localcache-only
  "A [[list-append]] workload that uses:
   - reducer for all append txns
   - fails mixed append/read txns
   - local client cache for all read txns"
  [opts]
  (let [opts (assoc opts
                    :min-txn-length 1  ; need single mop :value [[f k v]] ops
                    :max-txn-length 1) ; to wrap in a `batcher-generator`
        {:keys [generator] :as list-append} (list-append opts)
        generator (batch-generator generator)]
    (merge
     list-append
     {:generator generator}
     {:fs->stdb  {#{:append}    :reducer
                  #{:r}         :local-cache
                  #{:append :r} :fail}})))

(def workloads
  "A map of workload names to functions that take CLI options and return
  workload maps."
  {:list-append               list-append
   :list-append-all-functions list-append-all-functions
   :reducer-localcache-only   reducer-localcache-only
   :none                      (fn [_] tests/noop-test)})