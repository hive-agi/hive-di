(ns hive-di.resolve
  "Pure config resolution engine.

   Resolves field registries (from defconfig) into typed config maps.
   Core design: pure function with injectable env-fn for testability.

   Resolution per field:
     1. Check overrides map
     2. Dispatch on source (:source/env → getenv, :source/literal → value)
     3. blank->nil (empty strings become nil)
     4. Default fallback (pre-typed, skip coercion)
     5. Type coercion via hive-dsl.coerce

   ALL errors collected — no short-circuit. Operators see full picture."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-dsl.coerce :as coerce]))

;; =============================================================================
;; Utilities
;; =============================================================================

(defn- blank->nil
  "Convert blank/empty strings to nil.
   Fixes the systemic bug where VAR=\"\" silently passes through."
  [v]
  (if (and (string? v) (str/blank? v))
    nil
    v))

(defn- coerce-value
  "Coerce a raw string value to the declared type.
   Delegates to hive-dsl.coerce — all fns return Result."
  [v type-kw field-spec]
  (case type-kw
    :string  (r/ok v)
    :int     (coerce/->int v)
    :double  (coerce/->double v)
    :bool    (coerce/->boolean v)
    :keyword (coerce/->keyword v)
    :vec     (coerce/->vec v)
    :enum    (if-let [allowed (:allowed field-spec)]
               (coerce/->enum v allowed)
               (r/err :config/missing-allowed-set
                      {:message (str "Enum field requires :allowed set")
                       :value v}))
    ;; Unknown type — pass through with warning
    (r/ok v)))

(defn- needs-coercion?
  "Determine if a value needs type coercion.
   Pre-typed defaults and values already matching target type skip coercion."
  [v type-kw]
  (and (string? v)
       (not= type-kw :string)))

;; =============================================================================
;; Per-Field Resolution
;; =============================================================================

(defn- resolve-field
  "Resolve a single config field. Returns {:ok value} or {:error ...}.

   Strategy:
   1. Override wins (caller-provided explicit value)
   2. Source dispatch (env lookup or literal)
   3. blank->nil normalization
   4. Default fallback (pre-typed, skips coercion)
   5. Type coercion for string values"
  [field-kw field-spec overrides env-fn]
  (let [;; Step 1: Check overrides
        override-val (get overrides field-kw ::not-found)

        ;; Step 2: Source dispatch
        raw-value (if (not= override-val ::not-found)
                    override-val
                    (case (:source field-spec)
                      :source/env     (env-fn (:env-var field-spec))
                      :source/literal (:value field-spec)
                      nil))

        ;; Step 3: Normalize blanks
        normalized (blank->nil raw-value)]

    (cond
      ;; Value is nil — check for default or required
      (nil? normalized)
      (if (contains? field-spec :default)
        ;; Default is pre-typed — return directly, no coercion
        (r/ok (:default field-spec))
        ;; No default — is it required?
        (if (:required field-spec)
          (r/err :config/missing-required
                 {:field   field-kw
                  :source  (:source field-spec)
                  :env-var (:env-var field-spec)
                  :message (str "Required config field " (name field-kw)
                                " has no value and no default"
                                (when-let [ev (:env-var field-spec)]
                                  (str " (env: " ev ")")))})
          ;; Optional, nil is ok
          (r/ok nil)))

      ;; Value present — coerce if needed
      (needs-coercion? normalized (:type field-spec))
      (let [result (coerce-value normalized (:type field-spec) field-spec)]
        (if (r/ok? result)
          result
          ;; Enrich error with field context
          (r/err (:error result)
                 (merge (dissoc result :error)
                        {:field  field-kw
                         :source (:source field-spec)}))))

      ;; Already correct type (override was typed, or string field)
      :else
      (r/ok normalized))))

;; =============================================================================
;; Config Resolution — Main Entry Point
;; =============================================================================

(defn resolve-config
  "Resolve all fields from their sources. Returns Result.

   Arguments:
     fields    — field registry map {field-kw → field-spec} (from defconfig)
     overrides — explicit values bypassing source lookup (e.g., addon config map)
     opts      — {:env-fn (fn [var-name] string-or-nil)}

   Returns:
     (ok {:host \"localhost\" :port 19530 ...})
     (err :config/resolution-failed {:errors [...] :partial {...}})

   ALL fields attempted — errors collected, not short-circuited."
  ([fields]
   (resolve-config fields {}))
  ([fields overrides]
   (resolve-config fields overrides {}))
  ([fields overrides opts]
   (let [env-fn (or (:env-fn opts) #(System/getenv %))
         ;; Resolve every field, collecting results
         results (reduce-kv
                   (fn [acc field-kw field-spec]
                     (let [result (resolve-field field-kw field-spec overrides env-fn)]
                       (if (r/ok? result)
                         (-> acc
                             (update :resolved assoc field-kw (:ok result)))
                         (-> acc
                             (update :errors conj (merge {:field field-kw}
                                                         (dissoc result :error)
                                                         {:category (:error result)}))
                             (update :partial assoc field-kw (get overrides field-kw))))))
                   {:resolved {} :errors [] :partial {}}
                   fields)]

     (if (seq (:errors results))
       (r/err :config/resolution-failed
              {:errors  (:errors results)
               :partial (:partial results)})
       (r/ok (:resolved results))))))
