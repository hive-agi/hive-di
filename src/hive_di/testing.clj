(ns hive-di.testing
  "Auto-generated test macros for defconfig definitions.

   Given a config's field registry, generates:
   - Property tests (totality, roundtrip)
   - Golden baseline tests
   - Mutation witness tests
   - Config override generators

   Depends on hive-test macros (test-only dependency)."
  (:require [clojure.test.check.generators :as gen]
            [hive-di.resolve :as resolve]
            [hive-dsl.result :as r]))

;; =============================================================================
;; Generator Builders
;; =============================================================================

(defn gen-value-for-type
  "Generator producing valid values for a given config type."
  [type-kw]
  (case type-kw
    :string  gen/string-alphanumeric
    :int     (gen/choose -10000 10000)
    :double  (gen/double* {:min -10000.0 :max 10000.0 :NaN? false :infinite? false})
    :bool    gen/boolean
    :keyword (gen/fmap keyword gen/string-alphanumeric)
    :vec     (gen/vector gen/string-alphanumeric 0 5)
    :enum    (gen/fmap keyword gen/string-alphanumeric)
    gen/any-printable-equatable))

(defn gen-string-for-type
  "Generator producing string representations for a given config type.
   Simulates env var values."
  [type-kw]
  (case type-kw
    :string  gen/string-alphanumeric
    :int     (gen/fmap str (gen/choose -10000 10000))
    :double  (gen/fmap str (gen/double* {:min -10000.0 :max 10000.0 :NaN? false :infinite? false}))
    :bool    (gen/elements ["true" "false" "1" "0" "yes" "no"])
    :keyword (gen/fmap name (gen/fmap keyword gen/string-alphanumeric))
    :vec     (gen/return "[\"a\",\"b\"]")
    :enum    (gen/fmap name (gen/fmap keyword gen/string-alphanumeric))
    gen/string-alphanumeric))

(defn gen-config-overrides
  "Generator producing random override maps for property testing.

   For each field, randomly chooses:
   - Present with valid typed value
   - Present as string (simulating env var)
   - Present as empty string (edge case)
   - Present as nil
   - Absent (not in map)"
  [fields]
  (let [field-gens (mapv (fn [[field-kw {:keys [type]}]]
                           (gen/one-of
                             [(gen/return nil)               ;; absent
                              (gen/return [field-kw nil])    ;; nil value
                              (gen/return [field-kw ""])     ;; empty string
                              (gen/fmap #(vector field-kw %) ;; valid typed
                                        (gen-value-for-type type))
                              (gen/fmap #(vector field-kw %) ;; string repr
                                        (gen-string-for-type type))]))
                         fields)]
    (gen/fmap (fn [entries]
               (into {} (remove nil? entries)))
             (apply gen/tuple field-gens))))

(defn gen-mock-env
  "Generator producing a mock env-fn from field specs.
   Returns [env-map env-fn] where env-fn = #(get env-map %)."
  [fields]
  (let [env-fields (filterv (fn [[_ spec]] (= :source/env (:source spec))) fields)
        env-gens   (mapv (fn [[_ {:keys [env-var type]}]]
                           (gen/one-of
                             [(gen/return nil)            ;; unset
                              (gen/return [env-var ""])   ;; empty
                              (gen/fmap #(vector env-var %)
                                        (gen-string-for-type type))]))
                         env-fields)]
    (gen/fmap (fn [entries]
               (let [env-map (into {} (remove nil? entries))]
                 {:env-map env-map
                  :env-fn  #(get env-map %)}))
             (apply gen/tuple env-gens))))

;; =============================================================================
;; Assertion Helpers
;; =============================================================================

(defn resolve-with-defaults-only
  "Resolve config with no overrides and no env vars.
   Tests that defaults alone produce a valid config."
  [fields]
  (resolve/resolve-config fields {} {:env-fn (constantly nil)}))

(defn resolve-with-field-removed
  "Resolve config with one field's default removed from the registry.
   Used for mutation testing."
  [fields field-kw]
  (let [mutated (update fields field-kw dissoc :default)]
    (resolve/resolve-config mutated {} {:env-fn (constantly nil)})))

;; =============================================================================
;; Test Generation Macro
;; =============================================================================

(defmacro defconfig-tests
  "Generate comprehensive tests for a defconfig definition.

   Requires these namespaces in the calling test ns:
     [clojure.test :refer [deftest is]]
     [clojure.test.check.clojure-test :refer [defspec]]
     [clojure.test.check.properties :as prop]

   (defconfig-tests MilvusConfig MilvusConfig-fields
     :num-tests 100)

   Generates:
   1. <Name>-totality       — resolver never throws for any input
   2. <Name>-defaults-only  — defaults produce valid config
   3. <Name>-roundtrip      — resolved config survives EDN serialization
   4. <Name>-field-mutations — removing each default is handled gracefully"
  [config-name fields-sym & {:keys [num-tests] :or {num-tests 100}}]
  (let [totality-name   (symbol (str config-name "-totality"))
        defaults-name   (symbol (str config-name "-defaults-only"))
        roundtrip-name  (symbol (str config-name "-roundtrip"))
        mutations-name  (symbol (str config-name "-field-mutations"))]
    `(do
       ;; Ensure required namespaces are loaded
       (require '[clojure.test.check.clojure-test]
                '[clojure.test.check.properties]
                '[clojure.edn])

       ;; 1. Totality: resolver never throws for any input combination
       (clojure.test.check.clojure-test/defspec ~totality-name ~num-tests
         (clojure.test.check.properties/for-all
           [overrides# (gen-config-overrides ~fields-sym)
            mock#      (gen-mock-env ~fields-sym)]
           (let [result# (try
                           (resolve/resolve-config ~fields-sym overrides#
                                                   {:env-fn (:env-fn mock#)})
                           (catch Throwable t#
                             {:threw t#}))]
             ;; Must return a Result (ok or err), never throw
             (or (r/ok? result#)
                 (r/err? result#)))))

       ;; 2. Defaults only: no env, no overrides → must resolve
       (clojure.test/deftest ~defaults-name
         (let [result# (resolve-with-defaults-only ~fields-sym)]
           (clojure.test/is (r/ok? result#)
                            (str "Config with defaults-only should resolve, got: "
                                 (pr-str result#)))))

       ;; 3. Roundtrip: resolved config survives EDN serialization
       (clojure.test.check.clojure-test/defspec ~roundtrip-name ~num-tests
         (clojure.test.check.properties/for-all
           [overrides# (gen-config-overrides ~fields-sym)]
           (let [result# (resolve/resolve-config ~fields-sym overrides#
                                                 {:env-fn (constantly nil)})]
             (if (r/ok? result#)
               (let [resolved# (:ok result#)
                     round#    (clojure.edn/read-string (pr-str resolved#))]
                 (= resolved# round#))
               ;; err results don't need roundtrip — pass
               true))))

       ;; 4. Field mutations: removing each default is graceful
       (clojure.test/deftest ~mutations-name
         (doseq [[field-kw# _spec#] ~fields-sym]
           (let [result# (try
                           (resolve-with-field-removed ~fields-sym field-kw#)
                           (catch Throwable t#
                             {:threw t#}))]
             (clojure.test/is (or (r/ok? result#) (r/err? result#))
                              (str "Removing default for " field-kw#
                                   " should return Result, not throw"))))))))
