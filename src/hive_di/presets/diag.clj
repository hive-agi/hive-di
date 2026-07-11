(ns hive-di.presets.diag
  "Diagnostics subsystem configuration."
  (:require [hive-di.core :refer [defconfig env]]))

;; =============================================================================
;; DiagConfig — histogram, allocation-sampling, sizing, and dump settings
;; =============================================================================

(defconfig DiagConfig
  :histogram-top-n      (env "HIVE_DIAG_HISTO_N"     :default 25         :type :int)
  :alloc-sample-ms      (env "HIVE_DIAG_ALLOC_MS"    :default 3000       :type :int)
  :static-threshold-bps (env "HIVE_DIAG_STATIC_BPS"  :default 5242880    :type :int)   ; 5 MiB/s: below => static residency
  :sizer-guard-bytes    (env "HIVE_DIAG_SIZER_GUARD" :default 4294967296 :type :int)   ; 4 GiB: MIN FREE heap (max-used) the clinic requires before risking a deep retained-size walk; below it, hunt-retainers SKIPS the candidate (OOM guard under ExitOnOutOfMemoryError). NOT a max-object-size cap — an object's size is unknowable before the walk, so we gate on available headroom instead.
  :dump-dir             (env "HIVE_DIAG_DUMP_DIR"    :default "/home/leibniz" :type :string))
