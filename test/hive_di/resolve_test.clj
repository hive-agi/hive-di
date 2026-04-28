(ns hive-di.resolve-test
  "Tests for the config resolution engine."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-di.resolve :as resolve]
            [hive-di.source :as source]
            [hive-dsl.result :as r]))

;; =============================================================================
;; Test Fixtures — Example Field Registries
;; =============================================================================

(def simple-fields
  {:host (source/env "TEST_HOST" :default "localhost" :type :string)
   :port (source/env "TEST_PORT" :default 8080 :type :int)
   :debug (source/env "TEST_DEBUG" :default false :type :bool)
   :name (source/literal "test-app")})

(def required-no-default-fields
  {:api-key (source/env "API_KEY" :type :string)})

(def optional-fields
  {:nickname (source/env "NICKNAME" :type :string :required false)})

(def no-env (constantly nil))

;; =============================================================================
;; Defaults Resolution
;; =============================================================================

(deftest resolves-all-defaults-when-no-env
  (let [result (resolve/resolve-config simple-fields {} {:env-fn no-env})]
    (is (r/ok? result))
    (is (= "localhost" (:host (:ok result))))
    (is (= 8080 (:port (:ok result))))
    (is (= false (:debug (:ok result))))
    (is (= "test-app" (:name (:ok result))))))

(deftest literal-values-always-resolve
  (let [result (resolve/resolve-config
                 {:name (source/literal "fixed")}
                 {} {:env-fn no-env})]
    (is (r/ok? result))
    (is (= "fixed" (:name (:ok result))))))

;; =============================================================================
;; :source/file Resolution
;; =============================================================================

(deftest file-source-resolves-via-injected-file-fn
  (let [fields {:api-key (source/file "fake.edn" [:secrets :openrouter-api-key]
                                      :type :string)}
        file-fn (constantly {:secrets {:openrouter-api-key "sk-from-file"}})
        result (resolve/resolve-config fields {} {:env-fn no-env :file-fn file-fn})]
    (is (r/ok? result))
    (is (= "sk-from-file" (:api-key (:ok result))))))

(deftest file-source-falls-back-to-default-when-key-absent
  (let [fields {:host (source/file "fake.edn" [:host]
                                   :default "localhost" :type :string)}
        result (resolve/resolve-config fields {} {:env-fn no-env
                                                  :file-fn (constantly nil)})]
    (is (r/ok? result))
    (is (= "localhost" (:host (:ok result))))))

(deftest file-source-required-no-value-fails
  (let [fields {:tok (source/file "fake.edn" [:tok] :type :string)}
        result (resolve/resolve-config fields {} {:env-fn no-env
                                                  :file-fn (constantly {})})]
    (is (not (r/ok? result)))))

;; =============================================================================
;; Override Resolution
;; =============================================================================

(deftest overrides-win-over-defaults
  (let [result (resolve/resolve-config simple-fields
                 {:host "override.host" :port 9090}
                 {:env-fn no-env})]
    (is (r/ok? result))
    (is (= "override.host" (:host (:ok result))))
    (is (= 9090 (:port (:ok result))))
    ;; Non-overridden fields use defaults
    (is (= false (:debug (:ok result))))))

(deftest string-overrides-get-coerced
  (let [result (resolve/resolve-config simple-fields
                 {:port "9091" :debug "true"}
                 {:env-fn no-env})]
    (is (r/ok? result))
    (is (= 9091 (:port (:ok result))))
    (is (= true (:debug (:ok result))))))

;; =============================================================================
;; Env Var Resolution
;; =============================================================================

