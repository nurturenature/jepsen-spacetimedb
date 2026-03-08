(ns spacetimedb.db
  "A local SpacetimeDB database."
  (:require [clojure.tools.logging :refer [info]]
            [jepsen
             [db :as db]
             [control :as c]
             [util :as u]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def jepsen-dir
  "Working directory for Jepsen."
  "/jepsen/jepsen-spacetimedb")

(def pid-file (str jepsen-dir "/spacetimedb.pid"))

(def log-file-short "spacetimedb.log")
(def log-file       (str jepsen-dir "/" log-file-short))

(def spacetimedb-ps-name "spacetimedb")

(def spacetimedb-host "spacetimedb")
(def spacetimedb-port 3000)
(def spacetimedb-uri  (str "http://" spacetimedb-host ":" spacetimedb-port))

(def pg-port 5432)

(def spacetimedb-files
  "A map of all the SpacetimeDB file locations"
  {:local-dir  "/root/.local"
   :config-dir "/root/.config"
   :binary     "/root/.local/bin/spacetime"})

(defn wipe
  "Wipes all local files.
   Assumes on node and privs for file deletion."
  []
  (c/exec :rm :-rf (vals spacetimedb-files))
  (c/exec :rm :-rf jepsen-dir))

(defn install-packages
  []
  (debian/update!)
  (debian/install [:curl :extrepo])
  (c/su
   (c/exec :extrepo :enable :node_25.x))
  (debian/update!)
  (debian/install [:nodejs]))

(defn install-spacetimedb
  []
  (c/su
   (c/exec :mkdir :--parents jepsen-dir)
   (c/cd jepsen-dir
         (c/exec :curl :-sSf :--output :install-spacetimedb.sh "https://install.spacetimedb.com")
         (c/exec :chmod :a+x :install-spacetimedb.sh)
         (c/exec "./install-spacetimedb.sh" :--yes))))

(defn db
  "Local SpacetimeDB database."
  []
  (reify db/DB
    (setup!
      [this test node]
      (info "Setting up SpacetimeDB " node)

      (install-packages)
      (install-spacetimedb)

      (db/start! this test node)
      (u/sleep 1000)) ; TODO: sleep for 1s to allow endpoint to come up, should be retry http connection

    (teardown!
      [this test node]
      (info "Tearing down SpacetimeDB " node)
      (db/kill! this test node)
      (c/su
       (wipe)))

    ; SpacetimeDB doesn't have `primaries`.
    ; db/Primary

    db/LogFiles
    (log-files
      [_db _test _node]
      {log-file log-file-short})

    db/Kill
    (start!
      [_this _test _node]
      (if (cu/daemon-running? pid-file)
        :already-running
        (do
          (c/su
           (cu/start-daemon!
            {:chdir   jepsen-dir
             :logfile log-file
             :pidfile pid-file}
            (:binary spacetimedb-files)
            :start
            :--pg-port pg-port
            :--non-interactive))
          :started)))

    (kill!
      [_this _test _node]
      (c/su
       (cu/grepkill! spacetimedb-ps-name))
      :killed)

    db/Pause
    (pause!
      [_this _test _node]
      (c/su
       (cu/grepkill! :stop spacetimedb-ps-name))
      :paused)

    (resume!
      [_this _test _node]
      (cu/grepkill! :cont spacetimedb-ps-name)
      :resumed)))

(defn noop-db
  "A no-op database."
  []
  (reify db/DB
    (setup!
      [_this _test node]
      (info "Setting up noop-db " node))

    (teardown!
      [_this _test node]
      (info "Tearing down noop-db " node))

    ; noop-db doesn't have `primaries`.
    ; db/Primary

    ; noop-db doesn't have logs.
    db/LogFiles
    (log-files
      [_db _test _node]
      nil)

    db/Kill
    (start!
      [_this _test node]
      (info "Starting noop-db " node)
      :started)

    (kill!
      [_this _test node]
      (info "killing noop-db " node)
      :killed)

    db/Pause
    (pause!
      [_this _test node]
      (info "Pausing noop-db " node)
      :paused)

    (resume!
      [_this _test node]
      (info "Starting noop-db " node)
      :resumed)))
