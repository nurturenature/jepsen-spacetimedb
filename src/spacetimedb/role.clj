(ns spacetimedb.role
  "Nodes can have one of two roles:
   - :spacetimedb (one and only one node is the SpacetimeDB)
   - :client"
  (:require [jepsen.role :as role]
            [spacetimedb.client :as client]
            [spacetimedb.db :as db]))

(def spacetimedb-role
  :spacetimedb)

(def client-role
  :client)

(defn roles-map
  "Given test opts, returns a roles map, {role->[nodes]}."
  [{:keys [nodes spacetimedb-node] :as _opts}]
  (let [nodes (into #{} nodes)]
    (assert (contains? nodes spacetimedb-node)
            (str "SpacetimeDB node \"" spacetimedb-node "\" is required but missing from nodes " nodes))
    {spacetimedb-role [spacetimedb-node]
     client-role      (->> spacetimedb-node
                           (disj nodes)
                           (into []))}))

(defn roles-based-db
  "Given test opts, returns a composite DB that supports all of the roles.
   client-role nodes are no-ops for database setup/teardown/etc."
  [{:keys [] :as _opts}]
  (role/db {spacetimedb-role (db/db)
            client-role      (db/noop-db)}))

(defn restricted-client
  "Returns a restricted client specific to the client-role."
  []
  (role/restrict-client client-role (client/->SpacetimeDBClientNOOP nil)))
