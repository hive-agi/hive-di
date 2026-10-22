(ns hive-di.contract-test
  "Contract facets for hive-di's public config ports (cov5): every public fn
   of hive-di.source / resolve / schema / pass carries an m/=> contract
   (registered from the non-runtime `contracts/` path), and the ports honour
   those contracts under malli instrumentation — constructors build valid
   field specs, resolution is total and answers a well-shaped Result, and the
   generated malli schema accepts what resolution produced."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-di.contracts :as c]
            [hive-di.pass :as pass]
            [hive-di.resolve :as resolve]
            [hive-di.schema :as schema]
            [hive-di.source :as source]
            [hive-dsl.result :as r]
            [hive-test.properties :as props]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def ^:private nses
  ['hive-di.source 'hive-di.resolve 'hive-di.schema 'hive-di.pass])

(defn- throwing-report [type data]
  (throw (ex-info (str type) (select-keys data [:args :value :input :output]))))

(defn- instrumented [f]
  (mi/instrument! {:filters [(apply mi/-filter-ns nses)] :report throwing-report})
  (try (f) (finally (mi/unstrument! {:filters [(apply mi/-filter-ns nses)]}))))

(use-fixtures :each (fn [f] (instrumented f)))

;; =============================================================================
;; Coverage facet
;; =============================================================================

(deftest every-public-port-fn-has-a-contract
  (doseq [ns-sym (conj nses 'hive-di.core)
          :let [fns (->> (ns-publics ns-sym) vals
                         (filter #(and (fn? @%) (not (:macro (meta %)))))
                         (map #(-> % meta :name)))
                contracted (set (keys (get (m/function-schemas) ns-sym)))]
          f fns]
    (is (contains? contracted f) (str ns-sym "/" f " has no m/=> contract"))))

;; =============================================================================
;; Constructors honour their contracts
;; =============================================================================

(deftest source-constructors-honour-their-contracts
  (testing "each constructor yields a valid FieldSpec"
    (doseq [spec [(source/env "HOST" :default "localhost" :doc "host")
                  (source/env "MODE" :type :enum :allowed #{:a :b})
                  (source/literal 8080)
                  (source/literal "x" :type :string :doc "d")
                  (source/file "f.edn" '(:a :b) :default 1 :type :int)
                  (source/pass "Venice/key" :required false)
                  (source/coalesce [(source/pass "k") (source/env "K")]
                                   :required false)]]
      (is (m/validate c/FieldSpec spec) (pr-str spec))))
  (testing "a non-string env var name is refused at the contract boundary"
    (is (thrown? Exception (source/env :HOST))))
  (testing "a non-sequential key-path is refused"
    (is (thrown? Exception (source/file "f.edn" :a)))))

(deftest schema-and-pass-honour-their-contracts
  (is (= [:vector :any] (schema/type->malli :vec)))
  (is (= :any (schema/type->malli :unknown)))
  (is (r/ok? (schema/validate-resolved [:map [:a :int]] {:a 1})))
  (is (= :config/validation-failed
         (:error (schema/validate-resolved [:map [:a :int]] {:a "x"}))))
  (is (nil? (pass/show "")))
  (is (false? (pass/present? ""))))

(deftest config-source-adt-honours-its-contracts
  (let [v (source/config-source :source/env {:env-var "A"})]
    (is (true? (source/config-source? v)))
    (is (= :source/env (:adt/variant v))))
  (is (= :source/literal (:adt/variant (source/->config-source :source/literal))))
  (is (nil? (source/->config-source :source/bogus)))
  (is (false? (source/config-source? (source/env "A")))))

;; =============================================================================
;; Property facets
;; =============================================================================

(def ^:private gen-value
  (gen/one-of [gen/string-alphanumeric gen/small-integer gen/boolean
               gen/keyword (gen/vector gen/small-integer)
               (gen/double* {:NaN? false :infinite? false})]))

(props/defprop-total p1-infer-type-totality source/infer-type gen-value
  {:num-tests 100 :pred #(m/validate c/InferredType %)})

(props/defprop-total p1-type->malli-totality schema/type->malli gen/any-equatable
  {:num-tests 100 :pred #(m/validate c/MalliType %)})

(def ^:private gen-type
  (gen/elements [:string :int :double :bool :keyword :vec]))

(def ^:private gen-field-spec
  (gen/let [var-name gen/string-alphanumeric
            type     gen-type
            required gen/boolean
            kind     (gen/elements [:env :literal :pass])]
    (case kind
      :env     (source/env var-name :type type :required required)
      :literal (source/literal var-name)
      :pass    (source/pass var-name :type type :required required))))

(def ^:private gen-fields
  (gen/map gen/keyword gen-field-spec {:max-elements 6}))

(def ^:private gen-env
  (gen/map gen/string-alphanumeric
           (gen/one-of [gen/string-alphanumeric
                        (gen/fmap str gen/small-integer)
                        (gen/elements ["true" "false" "" " "])])
           {:max-elements 6}))

(defspec p2-resolve-config-answers-a-result 100
  (prop/for-all [fields gen-fields
                 env    gen-env]
    (instrumented
     (fn []
       (let [res (resolve/resolve-config fields {}
                                         {:env-fn  #(get env %)
                                          :pass-fn #(get env %)})]
         (and (m/validate c/ResolveResult res)
              (if (r/ok? res)
                (= (set (keys fields)) (set (keys (:ok res))))
                (every? #(contains? fields (:field %)) (:errors res)))))))))

(defspec p3-resolved-defaults-validate-against-generated-schema 100
  (prop/for-all [fields (gen/map gen/keyword
                                 (gen/fmap (fn [[n v]] (source/env n :default v))
                                           (gen/tuple gen/string-alphanumeric
                                                      gen/string-alphanumeric))
                                 {:max-elements 6})]
    (instrumented
     (fn []
       (let [s   (schema/fields->malli-schema fields)
             res (resolve/resolve-config fields {} {:env-fn (constantly nil)})]
         (and (r/ok? res)
              (r/ok? (schema/validate-resolved s (:ok res)))))))))
