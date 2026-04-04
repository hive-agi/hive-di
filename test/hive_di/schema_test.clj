(ns hive-di.schema-test
  "Tests for Malli schema generation."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-di.schema :as schema]
            [hive-di.source :as source]
            [hive-dsl.result :as r]))

(def test-fields
  {:host  (source/env "HOST" :default "localhost" :type :string)
   :port  (source/env "PORT" :default 8080 :type :int)
   :debug (source/env "DEBUG" :default false :type :bool)})

(deftest generates-closed-map-schema
  (let [s (schema/fields->malli-schema test-fields)]
    (is (vector? s))
    (is (= :map (first s)))
    (is (= {:closed true} (second s)))))

(deftest maps-types-correctly
  (testing "type->malli mappings"
    (is (= :string  (schema/type->malli :string)))
    (is (= :int     (schema/type->malli :int)))
    (is (= :double  (schema/type->malli :double)))
    (is (= :boolean (schema/type->malli :bool)))
    (is (= :keyword (schema/type->malli :keyword)))
    (is (= [:vector :any] (schema/type->malli :vec)))))

(deftest schema-contains-all-fields
  (let [s (schema/fields->malli-schema test-fields)
        field-entries (drop 2 s) ;; skip [:map {:closed true}]
        field-names (set (map first field-entries))]
    (is (contains? field-names :host))
    (is (contains? field-names :port))
    (is (contains? field-names :debug))))

(deftest optional-fields-wrapped-in-maybe
  (let [fields {:nick (source/env "NICK" :type :string :required false)}
        s (schema/fields->malli-schema fields)
        field-entries (drop 2 s)
        nick-entry (first (filter #(= :nick (first %)) field-entries))]
    (is (some? nick-entry))
    ;; Optional field has {:optional true} metadata and [:maybe :string] type
    (is (= {:optional true} (second nick-entry)))
    (is (= [:maybe :string] (nth nick-entry 2)))))

(deftest validate-resolved-with-valid-data
  (let [s (schema/fields->malli-schema test-fields)
        result (schema/validate-resolved s {:host "localhost" :port 8080 :debug false})]
    (is (r/ok? result))))

(deftest validate-resolved-with-invalid-data
  (let [s (schema/fields->malli-schema test-fields)
        result (schema/validate-resolved s {:host 123 :port "not-int" :debug "nope"})]
    (is (r/err? result))
    (is (= :config/validation-failed (:error result)))))
