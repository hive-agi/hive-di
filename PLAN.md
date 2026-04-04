# hive-di Architecture Plan — Config DI Library

> Status: PROPOSED | Author: ling-hive-di-saa | Date: 2026-04-04
> Pending: memory storage (nREPL was down during SAA)

## NIH Audit

| Check | Result |
|-------|--------|
| **Memory Search** | No existing config DI library in hive ecosystem |
| **Codebase Search** | `hive-dsl.coerce` (reusable), `manifest.clj/expand-env-vars` (replaced), `config/core.clj` deep-merge (subsumed) |
| **External Libraries** | aero (no ADT/Result/test-gen), environ (env access only), cprop (no validation) |
| **Decision** | **BUILD** — no library provides defadt-backed config + Result errors + auto-test generation + hive-dsl.coerce reuse |

---

## 1. Project Structure

```
hive-di/
├── deps.edn
├── src/hive_di/
│   ├── core.clj          # defconfig macro, public API (env, literal, resolve-config)
│   ├── resolve.clj       # Pure resolution engine: source lookup → coerce → Result
│   ├── source.clj        # Source ADT: :source/env, :source/literal, :source/file, :source/ref
│   ├── schema.clj        # Malli schema generation from field registry
│   └── testing.clj       # defconfig-tests macro: property, mutation, golden generators
└── test/hive_di/
    ├── core_test.clj
    ├── resolve_test.clj
    ├── schema_test.clj
    └── testing_test.clj
```

---

## 2. defconfig Macro Expansion

### Input

```clojure
(ns hive-milvus.config
  (:require [hive-di.core :refer [defconfig env literal]]))

(defconfig MilvusConfig
  :host            (env "MILVUS_HOST" :default "localhost" :type :string)
  :port            (env "MILVUS_PORT" :default 19530 :type :int)
  :secure          (env "MILVUS_SECURE" :default false :type :bool)
  :collection-name (literal "hive-mcp-memory"))
```

### Generated Artifacts

**(a) Config ADT** (via hive-dsl/defadt):

```clojure
(defadt MilvusConfig
  "Auto-generated config ADT."
  [:config/resolved   {:host string? :port integer? :secure boolean? :collection-name string?}]
  [:config/unresolved {:fields set? :partial map?}]
  [:config/invalid    {:errors vector? :partial map?}])
```

Three variants model config lifecycle:
- `:config/resolved` — all fields present and typed. Happy path.
- `:config/unresolved` — required fields missing (no default, no env). Carries `:fields` (missing keywords) + `:partial` (what resolved).
- `:config/invalid` — type coercion or validation failed. Carries `:errors` vector + `:partial` (pre-coercion values).

**(b) Field Registry** (def):

```clojure
(def MilvusConfig-fields
  {:host            {:source :source/env :env-var "MILVUS_HOST" :default "localhost" :type :string :required true}
   :port            {:source :source/env :env-var "MILVUS_PORT" :default 19530     :type :int    :required true}
   :secure          {:source :source/env :env-var "MILVUS_SECURE" :default false   :type :bool   :required true}
   :collection-name {:source :source/literal :value "hive-mcp-memory"              :type :string :required true}})
```

**(c) Malli Schema** (def):

```clojure
(def MilvusConfig-schema
  [:map {:closed true}
   [:host :string]
   [:port :int]
   [:secure :boolean]
   [:collection-name :string]])
```

**(d) Resolver Shorthand** (defn):

```clojure
(defn resolve-MilvusConfig
  ([] (resolve-config MilvusConfig-fields))
  ([overrides] (resolve-config MilvusConfig-fields overrides))
  ([overrides opts] (resolve-config MilvusConfig-fields overrides opts)))
```

---

## 3. Source ADT

```clojure
(defadt ConfigSource
  "Where a config value comes from."
  [:source/env     {:env-var string?}]
  [:source/literal {:value any?}]
  [:source/file    {:path string? :format keyword?}]          ;; future
  [:source/ref     {:config-name keyword? :field keyword?}])  ;; future
```

Source constructors (used inside defconfig):

```clojure
(defn env
  "Declare env-var source. Options: :default, :type, :required, :doc."
  [var-name & {:keys [default type required doc]
               :or {type :string required true}}]
  {:source :source/env :env-var var-name :default default
   :type type :required required :doc doc})

(defn literal
  "Declare literal/constant source. Type inferred from value."
  [value & {:keys [type doc]}]
  {:source :source/literal :value value
   :type (or type (infer-type value)) :doc doc})
```

---

## 4. resolve-config — Pure Resolution Engine

Located in `hive-di.resolve`.

### Signature

