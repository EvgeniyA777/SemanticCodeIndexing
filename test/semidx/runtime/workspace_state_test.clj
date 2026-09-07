(ns semidx.runtime.workspace-state-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.runtime.provider-authority :as authority]
            [semidx.runtime.workspace-state :as ws]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(deftest workspace-state-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-workspace-state-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! tmp-root "src/foo.clj" "(ns foo)")
        _ (write-file! tmp-root "src/bar.clj" "(ns bar)")
        profile {:paths ["src/foo.clj" "src/bar.clj"]}
        catalog-ver "1"]
    
    (testing "Identical filesystem state -> identical workspace_fingerprint"
      (let [state1 (ws/capture-workspace-state tmp-root profile catalog-ver)
            state2 (ws/capture-workspace-state tmp-root profile catalog-ver)]
        (is (= (:workspace_fingerprint state1) (:workspace_fingerprint state2)))
        (is (= 2 (count (:files state1))))
        (is (= "clojure-native" (get-in state1 [:files 0 :provider_id])))))

    (testing "Modified content -> file in :changed_paths, different fingerprint"
      (let [state1 (ws/capture-workspace-state tmp-root profile catalog-ver)
            _ (write-file! tmp-root "src/foo.clj" "(ns foo-modified)")
            state2 (ws/capture-workspace-state tmp-root profile catalog-ver)
            diff (ws/diff-workspace-state state1 state2)]
        (is (not= (:workspace_fingerprint state1) (:workspace_fingerprint state2)))
        (is (= ["src/foo.clj"] (:changed_paths diff)))
        (is (empty? (:added_paths diff)))
        (is (empty? (:deleted_paths diff)))))

    (testing "Added file -> file in :added_paths"
      (let [state1 (ws/capture-workspace-state tmp-root profile catalog-ver)
            _ (write-file! tmp-root "src/baz.clj" "(ns baz)")
            state2 (ws/capture-workspace-state tmp-root profile catalog-ver)
            diff (ws/diff-workspace-state state1 state2)]
        (is (= ["src/baz.clj"] (:added_paths diff)))))

    (testing "Deleted file -> file in :deleted_paths"
      (let [state1 (ws/capture-workspace-state tmp-root profile catalog-ver)
            ;; delete src/baz.clj
            _ (io/delete-file (io/file tmp-root "src/baz.clj"))
            state2 (ws/capture-workspace-state tmp-root profile catalog-ver)
            diff (ws/diff-workspace-state state1 state2)]
        (is (= ["src/baz.clj"] (:deleted_paths diff)))))

    (testing "modified_at change, content unchanged -> NOT in :changed_paths"
      (let [state1 (ws/capture-workspace-state tmp-root profile catalog-ver)
            ;; Update modified time but keep content same
            f (io/file tmp-root "src/bar.clj")
            _ (.setLastModified f (+ (.lastModified f) 50000))
            state2 (ws/capture-workspace-state tmp-root profile catalog-ver)
            diff (ws/diff-workspace-state state1 state2)]
        (is (empty? (:changed_paths diff)))
        (is (empty? (:added_paths diff)))
        (is (empty? (:deleted_paths diff)))))

    (testing "FAST mode content_digest reuse works when mtime and size are unchanged"
      (let [state1 (ws/capture-workspace-state tmp-root profile catalog-ver)
            ;; We call capture-workspace-state with state1 as prior-workspace-state.
            ;; It should not re-hash.
            state2 (ws/capture-workspace-state tmp-root profile catalog-ver state1)]
        (is (= (:workspace_fingerprint state1) (:workspace_fingerprint state2)))))

    (testing "provider_registry_version bump -> appears in :compatibility_changes"
      (let [state1 (ws/capture-workspace-state tmp-root profile catalog-ver)
            ;; We manually override the registry version in state2 to simulate a bump.
            state2 (assoc state1 :provider_registry_version "999")
            diff (ws/diff-workspace-state state1 state2)]
        (is (= [:provider_registry_version_changed] (:compatibility_changes diff)))))))

(deftest provider-catalog-alignment-test
  (testing "Verify provider catalog aligns with adapters"
    ;; Ensure that every language in the catalog maps to classification "source"
    (doseq [[lang info] ws/provider-catalog]
      (is (= "source" (:classification info)))
      (is (seq (:provider_id info)))
      (is (seq (:provider_version info))))))

(deftest the-authority-model-is-part-of-workspace-identity-test
  ;; plans/018 Stage 6.3.
  (let [files [{:path "src/a.ts" :content_digest "sha256:1" :provider_id "p" :provider_version "1"}]
        legacy (ws/compute-workspace-fingerprint "dp" "4" "1" files)
        explicit-nil (ws/compute-workspace-fingerprint "dp" "4" "1" files nil)
        shadow (ws/compute-workspace-fingerprint "dp" "4" "1" files
                                                 (authority/authority-model :shadow))
        authority (ws/compute-workspace-fingerprint "dp" "4" "1" files
                                                    (authority/authority-model :authority))]

    (testing "a build that runs no provider pipeline hashes exactly what it hashed
              before this stage existed, so snapshots taken before it stay valid"
      (is (= legacy explicit-nil))
      (is (nil? (authority/authority-model :off))))

    (testing "each observing mode is its own workspace identity"
      (is (not= legacy shadow))
      (is (not= legacy authority))
      (is (not= shadow authority)))

    (testing "the model carries what would make two builds disagree"
      (let [model (authority/authority-model :authority)]
        (is (= "authority" (:mode model)))
        (is (= authority/authority-policy-version (:policy_version model)))
        (is (= ["java" "typescript"] (:languages model)))
        (is (contains? (:provider_versions model) "scip-typescript"))
        (is (contains? (:provider_versions model) "java-regex"))))))

(deftest a-captured-manifest-carries-the-model-only-when-there-is-one-test
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "semidx-ws" (into-array java.nio.file.attribute.FileAttribute [])))]
    (spit (clojure.java.io/file dir "a.ts") "export function f() { return 1; }\n")
    (let [plain (ws/capture-workspace-state dir {} "1")
          with-model (ws/capture-workspace-state dir {} "1" nil nil
                                                 (authority/authority-model :authority))]
      (is (not (contains? plain :authority_model))
          "an absent model is an absent key, not a nil one: a nil would change the
           shape of every manifest ever written")
      (is (some? (:authority_model with-model)))
      (is (not= (:workspace_fingerprint plain) (:workspace_fingerprint with-model))))))
