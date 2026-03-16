(ns spacetimedb.repl
  (:require [cheshire.core :as json]
            [jepsen
             [checker :as checker]
             [history :as h]
             [store :as store]]
            [spacetimedb
             [cli :as cli]
             [client :as client]
             [nemesis :as nemesis]
             [workload :as workload]]
            [spacetimedb.db
             [client-node :as client-node]
             [spacetimedb :as stdb]]))

(def spacetimedb_endpoint
  "http://localhost:8989/sql-txn")

