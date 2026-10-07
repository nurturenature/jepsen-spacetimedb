(ns spacetimedb.db.spacetimedb
  "A local SpacetimeDB database."
  (:require [clojure.tools.logging :refer [info]]
            [jepsen
             [db :as db]
             [control :as c]
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
  "Installs or updates GitHub repository in current directory.
   `force-reinstall-repository?` deletes repo forcing a full re-installation."
  [force-reinstall-repository?]
  (when force-reinstall-repository?
    (c/exec :rm :-rf "jepsen-spacetimedb"))

  (if (cu/exists? "jepsen-spacetimedb/.git")
    (do
      (info "repository jepsen-spacetimedb already exists, pulling")
      (c/cd "jepsen-spacetimedb"
            (c/exec :git :pull)))
    (do
      (info "repository jepsen-spacetimedb does not exist, cloning")
      (c/exec :git :clone :-b :main :--depth :1 :--single-branch "https://github.com/nurturenature/jepsen-spacetimedb.git"))))

(defn install-spacetimedb
  [spacetimedb-version force-reinstall-spacetimedb? lazyfs?]
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
      (c/exec spacetimedb-binary :version :use spacetimedb-version))))

(defn configure-test-db
  "Configure SpacetimeDB for a test-db.
   Expects SpacetimeDB to be started."
  [force-reinstall-repository?]
  (c/cd jepsen-dir
        (install-repository force-reinstall-repository?))

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
   `spacetime server ping local`."
  [{:keys [spacetimedb-node universal-timeout] :as test}]
  (c/with-node test spacetimedb-node
    (try
      (u/await-fn (fn ping-spacetimedb []
                    (c/exec spacetimedb-binary :server :ping :local))
                  {:retry-interval 500
                   :log-interval   500
                   :log-message    "waiting for SpacetimeDB ping to succeed"
                   :timeout        (* 2 universal-timeout)})
      true
      (catch Exception _
        false))))

; local SpacetimeDB database
(defrecord STDB []
  db/DB
  (setup!
    [this {:keys [force-reinstall-repository? force-reinstall-spacetimedb? lazyfs? spacetimedb-version universal-timeout] :as test} node]
    (info "setting up SpacetimeDB on" node)

    (install-packages)
    (install-spacetimedb spacetimedb-version force-reinstall-spacetimedb? lazyfs?)

    (db/start! this test node)

    (configure-test-db force-reinstall-repository?)

    (let [alive? (u/timeout (* 4 universal-timeout) ::timed-out
                            (while (not (spacetimedb-alive? test))
                              (info "waiting for SpacetimeDB to be alive")))]
      (when (= ::timed-out alive?)
        (throw+ {:type :error :error "unable to start SpacetimeDB"})))

    (info "SpacetimeDB setup on" node))

  (teardown!
    [this test node]
    (info "tearing down SpacetimeDB on" node)
    (db/kill! this test node)

    ; NOTE: leaving SpacetimeDB installed

    (c/su
     (c/exec :rm :-rf log-file pid-file)))

  ;; SpacetimeDB doesn't have `primaries`.
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
    [_this _test _node]
    (killall spacetimedb-ps-name)
    :killed)

  db/Pause
  (pause!
    [_this _test _node]
    (killall :STOP spacetimedb-ps-name)
    :paused)

  (resume!
    [_this _test _node]
    (killall :CONT spacetimedb-ps-name)
    :resumed))

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

  ; SpacetimeDB doesn't have `primaries`
  db/Primary
  (primaries
    [_db _test]
    nil)

  (setup-primary!
    [_db _test _node])

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