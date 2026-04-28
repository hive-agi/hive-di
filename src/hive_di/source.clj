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
  [:source/env      {:env-var string?}]
  [:source/literal  {:value any?}]
  [:source/file     {:path string? :key-path vector?}]
  [:source/coalesce {:sources vector?}])

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

(defn file
  "Declare an EDN-file config source.

   (file \"~/.config/hive-mcp/secrets.edn\" [:openrouter-api-key]
         :type :string)

   Reads `path` as EDN once per resolve and looks up `key-path`.
   Use for secrets and per-host config overrides.

   Options:
     :default  — fallback when file missing or key absent
     :type     — coercion target
     :required — whether field must resolve (default true)
     :doc      — human-readable description"
  [path key-path & {:keys [default type required doc]
                    :or   {type :string required true}}]
  (cond-> {:source   :source/file
           :path     path
           :key-path (vec key-path)
           :type     type
           :required required}
    (some? default) (assoc :default default)
    (some? doc)     (assoc :doc doc)))

(defn coalesce
  "Chain multiple sources; first non-nil resolved value wins.

   (coalesce [(env \"HIVE_KG_DB_PATH\")
              (file \"~/.config/hive-mcp/config.edn\" [:services :datahike :path])]
             :default \"data/kg/datahike\"
             :type :string
             :doc \"Datahike file-store path\")

   Each child source is dispatched independently via the resolver's env-fn
   and file-fn (no per-child :default coercion — only the outer :default).
   Use when one logical field has multiple legitimate origins (env override
   for ops, config.edn for canonical, hardcoded default for fresh installs).

   Options mirror env/file: :default :type :required :doc.
   Children must be source maps (env/literal/file). Nested coalesce is
   supported but discouraged — flatten when possible."
  [sources & {:keys [default type required doc]
              :or   {type :string required true}}]
  (cond-> {:source   :source/coalesce
           :sources  (vec sources)
           :type     type
           :required required}
    (some? default) (assoc :default default)
    (some? doc)     (assoc :doc doc)))
