(ns spacetimedb.client
  "A SpacetimeDB client is a URI to a SpacetimeDB server.
   `db/invoke!` is a HTTP call to the server that returns the results in the `op`."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.tools.logging :refer [info]]
            [jepsen.client :as client]
            [slingshot.slingshot :refer [try+]]
            [spacetimedb.db.client-node :as client-node]))

(defn op->json
  "Given an op, encodes it as a json string."
  [op]
  (-> op
      (update :value (fn [value]
                       (->> value
                            (mapv (fn [[f k v :as _mop]]
                                    {:f f :k k :v v})))))
      json/generate-string))

(defn json->op
  "Given a json string, decodes it into an op."
  [json-string]
  (-> json-string
      (json/decode true)
      (select-keys [:type :f :value :error])
      (update :type  keyword)
      (update :f     keyword)))

(defn invoke
  "Invokes the op against the endpoint and returns the result."
  [op endpoint timeout]
  (let [body   (-> op
                   (select-keys [:type :f :value]) ; don't expose rest of op map 
                   op->json)]
    (try+
     (let [result (http/post endpoint
                             {:body               body
                              :content-type       :json
                              :socket-timeout     timeout
                              :connection-timeout timeout
                              :accept             :json})
           op'    (->> result
                       :body
                       json->op)]
       (merge op op'))

     (catch java.net.ConnectException ex
       (if (= (.getMessage ex) "Connection refused")
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

(defrecord SpacetimeDBClient [conn]
  client/Client
  (open!
    [this {:keys [client-timeout] :as _test} node]
    (assoc this
           :node    node
           :uri     (client-node/client-uri node)
           :timeout (* client-timeout 1000)))

  (setup!
    [_this _test])

  (invoke!
    [{:keys [node url timeout] :as _this} _test op]
    (let [op (assoc op :node node)]
      (invoke op url timeout)))

  (teardown!
    [_this _test])

  (close!
    [this _test]
    (dissoc this
            :node
            :uri
            :timeout)))

(defrecord SpacetimeDBClientNOOP [conn]
  client/Client
  (open!
    [this {:keys [nodes] :as _test} node]
    (info "SpacetimeDBClientNOOP/open!(" this " {:nodes " nodes "} " node ")")
    (assoc this
           :node node
           :uri  nil))

  (setup!
    [this {:keys [nodes] :as _test}]
    (info "SpacetimeDBClientNOOP/setup!(" this " {:nodes " nodes "})"))

  (invoke!
    [{:keys [node] :as _this} _test op]
    (let [op  (assoc op :node node)]
      (info "client ignoring: " op)
      (assoc op :type :ok)))

  (teardown!
    [_this _test])

  (close!
    [this _test]
    (dissoc this
            :node
            :uri)))
