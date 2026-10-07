(ns spacetimedb.role
  "Nodes can have one of two roles:
   - :spacetimedb (one and only one node is the SpacetimeDB)
   - :client"
  (:require [jepsen.role :as role]))

(def spacetimedb-role
  :spacetimedb)

(def client-role
  :client)

(defn roles-map
  "Given test opts, returns a roles map, {role->[nodes]}."
  [{:keys [nodes spacetimedb-node] :as _opts}]
  (let [nodes        (into #{} nodes)
        _            (assert (contains? nodes spacetimedb-node)
                             (str "SpacetimeDB node \"" spacetimedb-node "\" is required but missing from nodes " nodes))
        client-nodes (->> spacetimedb-node
                          (disj nodes)
                          (into []))]
    {spacetimedb-role [spacetimedb-node]
     client-role      client-nodes}))

(defn roles-based-db
  "Given a [[spacetimedb.db.spacetimedb]] and [[spacetimedb.db.client-node]],
   returns a composite DB that supports all of the roles."
  [stdb-node cndb-node]
  (role/db {spacetimedb-role {:db stdb-node :deps []}
            client-role      {:db cndb-node :deps [spacetimedb-role]}}))

(defn restricted-client
  "Returns a restricted client specific to the client-role."
  [client]
  (role/restrict-client client-role client))
