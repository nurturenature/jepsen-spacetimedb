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
  (assert (and (= type :invoke) (#{:txn :transfer :read} f))
          (str "Invalid :type and/or :f and/or :value in op: " op))
  (json/generate-string value))

(defn json-result->op
  "Given an f and a json string, decodes it into an op.
   Only care about the type, value, and error keys."
  [f json-string]
  (let [op (-> json-string
               (json/decode true)
               (select-keys [:type :value :error])
               (update :type  keyword)
               (update :value (fn [value]
                                (case f
                                  :txn      (->> value
                                                 (mapv (fn [[f k v]]
                                                         [(keyword f) k v])))
                                  :transfer value
                                  :read     (->> value
                                                 (map (fn [entry] ; {"account" integer "balance" integer}
                                                        (let [account (get entry "account")
                                                              balance (get entry "balance")]
                                                          [account balance])))
                                                 (into {}))))))]
    op))

(defn invoke
  "Invokes the op against the endpoint and returns the result."
  [op endpoint timeout preprocess postprocess]
  (let [body   (-> op
                   (select-keys [:type :f :value]) ; don't expose rest of op map 
                   preprocess)]
    (try+
     (let [result (http/post endpoint
                             {:body               body
                              :content-type       "application/json"
                              :socket-timeout     timeout
                              :connection-timeout timeout
                              :accept             "application/json"})
           op'    (-> result
                      :body
                      postprocess)]
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

(def invoke-dispatch
  "Maps [table f] to {:preprocess fn :postprocess fn}"
  {["registers" :txn]      {:preprocess  op->json-txn
                            :postprocess (partial json-result->op :txn)}
   ["ledger"    :transfer] {:preprocess  op->json-txn
                            :postprocess (partial json-result->op :transfer)}
   ["ledger"    :read]     {:preprocess  op->json-txn
                            :postprocess (partial json-result->op :read)}})

(def spacetimedb-setup?
  "First client initializes db."
  (atom false))

; (.SpacetimeDBClient conn)
; conn = {:table     SpacetimeDB table name, e.g. registers
;         :technique SpacetimeDB technique for writes/reads, e.g. procedure}
(defrecord SpacetimeDBClient [conn]
  client/Client
  (open!
    [this {:keys [client-timeout spacetimedb] :as _test} node]
    (assert (every? spacetimedb #{:table :technique})
            "SpacetimeDBClients expect a test containing {:spacetimedb {:table \"\" :technique \"\"}}")
    (assoc this
           :node      node
           :uri       (client-node/client-uri node)
           :timeout   (* client-timeout 1000)
           :table     (:table     spacetimedb)
           :technique (:technique spacetimedb)))

  (setup!
    [{:keys [timeout uri] :as _this} {:keys [accounts total-amount] :as _test}]
    ; first client sets up db
    (locking spacetimedb-setup?
      (when-not @spacetimedb-setup?
        (http/post (str uri "/ledger/setup")
                   {:body               (json/encode {:accounts accounts
                                                      :balance  (quot total-amount (count accounts))})
                    :content-type       "application/json"
                    :socket-timeout     timeout
                    :connection-timeout timeout
                    :accept             "application/json"})
        (swap! spacetimedb-setup? (constantly true)))))

  (invoke!
    [{:keys [node table technique timeout uri] :as _this} _test {:keys [f] :as op}]
    (let [op  (assoc op :node node)
          uri (str uri "/" table "/" (name f) "/" technique)
          {:keys [preprocess postprocess]} (get invoke-dispatch [table f])]
      (invoke op uri timeout preprocess postprocess)))

  (teardown!
    [_this _test])

  (close!
    [this _test]
    (dissoc this
            :node
            :uri
            :timeout)))

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
