(ns hive-di.source
  "Config source definitions — where config values come from.

   Sources are plain data maps describing how to resolve a config field.
   Used inside defconfig declarations:

     (defconfig MyConfig
       :host (env \"HOST\" :default \"localhost\" :type :string)
       :name (literal \"my-app\"))

   Each source fn returns a field spec map consumed by resolve-config."
  (:require [hive-dsl.adt :refer [defadt]]))

;; =============================================================================
;; Source ADT — classifies config value origins
;; =============================================================================

(defadt ConfigSource
  "Where a config value originates."
  [:source/env     {:env-var string?}]
  [:source/literal {:value any?}])

;; =============================================================================
;; Type Inference
;; =============================================================================

(defn infer-type
  "Infer config type keyword from a Clojure value."
  [v]
  (cond
    (string? v)  :string
    (integer? v) :int
    (float? v)   :double
    (boolean? v) :bool
    (keyword? v) :keyword
    (vector? v)  :vec
    :else        :string))

;; =============================================================================
;; Source Constructors — return field spec maps
;; =============================================================================

(defn env
  "Declare an environment variable config source.

   (env \"MILVUS_HOST\" :default \"localhost\" :type :string)

   Options:
     :default  — fallback value when env var is unset/blank
     :type     — coercion target (:string :int :double :bool :keyword :vec :enum)
     :required — whether field must resolve (default true)
     :allowed  — set of allowed values (for :enum type)
     :doc      — human-readable description"
  [var-name & {:keys [default type required allowed doc]
               :or   {type :string required true}}]
  (cond-> {:source   :source/env
           :env-var  var-name
           :type     type
           :required required}
    (some? default) (assoc :default default)
    (some? allowed) (assoc :allowed allowed)
    (some? doc)     (assoc :doc doc)))

(defn literal
  "Declare a literal/constant config value.

   (literal \"hive-mcp-memory\")
   (literal 8080 :type :int)

   Type is inferred from value unless explicitly provided."
  [value & {:keys [type doc]}]
  (cond-> {:source   :source/literal
           :value    value
           :type     (or type (infer-type value))
           :required true}
    (some? doc) (assoc :doc doc)))
