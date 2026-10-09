(ns spacetimedb.client
  "A SpacetimeDB client is a URI to a SpacetimeDB server.
   `db/invoke!` is a HTTP call to the server that returns the results in the `op`."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [jepsen.client :as client]
            [slingshot.slingshot :refer [try+]]
            [spacetimedb.db.client-node :as client-node]))

(defn op->json-txn
  "Given an op, encodes its `:value`, a transaction, as a json string."
  [{:keys [type f value] :as op}]
  (assert (and (= type :invoke) (#{:txn} f) value)
          (str "Invalid :type and/or :f and/or :value in op: " op))
  (->> value
       (mapv (fn [[f k v]]
               {:f f :k k :v v}))
       json/generate-string))

(defn json-result->op
  "Given a json string, decodes it into an op.
   Only care about the type, value, and error keys."
  [json-string]
  (let [op (-> json-string
               (json/decode true)
               (select-keys [:type :value :error])
               (update :type  keyword)
               (update :value (fn [value]
                                (->> value
                                     (mapv (fn [[f k v :as _mop]]
                                             [(keyword f) k v]))))))]
    op))

(defn invoke
  "Invokes the op against the endpoint and returns the result."
  [op endpoint timeout]
  (let [body   (-> op
                   (select-keys [:type :f :value]) ; don't expose rest of op map 
                   op->json-txn)]
    (try+
     (let [result (http/post endpoint
                             {:body               body
                              :content-type       "application/json"
                              :socket-timeout     timeout
                              :connection-timeout timeout
                              :accept             "application/json"})
           op'    (-> result
                      :body
                      json-result->op)]
       (merge op op'))

     (catch java.net.ConnectException ex
       (if (= (.getMessage ex) "Connection refused (connect failed)")
         (assoc op
                :type  :fail
                :error (.toString ex))
         (assoc op
                :type  :info
                :error (.toString ex))))
     (catch java.net.SocketException ex
       (if (= (.getMessage ex) "Connection reset")
         (assoc op
                :type  :info
                :error (.toString ex))
         (assoc op
                :type  :info
                :error (.toString ex))))
     (catch java.net.SocketTimeoutException ex
       (assoc op
              :type  :info
              :error (.toString ex)))
     (catch org.apache.http.ConnectionClosedException ex
       (assoc op
              :type  :info
              :error (.toString ex)))
     (catch org.apache.http.NoHttpResponseException ex
       (assoc op
              :type  :info
              :error (.toString ex)))
     (catch [:status 500] {}
       (assoc op
              :type  :info
              :error {:status 500})))))

(defn op->fs
  "Given an op, returns the set of functions,
   `:append` and/or `:`r`, that are in the op's transaction."
  [{:keys [f value] :as _op}]
  (assert (= f :txn))
  (->> value
       (map (fn [[f _k _v]]
              f))
       (into #{})))
(defrecord SpacetimeDBClient []
  client/Client
  (open!
    [this _test node]
    (assoc this
           :node          node
           :uri           (client-node/client-uri node)))

  (setup!
    [_this _test])

  (invoke!
    [{:keys [node uri] :as _this} {:keys [fs->stdb universal-timeout] :as _test} op]
    (let [op       (assoc op :node node)
          fs       (op->fs op)
          stdb-fn  (get fs->stdb fs)]
      (if-let [endpoint (case stdb-fn
                          :procedure   "lists/txn/procedure"
                          :reducer     "lists/appends/reducer"
                          :local-cache "lists/reads/cache"
                          :fail        nil)]
        (let [uri (str uri "/" endpoint)]
          (invoke op uri universal-timeout))
        (assoc op
               :type  :fail
               :error (str "No SpacetimeDB function mapped for a txn of fs: " fs)))))

  (teardown!
    [_this _test])

  (close!
    [this _test]
    (dissoc this
            :node
            :uri)))

(defn stdb-client
  "Returns a SpacetimeDB client.
   Client is wrapped in a [[jepsen.client/timeout]] to ensure that all client calls are bounded by the universal-timeout."
  [universal-timeout]
  (client/timeout universal-timeout (SpacetimeDBClient.)))

;; (defrecord SpacetimeDBClientNOOP [conn]
;;   client/Client
;;   (open!
;;     [this {:keys [nodes] :as _test} node]
;;     (info "SpacetimeDBClientNOOP/open!(" this " {:nodes " nodes "} " node ")")
;;     (assoc this
;;            :node node
;;            :uri  nil))
;; 
;;   (setup!
;;     [this {:keys [nodes] :as _test}]
;;     (info "SpacetimeDBClientNOOP/setup!(" this " {:nodes " nodes "})"))
;; 
;;   (invoke!
;;     [{:keys [node] :as _this} _test op]
;;     (let [op  (assoc op :node node)]
;;       (info "client ignoring: " op)
;;       (assoc op :type :ok)))
;; 
;;   (teardown!
;;     [_this _test])
;; 
;;   (close!
;;     [this _test]
;;     (dissoc this
;;             :node
;;             :uri)))
