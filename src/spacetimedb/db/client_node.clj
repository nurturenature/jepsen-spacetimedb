(ns spacetimedb.db.client-node
  "A SpacetimeDB client node."
  (:require [clj-http.client :as http]
            [clojure.tools.logging :refer [info]]
            [jepsen
             [db :as db]
             [control :as c]
             [util :as u]]
            [jepsen.control.util :as cu]
            [jepsen.db.watchdog :as watchdog]
            [jepsen.os.debian :as debian]
            [slingshot.slingshot :refer [throw+]]
            [spacetimedb.db.spacetimedb :as stdb]))

(def pid-file (str stdb/jepsen-dir "/client-node.pid"))

(def log-file-short "client-node.log")
(def log-file       (str stdb/jepsen-dir "/" log-file-short))

(def client-node-ps-name "node")

(defn client-uri
  "Give a node, return a client URI for that node."
  [node]
  (str "http://" node ":3000"))

(defn client-env
  "Given a test, constructs an env map to invoke client with."
  [{:keys [confirmed-reads? spacetimedb-node universal-timeout] :as _test}]
  {:SPACETIMEDB_HOST    (str "ws://" spacetimedb-node ":3000")
   :SPACETIMEDB_DB_NAME stdb/spacetimedb-db-name
   :CONFIRMED_READS     confirmed-reads?
   :CLIENT_PORT         3000
   :CLIENT_TIMEOUT      universal-timeout})

(defn install-packages
  []
  (debian/update!)
  (debian/install [:git])
  (stdb/install-nodejs))

(defn install-client
  "install-client? will force a full installation."
  [install-client?]
  (when install-client?
    (c/exec :rm :-rf stdb/jepsen-dir))

  (c/exec :mkdir :--parents stdb/jepsen-dir)

  (c/cd stdb/jepsen-dir
        (stdb/install-repository))

  ; as SpacetimeDB client is TypeScript, will need npm modules
  (c/cd stdb/client-dir
        (c/exec :npm :install)))

(defn ping-client!
  "Tries to ping the client for the given node."
  [{:keys [universal-timeout] :as _test} node]
  (let [uri (str (client-uri node) "/ping")]
    (http/get uri
              {:body               nil
               :content-type       "application/text"
               :socket-timeout     universal-timeout
               :connection-timeout universal-timeout
               :accept             "application/json"})
    true))

(defrecord CLIENT_NODE []
  db/DB
  (setup!
    [this {:keys [install-client? universal-timeout] :as test} node]
    (info "setting up client-node" node)
    (install-packages)

    (install-client install-client?)

    ; SpacetimeDB must be started to continue
    (u/await-fn (fn waiting-spacetimedb []
                  (if @stdb/spacetimedb-setup?
                    true
                    (throw+ {:error "SpacetimeDB not ready"})))
                {:retry-interval 1000
                 :log-interval   universal-timeout
                 :log-message    "waiting for @stdb/spacetimedb-setup?"
                 ; TODO: SpacetimeDB seems to start slowly on Docker w/lazyfs?
                 :timeout        (-> universal-timeout (* 10))})

    (u/await-fn (fn start-client-node []
                  (db/start! this test node)
                  (u/await-fn (fn ping-client-node []
                                (ping-client! test node))
                              {:retry-interval 1000
                               :log-interval   universal-timeout
                               :log-message    "Waiting for client node ping"
                               :timeout        (* 3 universal-timeout)}))
                {:retry-interval (+ 1000 universal-timeout)
                 :log-interval   (+ 1000 universal-timeout)
                 :log-message    "Waiting for client node startup"
                 :timeout        (-> universal-timeout
                                     (+ 1000)
                                     (* 3))}))

  (teardown!
    [this test node]
    (info "tearing down client-node" node)
    (db/kill! this test node)

    ;; NOTE: client files are not deleted

    (c/su
     (c/exec :rm :-rf (str stdb/client-dir "/.spacetimedb-token"))
     (c/exec :rm :-rf log-file)))

  ; client-node doesn't have `primaries`.
  db/Primary
  (primaries
    [_db _test]
    nil)

  (setup-primary!
    [_db _test _node])

  db/LogFiles
  (log-files
    [_db _test _node]
    {log-file log-file-short})

  db/Kill
  (start!
    [_this test node]
    (info "starting client-node" node)
    (if (cu/daemon-running? pid-file)
      :already-running
      (do
        (c/su
         (cu/start-daemon!
          {:chdir   stdb/client-dir
           :logfile log-file
           :pidfile pid-file
           :env     (client-env test)}
          "/usr/bin/npm"
          :run :dev))
        :started)))

  (kill!
    [_this _test _node]
    ; TODO: understand why sporadic Exception with exit code of 137 when using Docker,
    ;       for now, retry
    (u/timeout 10000
               :timed-out
               (do
                 (c/su
                  (u/retry 1 (cu/grepkill! client-node-ps-name)))
                 :killed)))

  db/Pause
  (pause!
    [_this _test _node]
    ; TODO: understand why sporadic Exception with exit code of 137 when using Docker,
    ;       for now, retry
    (u/timeout 10000
               :timed-out
               (do
                 (c/su
                  (u/retry 1 (cu/grepkill! :stop client-node-ps-name)))
                 :paused)))

  (resume!
    [_this _test _node]
    ; TODO: understand why sporadic Exception with exit code of 137 when using Docker,
    ;       for now, retry 
    (u/timeout 10000
               :timed-out
               (do
                 (c/su
                  (u/retry 1 (cu/grepkill! :cont client-node-ps-name)))
                 :resumed))))

(defn client-node
  "A client-node DB."
  []
  (CLIENT_NODE.))

(defn watched-client-node
  "Given a client-node DB and an interval,
   returns client-node with a watchdog to monitor and restart every interval."
  [client-node interval]
  (let [opts {:running? (fn running? [_test _node]
                          (cu/daemon-running? pid-file))
              :interval interval}]
    (watchdog/db opts client-node)))