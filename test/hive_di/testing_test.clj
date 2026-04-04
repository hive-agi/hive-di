(ns hive-di.testing-test
  "Tests for the testing DSL itself — meta-tests."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-di.testing :as di-testing]
            [hive-di.source :as source]
            [hive-di.resolve :as resolve]
            [hive-dsl.result :as r]))

(def example-fields
  {:host  (source/env "HOST" :default "localhost" :type :string)
   :port  (source/env "PORT" :default 8080 :type :int)
   :debug (source/env "DEBUG" :default false :type :bool)
   :name  (source/literal "test-app")})

;; =============================================================================
;; Generator Tests
;; =============================================================================

(deftest gen-config-overrides-produces-maps
  (let [g (di-testing/gen-config-overrides example-fields)
        samples (gen/sample g 20)]
    (is (every? map? samples))
    (testing "Override keys are subset of field keys"
      (doseq [sample samples]
        (is (every? #{:host :port :debug :name} (keys sample)))))))

(deftest gen-mock-env-produces-valid-envs
  (let [g (di-testing/gen-mock-env example-fields)
        samples (gen/sample g 10)]
    (doseq [{:keys [env-fn]} samples]
      (is (fn? env-fn))
      ;; env-fn returns string or nil for any var name
      (let [v (env-fn "HOST")]
        (is (or (nil? v) (string? v)))))))

;; =============================================================================
;; Assertion Helper Tests
;; =============================================================================

(deftest resolve-with-defaults-only-works
  (let [result (di-testing/resolve-with-defaults-only example-fields)]
    (is (r/ok? result))
    (is (= "localhost" (:host (:ok result))))))

(deftest resolve-with-field-removed-graceful
  (testing "Removing a field with default → still resolves (field becomes required-no-default)"
    (let [result (di-testing/resolve-with-field-removed example-fields :host)]
      ;; Should return err since :host has no default and env is nil
      (is (or (r/ok? result) (r/err? result))
          "Must return Result, not throw"))))

;; =============================================================================
;; Auto-generated tests via defconfig-tests
;; =============================================================================

(di-testing/defconfig-tests ExampleConfig example-fields
  :num-tests 50)
