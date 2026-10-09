(ns spacetimedb.util
  (:require [jepsen
             [control :as c]
             [util :as u]]))

; Jepsen's grepkill! has a bug
; FORNOW: workaround by explicitly calling killall
(defn killall
  "Kills the process group, or uses `signal` if given,
   for the given process name (as regex). Can optionally wait for the process to die.
   Assumes on node, privs.
   Returns a keyword of the signal used + 'ed'."
  ([process-name]        (killall :KILL  process-name false))
  ([signal process-name] (killall signal process-name false))
  ([signal process-name wait?]
   (u/meh ; will Exception if no processes
    (c/exec :killall :--signal signal :--process-group :--regexp (when wait? :--wait) :-- process-name))
   (keyword (str signal "ed"))))