(ns hive-di.presets.conversation
  "Conversation subsystem configuration."
  (:require [hive-di.core :refer [defconfig env]]))

;; =============================================================================
;; ConversationConfig — inbox, delivery, and transport settings
;; =============================================================================

(defconfig ConversationConfig
  :inbox-max-entries   (env "HIVE_CONV_INBOX_MAX"        :default 200    :type :int)
  :message-ttl-ms      (env "HIVE_CONV_TTL_MS"           :default 300000 :type :int)
  :delivery-timeout-ms (env "HIVE_CONV_DELIVERY_TIMEOUT" :default 30000  :type :int)
  :nats-subject-prefix (env "HIVE_CONV_NATS_PREFIX"      :default "hive.conversation" :type :string)
  :file-mailbox-dir    (env "HIVE_CONV_MAILBOX_DIR"      :default "/tmp/hive-mailbox" :type :string)
  :enable-nats-bridge  (env "HIVE_CONV_NATS_ENABLED"     :default false  :type :bool)
  :enable-file-mailbox (env "HIVE_CONV_MAILBOX_ENABLED"  :default false  :type :bool))
