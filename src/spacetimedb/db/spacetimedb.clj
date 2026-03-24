(ns spacetimedb.db.spacetimedb
  "A local SpacetimeDB database."
  (:require [clojure.tools.logging :refer [info]]
            [jepsen
             [db :as db]
             [control :as c]
             [util :as u]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def spacetimedb-db-name
  "test-db")

(def jepsen-dir
  "Working directory for Jepsen."
  "/jepsen/jepsen-spacetimedb")

(def client-dir
  "TypeScript client directory."
  (str jepsen-dir "/jepsen-spacetimedb/stdb-client"))

(def pid-file (str jepsen-dir "/spacetimedb.pid"))

(def log-file-short "spacetimedb.log")
(def log-file       (str jepsen-dir "/" log-file-short))

(def spacetimedb-ps-name "spacetimedb")

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

(defn install-nodejs
  []
  (debian/update!)
  (debian/install [:extrepo])
  (c/su
   (c/exec :extrepo :enable :node_25.x))
  (debian/update!)
  (debian/install [:nodejs]))

(defn install-packages
  []
  (debian/update!)
  (debian/install [:curl :git])
  (install-nodejs))

(defn install-repository
  "Installs or updates GitHub repository in current directory."
  []
  (if (cu/exists? "jepsen-spacetimedb/.git")
    (c/cd "jepsen-spacetimedb"
          (c/exec :git :pull))
    (c/exec :git :clone :-b :main :--depth :1 :--single-branch "https://github.com/nurturenature/jepsen-spacetimedb.git")))

(defn install-spacetimedb
  [version]
  (c/su
   (c/exec :mkdir :--parents jepsen-dir)

   (if (cu/exists? (:binary spacetimedb-files))
     (info "using existing SpacetimeDB binary")
     (c/cd jepsen-dir
           ; download and install binary
           (c/exec :curl :-sSf :--output :install-spacetimedb.sh "https://install.spacetimedb.com")
           (c/exec :chmod :a+x :install-spacetimedb.sh)
           (c/exec "./install-spacetimedb.sh" :--yes)

           ; explicit version
           (c/exec (:binary spacetimedb-files) :version :install version)
           (c/exec (:binary spacetimedb-files) :version :use version)

           ; configuring should also create config ~/.config/spacetime/cli.toml
           (c/exec (:binary spacetimedb-files) :server :set-default :local)))))

(defn configure-test-db
  "Configure SpacetimeDB for a test-db.
   Expects SpacetimeDB to be started."
  []
  (c/cd jepsen-dir
        (install-repository))

  ; build and publish our SpacetimeDB modules
  (c/cd client-dir
        ; as SpacetimeDB client is TypeScript, will need npm modules
        (c/exec :npm :install)

        ; shouldn't have to generate bindings, they are in repository,
        ; but generation from source keeps us honest
        (c/exec (:binary spacetimedb-files) :generate spacetimedb-db-name :--lang :typescript :--out-dir "src/module_bindings")

        (c/exec (:binary spacetimedb-files) :build)

        (c/exec (:binary spacetimedb-files) :publish :--yes :--server :local spacetimedb-db-name)))

(def spacetimedb-started? (atom false))

(defn stdb
  "Local SpacetimeDB database."
  [version]
  (reify db/DB
    (setup!
      [this test node]
      (info "Setting up SpacetimeDB " node)

      (install-packages)
      (install-spacetimedb version)

      (db/start! this test node)
      (u/sleep 1000) ; TODO: sleep for 1s to allow endpoint to come up, should be retry http connection

      (configure-test-db)

      (swap! spacetimedb-started? (constantly true)))

    (teardown!
      [this test node]
      (info "Tearing down SpacetimeDB " node)
      (db/kill! this test node)

      (if (:no-wipe test)
        (info "--no-wipe is set, setup files are preserved and not deleted")
        (c/su
         (wipe)))

      (swap! spacetimedb-started? (constantly false)))

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
      ; TODO: understand why sporadic Exception with exit code of 137 when using Docker,
      ;       for now, retrying is effective and safe 
      (c/su
       (u/retry 1 (cu/grepkill! spacetimedb-ps-name))
       :killed))

    db/Pause
    (pause!
      [_this _test _node]
      ; TODO: understand why sporadic Exception with exit code of 137 when using Docker,
      ;       for now, retrying is effective and safe 
      (c/su
       (u/retry 1 (cu/grepkill! :stop spacetimedb-ps-name)))
      :paused)

    (resume!
      [_this _test _node]
      ; TODO: understand why sporadic Exception with exit code of 137 when using Docker,
      ;       for now, retrying is effective and safe 
      (c/su
       (u/retry 1 (cu/grepkill! :cont spacetimedb-ps-name)))
      :resumed)))
