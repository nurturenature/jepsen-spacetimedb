(ns spacetimedb.db.spacetimedb
  "A local SpacetimeDB database."
  (:require [clojure.tools.logging :refer [info]]
            [jepsen
             [control :as c]
             [db :as db]
             [lazyfs :as lazyfs]
             [util :as u]]
            [jepsen.control.util :as cu]
            [jepsen.db.watchdog :as watchdog]
            [jepsen.os.debian :as debian]
            [slingshot.slingshot :refer [throw+]]
            [spacetimedb.util :refer [killall]]))

(def spacetimedb-host-name
  "spacetimedb")

(def spacetimedb-db-name
  "test-db")

(def jepsen-dir
  "Working directory for Jepsen."
  "/jepsen/jepsen-spacetimedb")

(def client-dir
  "TypeScript client directory."
  (str jepsen-dir "/jepsen-spacetimedb/stdb-client"))

(def websocket-config-toml
  "Custom SpacetimeDB config.toml that favors disconnecting client websockets."
  (str client-dir "/websocket-config.toml"))

(def pid-file (str jepsen-dir "/spacetimedb.pid"))

(def log-file-short "spacetimedb.log")
(def log-file       (str jepsen-dir "/" log-file-short))


(def log-lazyfs-short "lazyfs.log")
(def log-lazyfs       (str jepsen-dir "/" log-lazyfs-short))

(def spacetimedb-ps-name "spacetimedb-standalone")

(def pg-port 5432)

(def spacetimedb-binary
  "SpacetimeDB binary executable."
  "/root/.local/bin/spacetime")

(def spacetimedb-data-dir
  "SpacetimeDB data directory."
  "/root/.local/share/spacetime/data")
(def lazyfs-mount-dir
  "When using LazyFS, the mount directory."
  "/root/.local/share/spacetime/lazyfs.mount")
(def lazyfs-data-dir
  "When using LazyFS, the data directory."
  (str lazyfs-mount-dir "/data"))

(def spacetimedb-files
  "A map of most of the SpacetimeDB file locations."
  {:config-dir      "/root/.config"
   :local-dir       "/root/.local"
   :local-bin-dir   "/root/.local/bin"
   :local-share-dir "/root/.local/share/spacetime/bin"
   :npm             "/root/.npm"})

(defn install-nodejs
  []
  (debian/update!)
  (debian/install [:extrepo])
  ; FORNOW, GitHub Actions regularly fail when connecting to the Debian repo
  (u/await-fn (fn []
                (c/su
                 (c/exec :extrepo :enable :node_25.x)))
              {:retry-interval 500
               :log-interval   1000
               :log-message    "retrying: extrepo enable node_25.x"
               :timeout        3000})
  (debian/update!)
  (debian/install [:nodejs]))

(defn install-packages
  []
  (debian/update!)
  (debian/install [:curl :git])
  (install-nodejs))

(defn install-repository
  "Installs or updates GitHub repository in [[jepsen-dir]].
   `force-reinstall-repository?` deletes repo forcing a full re-installation."
  [force-reinstall-repository?]
  (when force-reinstall-repository?
    (c/exec :rm :-rf jepsen-dir))

  (c/exec :mkdir :--parents jepsen-dir)

  (c/cd jepsen-dir
        (if (cu/exists? "jepsen-spacetimedb/.git")
          (do
            (info "repository jepsen-spacetimedb already exists, pulling")
            (c/cd "jepsen-spacetimedb"
                  (c/exec :git :pull)))
          (do
            (info "repository jepsen-spacetimedb does not exist, cloning")
            (c/exec :git :clone :-b :main :--depth :1 :--single-branch "https://github.com/nurturenature/jepsen-spacetimedb.git")))))

(defn install-config-toml
  "Installs given custom SpacetimeDB `config-toml`.
   Assumes on SpacetimeDB node."
  [config-toml]
  (let [target-path (str spacetimedb-data-dir "/config.toml")]
    (info "installing custom SpacetimeDB config.toml:" config-toml)
    (c/su
     (c/exec :mkdir :--parents spacetimedb-data-dir)
     (c/exec :cp config-toml target-path))))

(defn install-spacetimedb
  [{:keys [spacetimedb-version force-reinstall-spacetimedb? lazyfs? websocket-config-toml?] :as _test}]
  (when force-reinstall-spacetimedb?
    (doseq [file-or-dir (vals spacetimedb-files)]
      (u/meh ; LazyFS may be mounted, Ok to ignore errors
       (c/exec :rm :-rf file-or-dir))))

  (c/exec :mkdir :--parents jepsen-dir)

  (if (not (cu/exists? spacetimedb-binary))
    (do
      (info "downloading and installing SpacetimeDB" spacetimedb-version)
      (c/cd jepsen-dir
             ; download and install binary
            (c/exec :curl :-sSf :--output :install-spacetimedb.sh "https://install.spacetimedb.com")
            (c/exec :chmod :a+x :install-spacetimedb.sh)
            (c/exec "./install-spacetimedb.sh" :--yes)

             ; configuring should also create config ~/.config/spacetime/cli.toml
            (c/exec spacetimedb-binary :server :set-default :local)))
    (do
      (info "SpacetimeDB already installed, clearing database and using version" spacetimedb-version)
      (c/exec spacetimedb-binary :server :clear :--data-dir (if lazyfs?
                                                              lazyfs-data-dir
                                                              spacetimedb-data-dir) :--yes)
      (c/exec spacetimedb-binary :version :use spacetimedb-version)))

  (when websocket-config-toml?
    (install-config-toml websocket-config-toml)))