```clojure
(defn resolve-config
  "Resolve all fields from sources. Returns Result.

   fields:    field registry map (from defconfig expansion)
   overrides: explicit values that bypass source lookup
   opts:      {:env-fn    (fn [var-name] string-or-nil)  ;; default: System/getenv
               :coerce?   true                            ;; default: true
               :validate? true}                           ;; Malli post-validation

   Returns:
     (ok {:adt/type :ConfigName :adt/variant :config/resolved ...})
     (err :config/resolution-failed {:errors [...] :partial {...}})"
  [{:keys [fields overrides opts]
    :or {overrides {} opts {}}}]
  ...)
```

### Resolution Algorithm (per field)

```
1. Check overrides[field] → if present, use as raw-value
2. Else dispatch on :source:
   :source/env     → (env-fn env-var) → raw-value (nil if unset)
   :source/literal → :value            → raw-value
3. Apply blank->nil: empty strings → nil
4. If raw-value is nil:
   a. If :default exists → use default (already typed, SKIP coercion)
   b. If :required → collect {:field f :error :config/missing-required}
   c. Else → nil (optional field)
5. If raw-value is string and :type != :string:
   Apply hive-dsl.coerce/{->int,->boolean,...}
   On err → collect {:field f :error :coerce/invalid-X :value raw}
6. Accumulate resolved values into map
```

### Error Collection (no short-circuit)

ALL fields attempted. Errors collected in vector. If errors → `(err :config/resolution-failed {:errors errors :partial partial-map})`. If none → `(ok resolved-map)`.

This contrasts with `let-ok` (short-circuits). Config resolution must report ALL problems so operators fix everything in one pass.

### Empty String Fix

```clojure
(defn- blank->nil [v]
  (if (and (string? v) (clojure.string/blank? v)) nil v))
```

Fixes systemic bug: `MILVUS_HOST=""` → nil → triggers default. Previously: empty string silently used as host.

### Coercion Dispatch (delegates to hive-dsl.coerce)

| :type | Coercion fn | Notes |
|-------|-------------|-------|
| :string | identity | Pass-through |
| :int | coerce/->int | Result-returning |
| :double | coerce/->double | Result-returning |
| :bool | coerce/->boolean | Handles "true"/"false"/"1"/"0"/"yes"/"no" |
| :keyword | coerce/->keyword | Blank → nil |
| :vec | coerce/->vec | JSON parse |
| :enum | coerce/->enum | Requires :allowed set in field spec |

---

## 5. Schema Generation (hive-di.schema)

```clojure
(defn fields->malli-schema
  "Generate Malli schema from defconfig field registry."
  [fields]
  (into [:map {:closed true}]
        (map (fn [[field-kw {:keys [type required] :or {required true}}]]
               (let [malli-type (type->malli type)]
                 [field-kw (if required malli-type [:maybe malli-type])])))
        fields))

(defn- type->malli [type-kw]
  (case type-kw
    :string  :string
    :int     :int
    :double  :double
    :bool    :boolean
    :keyword :keyword
    :vec     [:vector :any]
    :enum    :keyword
    :any))
```

Malli validation is a second pass, opt-in:

```clojure
(defn validate-resolved [schema resolved-map]
  (if (m/validate schema resolved-map)
    (r/ok resolved-map)
    (r/err :config/validation-failed
           {:errors (me/humanize (m/explain schema resolved-map))})))
```

---

## 6. Testing DSL (hive-di.testing)

### defconfig-tests Macro

```clojure
(defmacro defconfig-tests
  "Generate comprehensive tests for a defconfig definition.

   (defconfig-tests MilvusConfig :num-tests 100)

   Generates:
   1. defprop-total    — resolver never throws for any env state
   2. defprop-roundtrip — resolved → EDN → back preserves value
   3. deftest-golden    — default resolution (no env) is stable
   4. deftest-mutations — per-field default removal still returns Result"
  [config-name & {:keys [num-tests] :or {num-tests 100}}]
  ...)
```

### Generated Tests

**(a) Totality** (defprop-total from hive-test):

```clojure
(defprop-total resolve-MilvusConfig-total
  resolve-MilvusConfig
  (gen-config-overrides MilvusConfig-fields)
  {:num-tests 100})
```

Generator: for each field, randomly choose: present (valid type), present (wrong type), missing, empty string. Exercises all branches.

**(b) Roundtrip** (defprop-roundtrip from hive-test):

```clojure
(defprop-roundtrip MilvusConfig-roundtrip
  pr-str
  (comp clojure.edn/read-string)
  gen-resolved-MilvusConfig
  {:num-tests 100})
```

**(c) Golden Baseline** (deftest-golden from hive-test):

```clojure
(deftest-golden MilvusConfig-defaults
  "test/golden/milvus-config-defaults.edn"
  (let [result (resolve-MilvusConfig {} {:env-fn (constantly nil)})]
    (when (r/ok? result) (:ok result))))
```

