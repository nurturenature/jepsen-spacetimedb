(ns spacetimedb.db
  "A local SpacetimeDB database."
  (:require [clojure.tools.logging :refer [info]]
            [jepsen
             [db :as db]
             [control :as c]
             [util :as u]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def spacetimedb-files
  "A map of all the SpacetimeDB file locations"
  {:config-dir "/root/.config/spacetime/"
   :binary "/root/.local/bin/spacetime"
   :versions-dir "/root/.local/share/spacetime/bin"
   :database-directory "/root/.local/share/spacetime/data"})

(def app-dir
  "Directory for application files."
  "/jepsen/jepsen-spacetimedb")

(def pid-file (str app-dir "/spacetimedb.pid"))

(def log-file-short "spacetimedb.log")
(def log-file       (str app-dir "/" log-file-short))

(def app-ps-name "spacetimedb")

(defn wipe
  "Wipes all local files.
   Assumes on node and privs for file deletion."
  []
  (c/exec :rm :-rf (vals spacetimedb-files))
  (c/exec :rm :-rf app-dir))

(defn install-curl
  []
  (debian/update!)
  (debian/install [:curl]))

(defn install-spacetimedb
  []
  (c/su
   (c/exec :mkdir :--parents app-dir)
   (c/exec :curl :-sSf "https://install.spacetimedb.com" :| :sh :--yes)))

(defn db
  "Local SpacetimeDB database."
  []
  (reify db/DB
    (setup!
      [this test node]
      (info "Setting up SpacetimeDB " node)

      (install-curl)
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
            {:chdir   app-dir
             :logfile log-file
             :pidfile pid-file}
            (:binary spacetimedb-files)))
          :started)))

    (kill!
      [_this _test _node]
      ; TODO: understand why sporadic Exception with exit code of 137 when using Docker,
      ;       for now, retrying is effective and safe 
      (c/su
       (u/retry 1 (cu/grepkill! app-ps-name)))
      :killed)

    db/Pause
    (pause!
      [_this _test _node]
      ; TODO: timeout is an attempt to workaround GitHub Action timeout
      (u/timeout 1000 :grepkill-timeout
                 (c/su
                  (cu/grepkill! :stop app-ps-name))
                 :paused))

    (resume!
      [_this _test _node]
      ; TODO: timeout is an attempt to workaround GitHub Action timeout
      (u/timeout 1000 :grepkill-timeout
                 (c/su
                  (cu/grepkill! :cont app-ps-name))
                 :resumed))))
