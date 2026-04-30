(ns hive-di.addon
  "Manifest-driven addon loader — declarative replacement for the bespoke
   `init!` plumbing each hive-* project currently rolls by hand.

   Three concerns, one shape:
     1. lifecycle-addons : namespaces required for side-effectful self-register
     2. extensions       : symbol → registry-key resolutions, registered en masse
     3. bridges          : protocol-default swaps (set-default-* style installs)
     4. headless         : optional headless-backend registration (hive-mcp)

   Every step uses hive-dsl Result railway for graceful degradation —
   failures are logged but never propagate.

   Usage:
     (require '[hive-di.addon :as addon])
     (addon/defmanifest hive-agent-manifest
       {:lifecycle-addons #{'hive-agent.lifecycle.resources}
        :extensions [{:ns 'hive-agent.loop.core
                      :fns [[\"run-agent\" :ag/run]]}]
        :bridges    [{:ns 'hive-agent.web.event-emitter
                      :install 'hive-agent.web.event-emitter/install!}]
        :registry-target 'hive-mcp.extensions.registry/register-many!})

   The macro defs a `run!` fn alongside the manifest var; callers invoke
   `(my-project.init/run!)` at boot."
  (:require [hive-dsl.result :as r]))

;; Minimal log shim — println-based. Keeps hive-di leaf-clean.
;; Downstream projects with timbre can replace via alter-var-root if needed.
(defn- log-info  [& xs] (apply println "[addon INFO] " xs))
(defn- log-debug [& xs] (when (System/getenv "HIVE_DEBUG")
                          (apply println "[addon DEBUG]" xs)))
(defn- log-warn  [& xs] (apply println "[addon WARN] " xs))

;; =============================================================================
;; Resolution helpers (Result-aware)
;; =============================================================================

(defn try-resolve
  "Resolve a fully-qualified symbol or return nil. Never throws."
  [sym]
  (r/rescue nil (requiring-resolve sym)))

(defn- require-ns
  "Require an ns-symbol. Returns Result."
  [ns-sym]
  (r/try-effect* :addon/load-failed (require ns-sym)))

;; =============================================================================
;; Step 1: lifecycle-addons (side-effectful requires)
;; =============================================================================

(defn load-lifecycle!
  "Require each ns in `nses` for its load-time side effects.
   Returns {:loaded [...] :failed [...]}."
  [project-id nses]
  (reduce
   (fn [acc ns-sym]
     (let [result (require-ns ns-sym)]
       (if (r/ok? result)
         (do (log-debug project-id "lifecycle addon loaded:" ns-sym)
             (update acc :loaded conj ns-sym))
         (do (log-warn project-id "lifecycle addon failed:" ns-sym (:message result))
             (update acc :failed conj ns-sym)))))
   {:loaded [] :failed []}
   nses))

;; =============================================================================
;; Step 2: extensions (resolve fns, register en masse)
;; =============================================================================

(defn- resolve-group
  "Resolve a manifest group ({:ns ... :fns [[name reg-key] ...]}).
   Returns {<reg-key> <var>} for symbols that resolved."
  [{:keys [ns fns]}]
  (reduce
   (fn [m [fn-name reg-key]]
     (if-let [f (try-resolve (symbol (str ns) fn-name))]
       (assoc m reg-key f)
       (do (log-debug "extension resolve failed:" ns fn-name "→" reg-key)
           m)))
   {}
   fns))

(defn register-extensions!
  "Resolve all manifest extension groups, then register them via
   `registry-target` if available. Returns
   {:registered <vec keys> :groups {<ns> <count>}}."
  [project-id manifest]
  (let [{:keys [extensions registry-target]} manifest
        {:keys [resolved counts]}
        (reduce
         (fn [{:keys [resolved counts]} group]
           (let [g (resolve-group group)]
             {:resolved (merge resolved g)
              :counts   (assoc counts (:ns group) (count g))}))
         {:resolved {} :counts {}}
         extensions)]
    (when (and (seq resolved) registry-target)
      (if-let [register-many! (try-resolve registry-target)]
        (let [run (r/try-effect* :addon/register-failed (register-many! resolved))]
          (when-not (r/ok? run)
            (log-warn project-id "register-extensions! failed:" (:message run))))
        (log-warn project-id "registry-target unresolvable:" registry-target)))
    (let [active (into {} (filter #(pos? (val %))) counts)]
      (log-info project-id "extensions registered:" (count resolved)
                "from" (count active) "namespaces")
      {:registered (vec (keys resolved))
       :resolved   resolved
       :total      (count resolved)
       :groups     active})))

;; =============================================================================
;; Step 3: bridges (protocol default swaps)
;; =============================================================================

(defn install-bridges!
  "Require each bridge ns and run its install! fn.
   bridges: [{:ns <ns-sym> :install <fq-sym>} ...]"
  [project-id bridges]
  (reduce
   (fn [acc {:keys [ns install]}]
     (let [load-r (require-ns ns)]
       (cond
         (not (r/ok? load-r))
         (do (log-warn project-id "bridge load failed:" ns (:message load-r))
             (update acc :failed conj ns))

         :else
         (if-let [install! (try-resolve install)]
           (let [run (r/try-effect* :addon/bridge-install-failed (install!))]
             (if (r/ok? run)
               (do (log-debug project-id "bridge installed:" install)
                   (update acc :installed conj install))
               (do (log-warn project-id "bridge install failed:" install (:message run))
                   (update acc :failed conj install))))
           (do (log-warn project-id "bridge resolve failed:" install)
               (update acc :failed conj install))))))
   {:installed [] :failed []}
   bridges))

;; =============================================================================
;; Step 4: headless (optional hive-mcp backend registration)
;; =============================================================================

(defn register-headless!
  "Optional headless-backend registration. Reads
   {:headless {:backend-key :ag/loop-backend
               :register    'hive-mcp.agent.ling.headless-registry/register-headless!
               :metadata    {:hive-agent {:provides #{:claude} :priority 10}}}}
   and registers backend-fn under each metadata key."
  [project-id resolved manifest]
  (let [{:keys [backend-key register metadata]} (:headless manifest)]
    (cond
      (or (nil? backend-key) (nil? register))
      false

      :else
      (if-let [backend-fn (get resolved backend-key)]
        (if-let [register-fn (try-resolve register)]
          (let [run (r/try-effect*
                      :addon/headless-register-failed
                      (when-let [backend (backend-fn)]
                        (doseq [[k md] metadata]
                          (register-fn k backend md))
                        true))]
            (cond
              (r/ok? run) (do (log-info project-id "headless registered:"
                                        (vec (keys metadata)))
                              true)
              :else       (do (log-warn project-id "headless register failed:"
                                        (:message run))
                              false)))
          (do (log-warn project-id "headless register-fn unresolvable:" register)
              false))
        (do (log-debug project-id "headless skipped — no backend for"
                       backend-key)
            false)))))

;; =============================================================================
;; Orchestration
;; =============================================================================

(defn run-manifest!
  "Execute all four steps. Idempotent. Returns summary map."
  [project-id manifest]
  (let [lc       (load-lifecycle! project-id (:lifecycle-addons manifest))
        ext      (register-extensions! project-id manifest)
        br       (install-bridges! project-id (:bridges manifest))
        headless (register-headless! project-id (:resolved ext) manifest)]
    {:project-id          project-id
     :lifecycle           lc
     :registered          (:registered ext)
     :total               (:total ext)
     :groups              (:groups ext)
     :bridges             (:installed br)
     :bridges-failed      (:failed br)
     :headless-registered? (boolean headless)}))

;; =============================================================================
;; defmanifest macro
;; =============================================================================

(defmacro defmanifest
  "Declare a project's addon manifest + auto-generate `init!`.

   Emits:
     (def <name> <manifest-map>)
     (defn init! [] (run-manifest! '<name> <name>))
     (defn registered-keys [] <set of all reg-keys in :extensions>)"
  [name manifest-form]
  `(do
     (def ~name ~manifest-form)
     (defn ~'init! []
       (run-manifest! '~name ~name))
     (defn ~'registered-keys []
       (->> (:extensions ~name)
            (mapcat :fns)
            (map second)
            set))
     (var ~name)))
