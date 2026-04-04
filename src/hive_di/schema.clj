(ns hive-di.schema
  "Malli schema generation from defconfig field registries.

   Generates closed map schemas from field specs, enabling:
   - Post-resolution validation
   - Test data generation
   - Documentation

   Malli is an optional dependency — this ns is used by testing.clj
   and optionally at runtime for stricter validation."
  (:require [hive-dsl.result :as r]))

;; =============================================================================
;; Type → Malli Mapping
;; =============================================================================

(defn type->malli
  "Map a hive-di type keyword to a Malli schema."
  [type-kw]
  (case type-kw
    :string  :string
    :int     :int
    :double  :double
    :bool    :boolean
    :keyword :keyword
    :vec     [:vector :any]
    :enum    :keyword
    :any))

;; =============================================================================
;; Schema Generation
;; =============================================================================

(defn fields->malli-schema
  "Generate a closed Malli map schema from a defconfig field registry.

   (fields->malli-schema
     {:host {:type :string :required true}
      :port {:type :int :required true}})
   ;; => [:map {:closed true} [:host :string] [:port :int]]"
  [fields]
  (into [:map {:closed true}]
        (map (fn [[field-kw {:keys [type required] :or {required true}}]]
               (let [malli-type (type->malli type)]
                 (if required
                   [field-kw malli-type]
                   [field-kw {:optional true} [:maybe malli-type]]))))
        fields))

;; =============================================================================
;; Validation (requires Malli at runtime — optional)
;; =============================================================================

(defn validate-resolved
  "Validate a resolved config map against a Malli schema.
   Returns Result.

   Requires metosin/malli on classpath. Fails gracefully if absent."
  [schema resolved-map]
  (try
    (let [validate (requiring-resolve 'malli.core/validate)
          explain  (requiring-resolve 'malli.core/explain)
          humanize (requiring-resolve 'malli.error/humanize)]
      (if (validate schema resolved-map)
        (r/ok resolved-map)
        (r/err :config/validation-failed
               {:errors (humanize (explain schema resolved-map))})))
    (catch Exception e
      (r/err :config/validation-unavailable
             {:message (str "Malli not on classpath: " (.getMessage e))}))))
