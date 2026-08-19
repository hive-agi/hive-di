(ns hive-di.file.posix
  "POSIX permission hardening — the java.nio.file boundary of `hive-di.file`.

   Split out so `hive-di.file` carries no java.nio.file call in its analysed
   path: a host without `java.nio.file.attribute.PosixFilePermissions` fails
   to ANALYSE such a call, which kills the whole namespace rather than the one
   operation. `hive-di.file` resolves this namespace softly and reports the
   same `false` it already reports for a non-POSIX filesystem."
  (:require [clojure.java.io :as io]
            [hive-dsl.result :as r])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute PosixFilePermissions)))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private owner-rw-perms
  (PosixFilePermissions/fromString "rw-------"))

(def ^:private default-perms
  (PosixFilePermissions/fromString "rw-r--r--"))

(defn set-owner-rw!
  "Set POSIX perms on PATH-OR-FILE to 0600. True when applied, false when
   unavailable or failed."
  [path-or-file]
  (let [^File f (io/file path-or-file)]
    (r/rescue false
      (Files/setPosixFilePermissions (.toPath f) owner-rw-perms)
      true)))

(defn set-default!
  "Set POSIX perms on PATH-OR-FILE to 0644. True when applied, false when
   unavailable or failed."
  [path-or-file]
  (let [^File f (io/file path-or-file)]
    (r/rescue false
      (Files/setPosixFilePermissions (.toPath f) default-perms)
      true)))
