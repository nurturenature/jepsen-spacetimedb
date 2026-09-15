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

(def dispatch-by-f
  "A map of workload names to a (fn [op] uri)."
  {:list-append               (constantly "lists/txn/procedure")
   :list-append-all-functions (fn dispatch-by-f [{:keys [f value] :as _op}]
                                (assert (= f :txn))
                                (let [f's (->> value
                                               (map (fn [[f _k _v]]
                                                      f))
                                               (into #{}))]
                                  (case f's
                                    #{:append}    "lists/appends/reducer"
                                    #{:append :r} "lists/txn/procedure"
                                    #{:r}         "lists/reads/cache")))})

(defrecord SpacetimeDBClient []
  client/Client
  (open!
    [this {:keys [workload] :as _test} node]
    (assoc this
           :node          node
           :uri           (client-node/client-uri node)
           :dispatch-by-f (get dispatch-by-f workload)))

  (setup!
    [_this _test])

  (invoke!
    [{:keys [dispatch-by-f node uri] :as _this} {:keys [universal-timeout] :as _test} {:keys [f] :as op}]
    (let [op  (assoc op :node node)
          uri (str uri "/" (dispatch-by-f op))]
      (invoke op uri universal-timeout)))

  (teardown!
    [_this _test])

  (close!
    [this _test]
    (dissoc this
            :dispatch-by-f
            :node
            :uri)))

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
