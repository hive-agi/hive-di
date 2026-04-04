(ns hive-di.core-test
  "Tests for the defconfig macro and public API."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-di.core :refer [defconfig env literal resolve-config]]
            [hive-dsl.result :as r]))

;; =============================================================================
;; Example Config — defined at test ns level
;; =============================================================================

(defconfig TestConfig
  :host  (env "TEST_HOST" :default "localhost" :type :string)
  :port  (env "TEST_PORT" :default 8080 :type :int)
  :debug (env "TEST_DEBUG" :default false :type :bool)
  :name  (literal "test-app"))

;; =============================================================================
;; Macro Expansion — Generated Artifacts
;; =============================================================================

(deftest field-registry-generated
  (is (map? TestConfig-fields))
  (is (contains? TestConfig-fields :host))
  (is (contains? TestConfig-fields :port))
  (is (contains? TestConfig-fields :debug))
  (is (contains? TestConfig-fields :name))
  (is (= :source/env (:source (:host TestConfig-fields))))
  (is (= :source/literal (:source (:name TestConfig-fields)))))

(deftest malli-schema-generated
  (is (vector? TestConfig-schema))
  (is (= :map (first TestConfig-schema))))

(deftest resolver-fn-generated
  (is (fn? resolve-TestConfig)))

;; =============================================================================
;; Resolver Shorthand
;; =============================================================================

(deftest resolve-with-defaults
  (let [result (resolve-TestConfig {} {:env-fn (constantly nil)})]
    (is (r/ok? result))
    (let [config (:ok result)]
      (is (= "localhost" (:host config)))
      (is (= 8080 (:port config)))
      (is (= false (:debug config)))
      (is (= "test-app" (:name config))))))

(deftest resolve-with-overrides
  (let [result (resolve-TestConfig {:host "other" :port "9090"}
                                   {:env-fn (constantly nil)})]
    (is (r/ok? result))
    (let [config (:ok result)]
      (is (= "other" (:host config)))
      (is (= 9090 (:port config))))))

(deftest resolve-with-mock-env
  (let [mock-env {"TEST_HOST" "env.host" "TEST_PORT" "3000" "TEST_DEBUG" "true"}
        result (resolve-TestConfig {} {:env-fn #(get mock-env %)})]
    (is (r/ok? result))
    (let [config (:ok result)]
      (is (= "env.host" (:host config)))
      (is (= 3000 (:port config)))
      (is (= true (:debug config)))
      (is (= "test-app" (:name config)) "Literal unaffected by env"))))

;; =============================================================================
;; Error Cases via Resolver
;; =============================================================================

(defconfig StrictConfig
  :api-key (env "API_KEY" :type :string)
  :secret  (env "SECRET" :type :string))

(deftest missing-required-fields-collected
  (let [result (resolve-StrictConfig {} {:env-fn (constantly nil)})]
    (is (r/err? result))
    (is (= :config/resolution-failed (:error result)))
    (let [errors (:errors (dissoc result :error))]
      (is (= 2 (count errors))
          "Both api-key and secret should be reported missing"))))
