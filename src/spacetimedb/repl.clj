(ns spacetimedb.repl
  (:require [causal.checker
             [adya :as adya]
             [opts :as causal-opts]
             [strong-convergence :as strong-convergence]]
            [causal.checker.mww
             [causal-consistency :refer [causal-consistency]]
             [stats :refer [completions-by-node]]
             [strong-convergence :refer [strong-convergence]]
             [util :as util]]
            [cheshire.core :as json]
            [jepsen
             [checker :as checker]
             [history :as h]
             [store :as store]]
            [spacetimedb
             [cli :as cli]
             [client :as client]
             [db :as db]
             [nemesis :as nemesis]
             [workload :as workload]]))

(def spacetimedb_endpoint
  "http://localhost:8989/sql-txn")

