(ns hive-di.presets.conversation-test
  "Tests for ConversationConfig defconfig."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-di.presets.conversation :refer [resolve-ConversationConfig
                                                  ConversationConfig-fields
                                                  ConversationConfig-schema]]
            [hive-dsl.result :as r]))

;; =============================================================================
;; Generated Artifacts
;; =============================================================================

(deftest field-registry-exists
  (is (map? ConversationConfig-fields))
  (is (= 7 (count ConversationConfig-fields)))
  (is (every? #(contains? ConversationConfig-fields %)
              [:inbox-max-entries :message-ttl-ms :delivery-timeout-ms
               :nats-subject-prefix :file-mailbox-dir
               :enable-nats-bridge :enable-file-mailbox])))

(deftest schema-generated
  (is (vector? ConversationConfig-schema))
  (is (= :map (first ConversationConfig-schema))))

;; =============================================================================
;; Default Resolution
;; =============================================================================

(deftest resolve-defaults-only
  (testing "All fields resolve to declared defaults when env is empty"
    (let [result (resolve-ConversationConfig {} {:env-fn (constantly nil)})]
      (is (r/ok? result))
      (let [cfg (:ok result)]
        (is (= 200    (:inbox-max-entries cfg)))
        (is (= 300000 (:message-ttl-ms cfg)))
        (is (= 30000  (:delivery-timeout-ms cfg)))
        (is (= "hive.conversation" (:nats-subject-prefix cfg)))
        (is (= "/tmp/hive-mailbox" (:file-mailbox-dir cfg)))
        (is (= false  (:enable-nats-bridge cfg)))
        (is (= false  (:enable-file-mailbox cfg)))))))

;; =============================================================================
;; Env Override
;; =============================================================================

(deftest resolve-with-env-overrides
  (testing "Env vars override defaults with correct coercion"
    (let [mock-env {"HIVE_CONV_INBOX_MAX"        "500"
                    "HIVE_CONV_TTL_MS"           "600000"
                    "HIVE_CONV_DELIVERY_TIMEOUT" "10000"
                    "HIVE_CONV_NATS_PREFIX"      "custom.prefix"
                    "HIVE_CONV_MAILBOX_DIR"      "/var/hive/mail"
                    "HIVE_CONV_NATS_ENABLED"     "true"
                    "HIVE_CONV_MAILBOX_ENABLED"  "true"}
          result (resolve-ConversationConfig {} {:env-fn #(get mock-env %)})]
      (is (r/ok? result))
      (let [cfg (:ok result)]
        (is (= 500    (:inbox-max-entries cfg)))
        (is (= 600000 (:message-ttl-ms cfg)))
        (is (= 10000  (:delivery-timeout-ms cfg)))
        (is (= "custom.prefix" (:nats-subject-prefix cfg)))
        (is (= "/var/hive/mail" (:file-mailbox-dir cfg)))
        (is (= true   (:enable-nats-bridge cfg)))
        (is (= true   (:enable-file-mailbox cfg)))))))

;; =============================================================================
;; Partial Env Override
;; =============================================================================

(deftest resolve-with-partial-env
  (testing "Unset env vars fall back to defaults"
    (let [mock-env {"HIVE_CONV_INBOX_MAX" "1000"
                    "HIVE_CONV_NATS_ENABLED" "true"}
          result (resolve-ConversationConfig {} {:env-fn #(get mock-env %)})]
      (is (r/ok? result))
      (let [cfg (:ok result)]
        (is (= 1000   (:inbox-max-entries cfg)))
        (is (= true   (:enable-nats-bridge cfg)))
        ;; rest stay at defaults
        (is (= 300000 (:message-ttl-ms cfg)))
        (is (= false  (:enable-file-mailbox cfg)))))))
