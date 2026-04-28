(ns hive-di.file
  "EDN config file IO — primitive read/write with perm hardening.

   Effect boundary for config-file IO. Callers should layer their own
   merge/schema/secrets-resolution on top.

   Design:
   - All IO returns Result (no thrown exceptions)
   - Missing files return (ok nil) on read — distinct from IO errors
   - `:secret?` flag forces POSIX 0600 on write — for files containing
     API keys, tokens, password-store refs, etc.
   - POSIX perm operations no-op on non-POSIX filesystems"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [hive-dsl.result :as r])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute PosixFilePermissions)))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Perm Hardening
;; =============================================================================

(def ^:private owner-rw-perms
  (PosixFilePermissions/fromString "rw-------"))

(def ^:private default-perms
  (PosixFilePermissions/fromString "rw-r--r--"))

(defn restrict-perms!
  "Set POSIX perms on file to 0600. No-op on non-POSIX filesystems.
   Returns true when applied, false when unavailable or failed."
  [path-or-file]
  (let [^File f (io/file path-or-file)]
    (r/rescue false
      (Files/setPosixFilePermissions (.toPath f) owner-rw-perms)
      true)))

(defn relax-perms!
  "Set POSIX perms on file to 0644. No-op on non-POSIX filesystems."
  [path-or-file]
  (let [^File f (io/file path-or-file)]
    (r/rescue false
      (Files/setPosixFilePermissions (.toPath f) default-perms)
      true)))

;; =============================================================================
;; Read
;; =============================================================================

(defn read-edn
  "Read and parse an EDN file.
   Returns Result:
     (ok parsed) on success
     (ok nil)    when file does not exist
     (err :io/edn-read {...}) on IO or parse failure"
  [path]
  (let [^File f (io/file path)]
    (if-not (.exists f)
      (r/ok nil)
      (r/try-effect* :io/edn-read
        (edn/read-string (slurp f))))))

;; =============================================================================
;; Write
;; =============================================================================

(defn- ensure-parent!
  [^File f]
  (when-let [parent (.getParentFile f)]
    (when-not (.exists parent)
      (.mkdirs parent))))

(defn write-edn!
  "Write `data` to `path` as EDN. Creates parent dirs if missing.

   Options:
     :secret?  — when true, forces POSIX 0600 on the file after write.
                 Use for files that hold API keys, tokens, secret refs.
     :pr-fn    — custom serializer (default `pr-str`)

   Returns Result:
     (ok data) on success
     (err :io/edn-write {...}) on failure"
  ([path data] (write-edn! path data {}))
  ([path data {:keys [secret? pr-fn] :or {pr-fn pr-str}}]
   (r/try-effect* :io/edn-write
     (let [^File f (io/file path)]
       (ensure-parent! f)
       (spit f (pr-fn data))
       (when secret?
         (restrict-perms! f))
       data))))
