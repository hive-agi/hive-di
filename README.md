# hive-di

Declarative, typed, ADT-backed config resolution for Clojure.

Part of the [hive-agi](https://github.com/hive-agi) ecosystem.

## Why

Config resolution across addon systems is ad-hoc: string replacement, manual type parsing, scattered defaults. `hive-di` replaces all of that with a single `defconfig` macro that generates typed config, validation, and tests.

## Usage

```clojure
;; deps.edn
io.github.hive-agi/hive-di {:git/tag "v0.1.0" :git/sha "..."}
```

### Define config

```clojure
(ns my-app.config
  (:require [hive-di.core :refer [defconfig env literal]]))

(defconfig MilvusConfig
  :host            (env "MILVUS_HOST" :default "localhost" :type :string)
  :port            (env "MILVUS_PORT" :default 19530 :type :int)
  :secure          (env "MILVUS_SECURE" :default false :type :bool)
  :collection-name (literal "hive-mcp-memory"))
```

### Resolve

```clojure
;; From environment + defaults
(resolve-MilvusConfig)
;; => {:ok {:host "localhost" :port 19530 :secure false :collection-name "hive-mcp-memory"}}

;; With overrides (e.g., from addon manifest)
(resolve-MilvusConfig {:host "milvus.svc" :port "9091"})
;; => {:ok {:host "milvus.svc" :port 9091 :secure false :collection-name "hive-mcp-memory"}}

;; With mock env (testing)
(resolve-MilvusConfig {} {:env-fn {"MILVUS_HOST" "prod.host"}})
```

### What `defconfig` generates

| Artifact | Description |
|----------|-------------|
| `MilvusConfig` | ADT with `:config/resolved`, `:config/unresolved`, `:config/invalid` variants |
| `MilvusConfig-fields` | Field registry map (source of truth for resolution) |
| `MilvusConfig-schema` | Malli schema (closed map, derived from field types) |
| `resolve-MilvusConfig` | Resolver fn (0/1/2 arity) returning `Result` |

### Auto-generated tests

```clojure
(ns my-app.config-test
  (:require [hive-di.testing :refer [defconfig-tests]]
            [my-app.config :refer [MilvusConfig MilvusConfig-fields]]))

(defconfig-tests MilvusConfig MilvusConfig-fields :num-tests 100)
;; Generates:
;;   MilvusConfig-totality       — resolver never throws for any input
;;   MilvusConfig-defaults-only  — defaults alone produce valid config
;;   MilvusConfig-roundtrip      — resolved config survives EDN serialization
;;   MilvusConfig-field-mutations — removing each default is handled gracefully
```

## Design

- **ADT-backed**: Config lifecycle modeled as sum type (resolved/unresolved/invalid)
- **All errors collected**: No short-circuit. Operators see full picture in one pass
- **`blank->nil`**: `VAR=""` triggers default, not silent empty string
- **Pre-typed defaults skip coercion**: `19530` stays int, no string round-trip
- **Injectable `env-fn`**: Zero mocking frameworks needed for tests
- **Malli optional**: Core depends only on `hive-dsl`. Schema validation is opt-in

## Dependencies

- [hive-dsl](https://github.com/hive-agi/hive-dsl) — defadt, Result monad, coerce
- [hive-test](https://github.com/hive-agi/hive-test) (test-only) — property macros, mutation testing, golden snapshots

## License

AGPL-3.0-or-later