(deftest env-vars-override-defaults
  (let [mock-env {"TEST_HOST" "env.host" "TEST_PORT" "3000"}
        result (resolve/resolve-config simple-fields {}
                 {:env-fn #(get mock-env %)})]
    (is (r/ok? result))
    (is (= "env.host" (:host (:ok result))))
    (is (= 3000 (:port (:ok result))))
    ;; Unset env var → default
    (is (= false (:debug (:ok result))))))

(deftest overrides-win-over-env-vars
  (let [mock-env {"TEST_HOST" "env.host"}
        result (resolve/resolve-config simple-fields
                 {:host "override.host"}
                 {:env-fn #(get mock-env %)})]
    (is (r/ok? result))
    (is (= "override.host" (:host (:ok result))))))

;; =============================================================================
;; Empty String Handling (blank->nil fix)
;; =============================================================================

(deftest empty-string-env-var-triggers-default
  (let [mock-env {"TEST_HOST" "" "TEST_PORT" "  "}
        result (resolve/resolve-config simple-fields {}
                 {:env-fn #(get mock-env %)})]
    (is (r/ok? result))
    (is (= "localhost" (:host (:ok result))) "Empty env var should fall back to default")
    (is (= 8080 (:port (:ok result))) "Blank env var should fall back to default")))

(deftest empty-string-override-triggers-default
  (let [result (resolve/resolve-config simple-fields
                 {:host "" :port ""}
                 {:env-fn no-env})]
    (is (r/ok? result))
    (is (= "localhost" (:host (:ok result))))
    (is (= 8080 (:port (:ok result))))))

;; =============================================================================
;; Error Collection (no short-circuit)
;; =============================================================================

(deftest missing-required-field-returns-error
  (let [result (resolve/resolve-config required-no-default-fields {}
                 {:env-fn no-env})]
    (is (r/err? result))
    (is (= :config/resolution-failed (:error result)))
    (is (seq (:errors (dissoc result :error))))))

(deftest invalid-coercion-returns-error
  (let [result (resolve/resolve-config simple-fields
                 {:port "not-a-number"}
                 {:env-fn no-env})]
    (is (r/err? result))
    (is (= :config/resolution-failed (:error result)))))

(deftest multiple-errors-collected
  (testing "Both missing required AND invalid coercion reported"
    (let [fields {:api-key (source/env "API_KEY" :type :string)
                  :port    (source/env "PORT" :default 8080 :type :int)}
          result (resolve/resolve-config fields
                   {:port "not-a-number"}
                   {:env-fn no-env})]
      (is (r/err? result))
      ;; Should have errors for both api-key (missing) and port (invalid)
      (let [errors (:errors (dissoc result :error))]
        (is (= 2 (count errors))
            (str "Expected 2 errors, got: " (pr-str errors)))))))

;; =============================================================================
;; Optional Fields
;; =============================================================================

(deftest optional-field-resolves-to-nil
  (let [result (resolve/resolve-config optional-fields {}
                 {:env-fn no-env})]
    (is (r/ok? result))
    (is (nil? (:nickname (:ok result))))))

(deftest optional-field-resolves-when-present
  (let [result (resolve/resolve-config optional-fields
                 {:nickname "Bob"}
                 {:env-fn no-env})]
    (is (r/ok? result))
    (is (= "Bob" (:nickname (:ok result))))))

;; =============================================================================
;; Type Coercion Edge Cases
;; =============================================================================

(deftest boolean-coercion-variants
  (doseq [[input expected] [["true" true] ["false" false]
                             ["1" true] ["0" false]
                             ["yes" true] ["no" false]
                             ["TRUE" true] ["FALSE" false]]]
    (let [fields {:flag (source/env "FLAG" :type :bool)}
          result (resolve/resolve-config fields {:flag input}
                   {:env-fn no-env})]
      (is (r/ok? result) (str "Expected ok for input: " input))
      (is (= expected (:flag (:ok result)))
          (str "Expected " expected " for input: " input)))))

(deftest pre-typed-defaults-skip-coercion
  (testing "Integer default doesn't go through string coercion"
    (let [result (resolve/resolve-config
                   {:port (source/env "PORT" :default 8080 :type :int)}
                   {} {:env-fn no-env})]
      (is (r/ok? result))
      (is (= 8080 (:port (:ok result))))
      (is (integer? (:port (:ok result)))))))

(deftest pre-typed-override-skips-coercion
  (testing "Integer override doesn't need coercion"
    (let [result (resolve/resolve-config
                   {:port (source/env "PORT" :default 8080 :type :int)}
                   {:port 9090}
                   {:env-fn no-env})]
      (is (r/ok? result))
      (is (= 9090 (:port (:ok result)))))))

;; =============================================================================
;; :source/coalesce Resolution — chain env > file > default
;; =============================================================================

(def coalesce-spec
  {:db-path (source/coalesce
              [(source/env "HIVE_KG_DB_PATH" :required false)
               (source/file "/tmp/hive-test.edn" [:services :datahike :path]
                            :required false)]
              :default "data/kg/datahike"
              :type :string
              :doc "Datahike file-store path")})

(def file-with-path
  (constantly {:services {:datahike {:path "/from/file"}}}))

(deftest coalesce-env-wins-when-set
  (let [result (resolve/resolve-config coalesce-spec {}
                 {:env-fn (constantly "/from/env")
                  :file-fn file-with-path})]
    (is (r/ok? result))
    (is (= "/from/env" (:db-path (:ok result))))))

(deftest coalesce-file-wins-when-env-unset
  (let [result (resolve/resolve-config coalesce-spec {}
                 {:env-fn no-env :file-fn file-with-path})]
    (is (r/ok? result))
    (is (= "/from/file" (:db-path (:ok result))))))

(deftest coalesce-default-when-all-unset
  (let [result (resolve/resolve-config coalesce-spec {}
                 {:env-fn no-env :file-fn (constantly nil)})]
    (is (r/ok? result))
    (is (= "data/kg/datahike" (:db-path (:ok result))))))

(deftest coalesce-override-trumps-all-sources
  (let [result (resolve/resolve-config coalesce-spec {:db-path "/from/override"}
                 {:env-fn (constantly "/from/env")
                  :file-fn file-with-path})]
    (is (r/ok? result))
    (is (= "/from/override" (:db-path (:ok result))))))

(deftest coalesce-blank-env-falls-through-to-file
  (testing "Empty env string is treated as unset, not empty value"
    (let [result (resolve/resolve-config coalesce-spec {}
                   {:env-fn (constantly "")
                    :file-fn file-with-path})]
      (is (r/ok? result))
      (is (= "/from/file" (:db-path (:ok result)))))))

(deftest coalesce-required-no-default-fails-when-empty
  (let [fields {:tok (source/coalesce
                       [(source/env "X" :required false)
                        (source/file "f.edn" [:k] :required false)]
                       :type :string)}
        result (resolve/resolve-config fields {}
                 {:env-fn no-env :file-fn (constantly nil)})]
    (is (not (r/ok? result)))))

(deftest coalesce-empty-sources-falls-to-default
  (let [fields {:host (source/coalesce [] :default "localhost" :type :string)}
        result (resolve/resolve-config fields {} {:env-fn no-env})]
    (is (r/ok? result))
    (is (= "localhost" (:host (:ok result))))))
