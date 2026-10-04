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
  [force-reinstall-spacetimedb?]
  (c/su
   (when force-reinstall-spacetimedb?
     ; remove any old files
     (doseq [file-or-dir (vals spacetimedb-files)]
       (u/meh  ; data dir may already be lazyfs mounted so undeletable, so meh
        (c/exec :rm :-rf file-or-dir))))

   (c/exec :mkdir :--parents jepsen-dir)

   (if (not (cu/exists? spacetimedb-binary))
     (do
       (info "downloading and installing latest SpacetimeDB")
       (c/cd jepsen-dir
             ; download and install binary
             (c/exec :curl :-sSf :--output :install-spacetimedb.sh "https://install.spacetimedb.com")
             (c/exec :chmod :a+x :install-spacetimedb.sh)
             (c/exec "./install-spacetimedb.sh" :--yes)

             ; configuring should also create config ~/.config/spacetime/cli.toml
             (c/exec spacetimedb-binary :server :set-default :local)))
     (do
       (info "SpacetimeDB already installed, clearing and upgrading")
       (c/exec spacetimedb-binary :server :clear :--yes)
       (c/exec spacetimedb-binary :version :upgrade)))))

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
   a SQL query in [[spacetimedb-db-name]]."
  [{:keys [spacetimedb-node universal-timeout] :as test}]
  (c/with-node test spacetimedb-node
    (try
      (u/await-fn (fn sql-query-spacetimedb []
                    (c/exec spacetimedb-binary :sql
                            :--confirmed :true :--anonymous :--server :local :--yes spacetimedb-db-name
                            :select :* :from :lists))
                  {:retry-interval 500
                   :log-interval   500
                   :log-message    "Waiting for SpacetimeDB liveness query..."
                   :timeout        (* 2 universal-timeout)})
      true
      (catch Exception _
        false))))

(def spacetimedb-setup? (atom false))

;; Local SpacetimeDB database.
;; lazyfs-map may be nil
(defrecord STDB [lazyfs-map]
  db/DB
  (setup!
    [this {:keys [force-reinstall-repository? force-reinstall-spacetimedb?] :as test} node]
    (info "setting up SpacetimeDB" node)

    (install-packages)
    (install-spacetimedb force-reinstall-spacetimedb?)

    ; NOTE: must install SpacetimeDB before
    ; mounting lazyfs and starting the db
    (when lazyfs-map
      (if-not (cu/exists? lazyfs/bin)
        (lazyfs/install!)
        (info "using already installed lazyfs bin:" lazyfs/bin))
      (lazyfs/mount! lazyfs-map))

    (db/start! this test node)

    (configure-test-db force-reinstall-repository?)

    (let [alive? (u/timeout 10000 ::timed-out
                            (while (not (spacetimedb-alive? test))
                              (info "waiting for SpacetimeDB to be alive")))]
      (when (= ::timed-out alive?)
        (throw+ {:type :error :error "unable to start SpacetimeDB"})))

    (info "SpacetimeDB setup")
    (swap! spacetimedb-setup? (constantly true)))

  (teardown!
    [this test node]
    (info "tearing down SpacetimeDB" node)
    (db/kill! this test node)

    ; NOTE: leaving SpacetimeDB installed

    (c/su
     (c/exec :rm :-rf log-file pid-file))

    ; NOTE: teardown lazyfs last
    (when lazyfs-map
      (lazyfs/umount! lazyfs-map)
      (c/su
       (c/exec :rm :-rf log-lazyfs)))

    (swap! spacetimedb-setup? (constantly false)))

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
    (merge
     {log-file log-file-short}
     (when lazyfs-map
       {log-lazyfs log-lazyfs-short})))

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
          spacetimedb-binary
          :start
          :--data-dir spacetimedb-data-dir
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
  (STDB. nil))

(defn lazyfs-stdb
  "Installs and uses the latest version of SpacetimeDB.
   Data directory is mounted on a lazyfs."
  []
  (let [lazyfs-map {:dir spacetimedb-data-dir :log-file log-lazyfs}
        lazyfs-map (lazyfs/lazyfs lazyfs-map)]
    (STDB. lazyfs-map)))

(defn watched-stdb
  "Wraps given stdb with a [[jepsen.db.watchdog]] that monitors and restarts every interval."
  [stdb interval]
  (let [opts {:running? (fn running? [test _node]
                          (spacetimedb-alive? test))
              :interval interval}]
    (watchdog/db opts stdb)))