**(d) Mutation Witnesses** (deftest-mutations from hive-test):

```clojure
(deftest-mutations MilvusConfig-field-mutations
  #'resolve-MilvusConfig
  [["remove-host-default"    (fn [_] (resolve-with-field-removed :host))]
   ["remove-port-default"    (fn [_] (resolve-with-field-removed :port))]
   ...]
  (fn [] (is (r/ok? (resolve-MilvusConfig)))))
```

**(e) Override Generator** (auto-generated per config):

```clojure
(defn gen-config-overrides [fields]
  (gen/let [field-subset (gen/set (gen/elements (keys fields)))
            values (gen/map (gen/elements (keys fields))
                           (gen/one-of [gen/string-alphanumeric
                                        gen/small-integer
                                        (gen/return nil)
                                        (gen/return "")]))]
    (select-keys values field-subset)))
```

---

## 7. Integration Path

### Phase 1: hive-milvus (first consumer)

**Before** (addon.clj):
```clojure
(let [resolved (-> config
                   (update :host #(if (seq %) % "localhost"))
                   (update :port #(if (and % (not= % ""))
                                    (if (string? %) (parse-long %) %) 19530))
                   (update :collection-name #(if (seq %) % "hive-mcp-memory")))]
  ...)
```

**After**:
```clojure
(ns hive-milvus.config
  (:require [hive-di.core :refer [defconfig env literal]]
            [hive-dsl.result :as r]))

(defconfig MilvusConfig
  :host            (env "MILVUS_HOST" :default "localhost" :type :string)
  :port            (env "MILVUS_PORT" :default 19530 :type :int)
  :secure          (env "MILVUS_SECURE" :default false :type :bool)
  :collection-name (literal "hive-mcp-memory"))

;; In initialize!:
(r/let-ok [config (resolve-MilvusConfig addon-config)]
  (store/create-store (select-keys config [:host :port :collection-name :token :database :secure])))
```

**Fixes**: `:secure` coerced to bool. Port validated as int. Empty strings → defaults. All errors collected.

### Phase 2: hive-mcp manifest.clj

Replace `expand-env-vars` with per-addon defconfig declarations:

```clojure
(defn prepare-config [manifest]
  (let [addon-type (:addon/type manifest)
        config (:addon/config manifest)]
    (case addon-type
      :milvus (resolve-MilvusConfig config)
      :chroma (resolve-ChromaConfig config)
      (resolve-generic-config config))))  ;; fallback for unknown addons
```

### Phase 3: Future (not v0.1)

Global config integration (config/core.clj deep-merge pattern) requires nested config support.

---

## 8. deps.edn

```clojure
{:paths ["src"]
 :deps {org.clojure/clojure         {:mvn/version "1.12.1"}
        io.github.hive-agi/hive-dsl {:mvn/version "0.3.7"}}

 :aliases
 {:test
  {:extra-paths ["test"]
   :extra-deps {io.github.hive-agi/hive-test {:mvn/version "0.1.4"}
                org.clojure/test.check       {:mvn/version "1.1.1"}
                metosin/malli                {:mvn/version "0.20.0"}
                lambdaisland/kaocha          {:mvn/version "1.91.1392"}}
   :main-opts ["-m" "kaocha.runner"]}
  :dev
  {:extra-paths ["dev"]}}}
```

**Malli is TEST-only**. Core resolution depends only on hive-dsl (coerce + result + adt). Minimal runtime footprint.

---

## 9. Key Design Decisions

| ID | Decision | Rationale |
|----|----------|-----------|
| D1 | ADT variants model config **lifecycle** (resolved/unresolved/invalid), not config types | `adt-case` answers "is this config ready?" — the critical question |
| D2 | `env-fn` injection for testability | Tests inject `(constantly nil)` or mock maps. Zero mocking frameworks. |
| D3 | Defaults are pre-typed, skip coercion | `19530` is already int — no string→int applied. Avoids double-coercion. |
| D4 | All errors collected, not short-circuited | Operators see "port invalid AND host missing" in one pass. |
| D5 | Malli optional, coerce mandatory | Core has zero Malli dependency. Schema validation is second pass. |
| D6 | `blank->nil` universal | Fixes systemic bug: `VAR=""` → nil → default. |

---

## 10. Future Extensions (not v0.1)

- `:source/file` — read from EDN/JSON/YAML
- `:source/ref` — cross-config references
- Nested configs — `(defconfig Outer :inner (ref InnerConfig))`
- Config watching — re-resolve on env change
- Config diff — compare resolved configs for drift
- Secret sources — integrate with hive-mcp secrets module
