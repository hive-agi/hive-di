(ns hive-di.pass-test
  "The :source/pass secret source. Resolution is driven through an INJECTED
   :pass-fn, so nothing here needs a pass store, a `pass` binary, or a real
   secret — the suite is identical on every machine and in CI."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-di.pass :as pass]
            [hive-di.resolve :as resolve]
            [hive-di.source :as source]
            [hive-dsl.result :as r]))

(def ^:private no-env (constantly nil))
(def ^:private no-file (constantly nil))

(defn- store
  "A pass-fn backed by a map — an absent path resolves to nil, as a real store
   does for a missing entry."
  [m]
  (fn [path] (get m path)))

(defn- resolved
  [fields pass-fn]
  (resolve/resolve-config fields {} {:env-fn no-env :file-fn no-file :pass-fn pass-fn}))

;; --- the source constructor -------------------------------------------------

(deftest pass-declares-a-pass-source
  (let [s (source/pass "Venice/key")]
    (is (= :source/pass (:source s)))
    (is (= "Venice/key" (:path s)))
    (is (= :string (:type s)))
    (is (:required s) "required by default, like env and file"))
  (testing "options mirror env/file"
    (let [s (source/pass "a/b" :required false :default "fallback" :doc "key")]
      (is (false? (:required s)))
      (is (= "fallback" (:default s)))
      (is (= "key" (:doc s))))))

;; --- resolution -------------------------------------------------------------

(deftest a-present-entry-resolves-to-its-secret
  (let [res (resolved {:api-key (source/pass "Venice/key")}
                      (store {"Venice/key" "sk-live"}))]
    (is (r/ok? res))
    (is (= "sk-live" (:api-key (:ok res))))))

(deftest a-missing-entry-behaves-like-any-other-absent-source
  (testing "required with no default fails loud"
    (let [res (resolved {:api-key (source/pass "Venice/key")} (store {}))]
      (is (r/err? res))
      (is (= :config/resolution-failed (:error res)))))
  (testing "optional resolves to nil"
    (let [res (resolved {:api-key (source/pass "Venice/key" :required false)} (store {}))]
      (is (r/ok? res))
      (is (nil? (:api-key (:ok res))))))
  (testing "a default still applies"
    (let [res (resolved {:api-key (source/pass "Venice/key" :default "none")} (store {}))]
      (is (= "none" (:api-key (:ok res)))))))

(deftest a-blank-secret-is-no-secret
  (let [res (resolved {:api-key (source/pass "Venice/key" :required false)}
                      (store {"Venice/key" "   "}))]
    (is (nil? (:api-key (:ok res))))
    (is (r/ok? res) "whitespace must not pass for a key")))

;; --- the reason this source exists ------------------------------------------

(deftest a-resolvable-pass-entry-beats-a-stale-env-var
  ;; The precedence the engine's adapters rely on: an old key left in the
  ;; environment must not shadow the real one in the store.
  (let [fields {:api-key (source/coalesce [(source/pass "Venice/key")
                                           (source/env "VENICE_API_KEY")])}
        res    (resolve/resolve-config
                fields {}
                {:env-fn  (constantly "stale-env-key")
                 :file-fn no-file
                 :pass-fn (store {"Venice/key" "real-pass-key"})})]
    (is (= "real-pass-key" (:api-key (:ok res))))))

(deftest the-env-var-is-the-fallback-not-the-loser
  (testing "an unreadable store falls through to the env var"
    (let [fields {:api-key (source/coalesce [(source/pass "Venice/key")
                                             (source/env "VENICE_API_KEY")])}
          res    (resolve/resolve-config
                  fields {}
                  {:env-fn  (constantly "env-key")
                   :file-fn no-file
                   :pass-fn (store {})})]
      (is (= "env-key" (:api-key (:ok res))))))
  (testing "neither source means the field is simply absent"
    (let [fields {:api-key (source/coalesce [(source/pass "Venice/key")
                                             (source/env "VENICE_API_KEY")]
                                            :required false)}
          res    (resolve/resolve-config
                  fields {} {:env-fn no-env :file-fn no-file :pass-fn (store {})})]
      (is (r/ok? res))
      (is (nil? (:api-key (:ok res)))))))

(deftest an-override-still-wins-over-every-source
  (let [res (resolve/resolve-config
             {:api-key (source/pass "Venice/key")}
             {:api-key "injected"}
             {:env-fn no-env :file-fn no-file :pass-fn (store {"Venice/key" "sk-live"})})]
    (is (= "injected" (:api-key (:ok res))))))

;; --- the shelling-out part --------------------------------------------------

(deftest show-never-throws-on-a-path-it-cannot-read
  (testing "blank paths short-circuit without shelling out"
    (is (nil? (pass/show nil)))
    (is (nil? (pass/show "")))
    (is (nil? (pass/show "   "))))
  (testing "a missing entry (or a missing `pass`) is an absent value, not a throw"
    (is (nil? (pass/show "hive-di/definitely-not-a-real-entry")))
    (is (false? (pass/present? "hive-di/definitely-not-a-real-entry")))))
