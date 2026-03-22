(ns spacetimedb.workload
  (:require [jepsen.tests.bank :as bank]
            [jepsen.tests.cycle.wr :as wr]
            [spacetimedb.role :as role]))

(defn ledger-procedure
  "A SpacetimeDB workload where all reads/writes to an i32/i32 account/balance ledger
   happen in a transaction in a Procedure."
  [{:keys [accounts max-transfer negative-balances? total total-amount] :as opts}]
  (assert (and accounts max-transfer (not negative-balances?) total total-amount)
          (str "opts must have :accounts :max-transfer :negative-balances? :total :total-amount, opts: " opts))
  (merge
   (bank/test opts)
   ; bank/test inappropriately overrides these
   {:accounts           accounts
    :max-transfer       max-transfer
    :negative-balances? negative-balances?
    :total              total
    :total-amount       total-amount}
   {:db     (role/roles-based-db opts)
    :client (role/restricted-client)
    :roles  (role/roles-map opts)
    :spacetimedb {:table "ledger" :technique "procedure"}}))

(defn wr-register-procedure
  "A SpacetimeDB workload where all reads/writes to an Integer/Integer key/value register
   happen in a transaction in a Procedure."
  [opts]
  (merge
   (wr/test opts)
   {:db     (role/roles-based-db opts)
    :client (role/restricted-client)
    :roles  (role/roles-map opts)
    :spacetimedb {:table "registers" :technique "procedure"}}))