(defn configure-test-db
  "Configure SpacetimeDB for a test-db.
   Expects SpacetimeDB to be started."
  []
  ; build and publish our SpacetimeDB modules
  (c/cd client-dir
        ; as SpacetimeDB client is TypeScript, will need npm modules
        (c/exec :npm :install)

        ; shouldn't have to generate bindings, they are in repository,
        ; but generation from source keeps us honest
        (c/exec spacetimedb-binary :generate spacetimedb-db-name :--lang :typescript :--out-dir "src/module_bindings")

        (c/exec spacetimedb-binary :build)

        (c/exec spacetimedb-binary :publish :--yes :--server :local spacetimedb-db-name)))

(defn spacetimedb-alive?
  "Tests the SpacetimeDB server for liveness by executing
   `spacetime server ping local`.
   Will retry for `universal-timeout`."
  [{:keys [spacetimedb-node universal-timeout] :as test}]
  (c/with-node test spacetimedb-node
    (try
      (u/await-fn (fn ping-spacetimedb []
                    (c/exec spacetimedb-binary :server :ping :local))
                  {:retry-interval 100
                   :log-interval   1000
                   :log-message    "waiting for SpacetimeDB ping to succeed"
                   :timeout        universal-timeout})
      true
      (catch Exception _
        false))))

; local SpacetimeDB database
(defrecord STDB []
  db/DB
  (setup!
    [this {:keys [force-reinstall-repository?] :as test} node]
    (info "setting up SpacetimeDB on" node)

    (install-packages)
    (install-repository force-reinstall-repository?)
    (install-spacetimedb test)

    (db/start! this test node)

    (configure-test-db)

    (when (not (spacetimedb-alive? test))
      (throw+ {:type :error :error "Unable to start SpacetimeDB!"}))

    (info "SpacetimeDB setup on" node))

  (teardown!
    [this test node]
    (info "tearing down SpacetimeDB on" node)
    (db/kill! this test node)

    ; NOTE: leaving SpacetimeDB installed

    (c/su
     (c/exec :rm :-rf log-file pid-file)))

  ;; SpacetimeDB is the primary.
  db/Primary
  (primaries
    [_db _test]
    [spacetimedb-host-name])

  (setup-primary!
    [_db _test _node])

  db/LogFiles
  (log-files
    [_db _test _node]
    {log-file log-file-short})

  db/Kill
  (start!
    [_this {:keys [lazyfs?] :as _test} _node]
    (if (cu/daemon-running? pid-file)
      :already-running
      (do
        (c/su
         (cu/start-daemon!
          {:chdir   jepsen-dir
           :logfile log-file
           :pidfile pid-file}
          spacetimedb-binary
          :start
          :--data-dir (if lazyfs?
                        lazyfs-data-dir
                        spacetimedb-data-dir)
          :--pg-port pg-port
          :--non-interactive))
        :started)))

  (kill!
    [_this {:keys [kill-with] :as _test} _node]
    (killall kill-with spacetimedb-ps-name true)) ; wait for process to die


  db/Pause
  (pause!
    [_this _test _node]
    (killall :STOP spacetimedb-ps-name))

  (resume!
    [_this _test _node]
    (killall :CONT spacetimedb-ps-name)))

(defn stdb
  "Installs and uses the latest version of SpacetimeDB."
  []
  (STDB.))

(defrecord LazyFSSTDB [lazyfs-db stdb-db]
  db/DB
  (setup!
    [this test node]
    (db/setup! lazyfs-db test node)
    (db/setup! stdb-db   test node)
    this)

  (teardown!
    [this test node]
    (db/teardown! stdb-db   test node)
    (db/teardown! lazyfs-db test node)
    this)

  db/Primary
  (primaries
    [_db test]
    (db/primaries stdb-db test))

  (setup-primary!
    [_db test node]
    (db/setup-primary! stdb-db test node))

  db/LogFiles
  (log-files
    [_db test node]
    (merge
     (db/log-files stdb-db   test node)
     (db/log-files lazyfs-db test node)))

  db/Kill
  (start!
    [_this test node]
    (db/start! stdb-db test node))

  (kill!
    [_this test node]
    (db/kill! stdb-db test node))

  db/Pause
  (pause!
    [_this test node]
    (db/pause! stdb-db test node))

  (resume!
    [_this test node]
    (db/resume! stdb-db test node)))

(defn lazyfs-stdb
  "Installs and uses the latest version of SpacetimeDB.
   Data directory is mounted on a LazyFS."
  []
  (let [lazyfs-db (->> {:dir lazyfs-mount-dir :log-file log-lazyfs}
                       lazyfs/lazyfs
                       lazyfs/db)
        stdb-db   (stdb)]
    (LazyFSSTDB. lazyfs-db stdb-db)))

(defn watched-stdb
  "Wraps given stdb with a [[jepsen.db.watchdog]] that monitors and restarts every interval."
  [stdb interval]
  (let [opts {:running? (fn running? [test _node]
                          (spacetimedb-alive? test))
              :interval interval}]
    (watchdog/db opts stdb)))