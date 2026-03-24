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
  "Given an f and a json string, decodes it into an op.
   Only care about the type, value, and error keys."
  [f json-string]
  (let [op (-> json-string
               (json/decode true)
               (select-keys [:type :value :error])
               (update :type  keyword)
               (update :value (fn [value]
                                (case f
                                  :txn (->> value
                                            (mapv (fn [{:keys [f k v] :as _mop}]
                                                    [(keyword f) k v])))))))]
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
  {["lists" :txn] {:preprocess  op->json-txn
                   :postprocess (partial json-result->op :txn)}})

; (.SpacetimeDBClient conn)
; conn = {:spacetimedb {:table         \"...\"
;                       :dispatch-by-f {f \"technique\"}}}
(defrecord SpacetimeDBClient [conn]
  client/Client
  (open!
    [this {:keys [client-timeout spacetimedb] :as _test} node]
    (assert (every? spacetimedb #{:table :dispatch-by-f})
            "SpacetimeDBClients expect a test containing
               {:spacetimedb {:table \"...\" :dispatch-by-f {f \"technique\"}}}")
    (assoc this
           :node          node
           :uri           (client-node/client-uri node)
           :timeout       (* client-timeout 1000)
           :table         (:table         spacetimedb)
           :dispatch-by-f (:dispatch-by-f spacetimedb)))

  (setup!
    [_this _test])

  (invoke!
    [{:keys [dispatch-by-f node table timeout uri] :as _this} _test {:keys [f] :as op}]
    (let [op  (assoc op :node node)
          uri (str uri "/" table "/" (name f) "/" (->> f
                                                       (get dispatch-by-f)
                                                       rand-nth))
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
