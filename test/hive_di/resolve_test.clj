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
