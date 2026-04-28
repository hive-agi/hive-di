(ns hive-di.file-test
  "Tests for hive-di.file — EDN read/write + perm hardening."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-di.file :as file]
            [hive-dsl.result :as r])
  (:import (java.io File)
           (java.nio.file Files)))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- tmp-file ^File [prefix]
  (let [f (File/createTempFile prefix ".edn")]
    (.deleteOnExit f)
    f))

(defn- posix-perms [^File f]
  (try (str (Files/getPosixFilePermissions (.toPath f) (into-array java.nio.file.LinkOption [])))
       (catch UnsupportedOperationException _ ::no-posix)))

;; =============================================================================
;; read-edn
;; =============================================================================

(deftest read-edn-missing-returns-ok-nil
  (let [r (file/read-edn "/tmp/__hive-di-missing-XXXX.edn")]
    (is (r/ok? r))
    (is (nil? (:ok r)))))

(deftest read-edn-roundtrip
  (let [f (tmp-file "hive-di-read")
        data {:openrouter-api-key "sk-test" :nested {:k 1}}]
    (spit f (pr-str data))
    (let [r (file/read-edn f)]
      (is (r/ok? r))
      (is (= data (:ok r))))))

(deftest read-edn-parse-error-returns-err
  (let [f (tmp-file "hive-di-bad")]
    (spit f "{:not balanced")
    (let [r (file/read-edn f)]
      (is (not (r/ok? r))))))

;; =============================================================================
;; write-edn!
;; =============================================================================

(deftest write-edn-creates-parent-dirs
  (let [parent (File/createTempFile "hive-di-pdir" "")
        _ (.delete parent)
        nested (io/file parent "a/b/c.edn")
        data {:hello :world}]
    (try
      (let [r (file/write-edn! nested data)]
        (is (r/ok? r))
        (is (.exists nested))
        (is (= data (:ok (file/read-edn nested)))))
      (finally
        (when (.exists nested) (.delete nested))
        (doseq [d [(io/file parent "a/b") (io/file parent "a") parent]]
          (when (.exists d) (.delete d)))))))

(deftest write-edn-secret-hardens-perms
  (let [f (tmp-file "hive-di-secret")
        r (file/write-edn! f {:k "secret"} {:secret? true})]
    (is (r/ok? r))
    (let [p (posix-perms f)]
      (when (not= p ::no-posix)
        (is (= "[OWNER_READ, OWNER_WRITE]" p))))))

(deftest write-edn-default-does-not-harden
  (let [f (tmp-file "hive-di-public")
        _ (file/write-edn! f {:k 1})
        p (posix-perms f)]
    (when (not= p ::no-posix)
      ;; Default umask-respecting perms include group/other read on most setups.
      ;; We assert only that it is NOT 0600 — we did not opt into hardening.
      (is (not= "[OWNER_READ, OWNER_WRITE]" p)))))

;; =============================================================================
;; restrict-perms! / relax-perms!
;; =============================================================================

(deftest restrict-perms-then-relax
  (let [f (tmp-file "hive-di-perm")]
    (spit f "{}")
    (file/restrict-perms! f)
    (let [p1 (posix-perms f)]
      (when (not= p1 ::no-posix)
        (is (= "[OWNER_READ, OWNER_WRITE]" p1))
        (file/relax-perms! f)
        (let [p2 (posix-perms f)]
          (is (not= "[OWNER_READ, OWNER_WRITE]" p2)))))))
