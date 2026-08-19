(ns hive-di.pass
  "pass-store (passwordstore.org) reads for :source/pass.

   Isolated in its own namespace because it is the one part of resolution that
   shells out: a caller that must stay pure injects :pass-fn instead."
  (:require [clojure.string :as str]))

(defonce ^:private -sh
  (delay
    (try
      (requiring-resolve 'clojure.java.shell/sh)
      (catch Exception _ nil))))

(defn show
  "First line of `pass show <path>` — the pass convention for the secret itself.
   => secret-string | nil when the path is blank, the entry is missing, the
   store is locked, `pass` is not installed, or the host carries no
   `clojure.java.shell`. Never throws: an unreadable secret is an absent value,
   so a coalesce chain falls through to its next source rather than failing the
   whole resolve."
  [path]
  (when-not (str/blank? (str path))
    (try
      (when-let [sh @-sh]
        (let [{:keys [exit out]} (sh "pass" "show" (str path))]
          (when (zero? exit)
            (some-> out str/split-lines first str/trim not-empty))))
      (catch Exception _ nil))))

(defn present?
  "Whether `path` resolves to a secret, WITHOUT retaining it. For diagnostics
   that must report whether a key is reachable but must not hold it."
  [path]
  (some? (show path)))
