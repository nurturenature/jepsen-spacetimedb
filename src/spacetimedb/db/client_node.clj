(ns spacetimedb.db.client-node
  "A SpacetimeDB client node."
  (:require [clojure.tools.logging :refer [info]]
            [jepsen
             [db :as db]
             [control :as c]
             [util :as u]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [spacetimedb.db.spacetimedb :as stdb]))

(def pid-file (str stdb/jepsen-dir "/client-node.pid"))

(def log-file-short "client-node.log")
(def log-file       (str stdb/jepsen-dir "/" log-file-short))

(def client-node-ps-name "node")

(defn wipe
  "Wipes all local files.
   Assumes on node and privs for file deletion."
  []
  (c/exec :rm :-rf stdb/jepsen-dir))

(defn install-packages
  []
  (debian/update!)
  (debian/install [:git])
  (stdb/install-nodejs))

(defn install-client
  []
  (c/exec :mkdir :--parents stdb/jepsen-dir)

  (c/cd stdb/jepsen-dir
        (stdb/install-repository))

  ; as SpacetimeDB client is TypeScript, will need npm modules
  (c/cd stdb/client-dir
        (c/exec :npm :install)))

(defn client-node
  "Manage a client node."
  []
  (reify db/DB
    (setup!
      [this test node]
      (info "Setting up client-node " node)
      (install-packages)

      (install-client)

      ; SpacetimeDB must be started to continue
      (while (not @stdb/spacetimedb-started?)
        (u/sleep 1000))

      (db/start! this test node)
      (u/sleep 1000) ; TODO: sleep for 1s to allow endpoint to come up, should be retry http connection
      )

    (teardown!
      [this test node]
      (info "Tearing down client-node " node)
      (db/kill! this test node)

      (if (:no-wipe test)
        (info "--no-wipe is set, setup files are preserved and not deleted")
        (c/su
         (wipe))))

    ; client-node doesn't have `primaries`.
    ; db/Primary

    db/LogFiles
    (log-files
      [_db _test _node]
      {log-file log-file-short})

    db/Kill
    (start!
      [_this _test node]
      (info "Starting client-node " node)
      (if (cu/daemon-running? pid-file)
        :already-running
        (do
          (c/su
           (cu/start-daemon!
            {:chdir   stdb/client-dir
             :logfile log-file
             :pidfile pid-file
             :env     {:SPACETIMEDB_DB_NAME "wr-register"}}
            "/usr/bin/npm"
            :run :dev))
          :started)))

    (kill!
      [_this _test _node]
      (c/su
       (cu/grepkill! client-node-ps-name))
      :killed)

    db/Pause
    (pause!
      [_this _test _node]
      (c/su
       (cu/grepkill! :stop client-node-ps-name))
      :paused)

    (resume!
      [_this _test _node]
      (cu/grepkill! :cont client-node-ps-name)
      :resumed)))
