(ns hive-di.contracts
  "Malli function contracts (m/=>) for hive-di's public config ports.

   Lives on the non-runtime `contracts/` path, never on :paths: hive-di's core
   carries no malli dependency (PLAN.md D5, README \"Malli optional\"), so no
   namespace under src/ may require malli. This namespace is loaded by the
   test and dev aliases; it registers the contracts against the src vars by
   qualified symbol, so instrumentation and the contract-coverage facet see
   them while the shipped jar stays malli-free.

   Covered: hive-di.source (field-spec constructors), hive-di.resolve (the
   resolution engine), hive-di.schema (malli projection of a field registry)
   and hive-di.pass (pass-store reads)."
  (:require [hive-di.pass]
            [hive-di.resolve]
            [hive-di.schema]
            [hive-di.source]
            [malli.core :as m]
            [hive-di.core]))

;; =============================================================================
;; Value objects
;; =============================================================================

(def FieldType
  "A declared coercion target. Open: resolve passes an unknown type through."
  :keyword)

(def InferredType
  "What source/infer-type can answer — a closed set."
  [:enum :string :int :double :bool :keyword :vec])

(def KwArgs
  "Trailing keyword options, e.g. :default 1 :type :int."
  [:* [:cat :keyword :any]])

(def Doc [:doc {:optional true} :string])
(def Default [:default {:optional true} :any])

(def EnvSpec
  [:map
   [:source [:= :source/env]]
   [:env-var :string]
   [:type FieldType]
   [:required :boolean]
   Default
   [:allowed {:optional true} :any]
   Doc])

(def LiteralSpec
  [:map
   [:source [:= :source/literal]]
   [:value :any]
   [:type FieldType]
   [:required [:= true]]
   Doc])

(def FileSpec
  [:map
   [:source [:= :source/file]]
   [:path :string]
   [:key-path [:vector :any]]
   [:type FieldType]
   [:required :boolean]
   Default
   Doc])

(def PassSpec
  [:map
   [:source [:= :source/pass]]
   [:path :string]
   [:type FieldType]
   [:required :boolean]
   Default
   Doc])

(def ChildSpec
  "A coalesce child: any source map. Kept shallow (no recursion) because
   nested coalesce is legal and the resolver treats every child alike."
  [:map [:source :keyword]])

(def CoalesceSpec
  [:map
   [:source [:= :source/coalesce]]
   [:sources [:vector ChildSpec]]
   [:type FieldType]
   [:required :boolean]
   Default
   Doc])

(def FieldSpec
  "Any field spec a source constructor yields."
  [:multi {:dispatch :source}
   [:source/env      EnvSpec]
   [:source/literal  LiteralSpec]
   [:source/file     FileSpec]
   [:source/pass     PassSpec]
   [:source/coalesce CoalesceSpec]])

(def Fields
  "A field registry {field-kw -> field-spec}. The resolver dispatches on
   :source and answers nil for an unknown one, so the value is any map."
  [:maybe [:map-of :keyword :map]])

(def Overrides [:maybe [:map-of :keyword :any]])

(def ResolveOpts
  [:maybe
   [:map
    [:env-fn  {:optional true} [:maybe ifn?]]
    [:file-fn {:optional true} [:maybe ifn?]]
    [:pass-fn {:optional true} [:maybe ifn?]]]])

(def ResolveResult
  "Ok of the typed config map, or the collected resolution failure."
  [:or
   [:map {:closed true} [:ok [:map-of :keyword :any]]]
   [:map
    [:error [:= :config/resolution-failed]]
    [:errors [:vector [:map [:field :keyword] [:category :keyword]]]]
    [:partial [:map-of :keyword :any]]]])

(def MalliType
  "What schema/type->malli projects a field type to."
  [:or [:enum :string :int :double :boolean :keyword :any]
   [:= [:vector :any]]])

(def ConfigSchema
  "A closed malli map schema: [:map {:closed true} & entries], each entry
   [k type] (required) or [k {:optional true} [:maybe type]] (optional).
   `:=` would read a literal map as its own properties, so the property maps
   are checked by predicate."
  [:cat [:= :map] [:fn #(= {:closed true} %)]
   [:* [:or [:tuple :keyword MalliType]
        [:tuple :keyword [:fn #(= {:optional true} %)]
         [:tuple [:= :maybe] MalliType]]]]])

(def ValidateResult
  [:or
   [:map {:closed true} [:ok :any]]
   [:map [:error [:enum :config/validation-failed
                  :config/validation-unavailable]]]])

(def ConfigSourceValue
  "A ConfigSource ADT value as hive-dsl.adt builds it."
  [:map
   [:adt/type [:= :ConfigSource]]
   [:adt/variant [:enum :source/env :source/literal :source/file
                  :source/pass :source/coalesce]]])

;; hive-di.core re-exports — the defconfig-facing entry point most callers
;; require. Same contracts as the fns they alias.
(m/=> hive-di.core/env [:=> [:cat :string KwArgs] EnvSpec])
(m/=> hive-di.core/literal [:=> [:cat :any KwArgs] LiteralSpec])
(m/=> hive-di.core/file [:=> [:cat :string [:sequential :any] KwArgs] FileSpec])
(m/=> hive-di.core/coalesce [:=> [:cat [:maybe [:sequential ChildSpec]] KwArgs]
                             CoalesceSpec])
(m/=> hive-di.core/resolve-config
      [:function
       [:=> [:cat Fields] ResolveResult]
       [:=> [:cat Fields Overrides] ResolveResult]
       [:=> [:cat Fields Overrides ResolveOpts] ResolveResult]])

;; The three fns `defadt ConfigSource` emits into hive-di.source. The variant
;; constructors answer nil for an unknown variant rather than throwing.
(m/=> hive-di.source/config-source? [:=> [:cat :any] :boolean])
(m/=> hive-di.source/->config-source [:=> [:cat :any] [:maybe ConfigSourceValue]])
(m/=> hive-di.source/config-source
      [:function
       [:=> [:cat :any] [:maybe ConfigSourceValue]]
       [:=> [:cat :any [:maybe :map]] [:maybe ConfigSourceValue]]])

;; =============================================================================
;; hive-di.source
;; =============================================================================

(m/=> hive-di.source/infer-type [:=> [:cat :any] InferredType])
(m/=> hive-di.source/env [:=> [:cat :string KwArgs] EnvSpec])
(m/=> hive-di.source/literal [:=> [:cat :any KwArgs] LiteralSpec])
(m/=> hive-di.source/file [:=> [:cat :string [:sequential :any] KwArgs] FileSpec])
(m/=> hive-di.source/pass [:=> [:cat :string KwArgs] PassSpec])
(m/=> hive-di.source/coalesce [:=> [:cat [:maybe [:sequential ChildSpec]] KwArgs]
                               CoalesceSpec])

;; =============================================================================
;; hive-di.resolve
;; =============================================================================

(m/=> hive-di.resolve/resolve-config
      [:function
       [:=> [:cat Fields] ResolveResult]
       [:=> [:cat Fields Overrides] ResolveResult]
       [:=> [:cat Fields Overrides ResolveOpts] ResolveResult]])

;; =============================================================================
;; hive-di.schema
;; =============================================================================

(m/=> hive-di.schema/type->malli [:=> [:cat :any] MalliType])
(m/=> hive-di.schema/fields->malli-schema [:=> [:cat Fields] ConfigSchema])
(m/=> hive-di.schema/validate-resolved [:=> [:cat :any :any] ValidateResult])

;; =============================================================================
;; hive-di.pass
;; =============================================================================

(m/=> hive-di.pass/show [:=> [:cat :any] [:maybe [:string {:min 1}]]])
(m/=> hive-di.pass/present? [:=> [:cat :any] :boolean])
