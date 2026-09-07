(ns semidx.runtime.freshness-test
  (:require [clojure.test :refer [deftest is testing]]
            [semidx.runtime.freshness :as freshness]))

(deftest decide-freshness-test
  (let [curr-ws {:schema_version "1"
                 :discovery_profile_hash "hash1"
                 :provider_registry_version "1"
                 :semantic_pipeline_version "1"
                 :files [{:path "src/foo.clj" :content_digest "d1" :provider_id "p1" :provider_version "1"}
                         {:path "src/bar.clj" :content_digest "d2" :provider_id "p1" :provider_version "1"}]
                 :workspace_fingerprint "fp1"}
        prev-snap {:snapshot_id "snap1"
                   :workspace_state curr-ws}]
    
    (testing "Rule 1: force_rebuild requested"
      (let [res1 (freshness/decide-freshness prev-snap curr-ws {:force_rebuild true})
            res2 (freshness/decide-freshness prev-snap curr-ws {:force-rebuild true})]
        (is (= :full_rebuild (:action res1)))
        (is (= "force_rebuild_requested" (:reason res1)))
        (is (= :full_rebuild (:action res2)))))

    (testing "Rule 2: No previous snapshot"
      (let [res (freshness/decide-freshness nil curr-ws {})]
        (is (= :full_rebuild (:action res)))
        (is (= "initial_build" (:reason res)))))

    (testing "Rule 3: :workspace_state missing from previous snapshot"
      (let [res (freshness/decide-freshness {:snapshot_id "snap1"} curr-ws {})]
        (is (= :full_rebuild (:action res)))
        (is (= "no_prior_manifest" (:reason res)))))

    (testing "Rule 4: Manifest schema version incompatible"
      (let [prev (assoc-in prev-snap [:workspace_state :schema_version] "0")
            res (freshness/decide-freshness prev curr-ws {})]
        (is (= :full_rebuild (:action res)))
        (is (= "manifest_schema_incompatible" (:reason res)))))

    (testing "Rule 5: provider_registry_version or semantic_pipeline_version changed"
      (let [prev1 (assoc-in prev-snap [:workspace_state :provider_registry_version] "0")
            prev2 (assoc-in prev-snap [:workspace_state :semantic_pipeline_version] "0")
            res1 (freshness/decide-freshness prev1 curr-ws {})
            res2 (freshness/decide-freshness prev2 curr-ws {})]
        (is (= :full_rebuild (:action res1)))
        (is (= "provider_or_pipeline_version_changed" (:reason res1)))
        (is (= :full_rebuild (:action res2)))))

    (testing "Rule 6: workspace_fingerprint matches -> reuse"
      (let [res (freshness/decide-freshness prev-snap curr-ws {})]
        (is (= :reuse (:action res)))
        (is (= "workspace_unchanged" (:reason res)))))

    (testing "Rule 7: Delta file count > threshold -> full rebuild"
      (let [prev-ws {:schema_version "1"
                     :discovery_profile_hash "hash1"
                     :provider_registry_version "1"
                     :semantic_pipeline_version "1"
                     :files [{:path "src/foo.clj" :content_digest "d1-old"}
                             {:path "src/bar.clj" :content_digest "d2-old"}]
                     :workspace_fingerprint "fp-old"}
            prev (assoc prev-snap :workspace_state prev-ws)
            ;; Both files changed -> delta = 2. Total = 2. Threshold = 2 * 0.5 = 1.0.
            ;; delta-count (2) > threshold (1.0) -> full rebuild.
            res (freshness/decide-freshness prev curr-ws {:freshness_delta_rebuild_ratio 0.5})]
        (is (= :full_rebuild (:action res)))
        (is (= "delta_exceeds_threshold" (:reason res)))))

    (testing "Rule 8: Otherwise -> incremental update"
      (let [prev-ws {:schema_version "1"
                     :discovery_profile_hash "hash1"
                     :provider_registry_version "1"
                     :semantic_pipeline_version "1"
                     :files [{:path "src/foo.clj" :content_digest "d1-old"}
                             {:path "src/bar.clj" :content_digest "d2"}]
                     :workspace_fingerprint "fp-old"}
            prev (assoc prev-snap :workspace_state prev-ws)
            ;; Only foo.clj changed -> delta = 1. Total = 2. Threshold = 2 * 0.5 = 1.0.
            ;; delta-count (1) is not > threshold (1.0) -> incremental.
            res (freshness/decide-freshness prev curr-ws {:freshness_delta_rebuild_ratio 0.5})]
        (is (= :incremental_update (:action res)))
        (is (= "workspace_changed" (:reason res)))
        (is (= ["src/foo.clj"] (:changed_paths res)))))))

(deftest an-authority-model-change-forces-a-full-rebuild-test
  ;; plans/018 Stage 6.3. The same files under a different authority model are a
  ;; different snapshot: the units carry different authorities, different
  ;; parser_mode labels, and possibly different units entirely.
  (let [ws {:schema_version "1"
            :discovery_profile_hash "hash1"
            :provider_registry_version "1"
            :semantic_pipeline_version "1"
            :files [{:path "src/foo.ts" :content_digest "d1"}
                    {:path "src/bar.ts" :content_digest "d2"}]
            :workspace_fingerprint "fp1"}
        model {:mode "authority" :policy_version "1"}]

    (testing "a snapshot built with no provider pipeline is not served to a build
              that asked for one, even when every file is byte-identical"
      (let [res (freshness/decide-freshness {:workspace_state ws}
                                            (assoc ws :authority_model model)
                                            {})]
        (is (= :full_rebuild (:action res)))
        (is (= "authority_model_changed" (:reason res)))))

    (testing "and the reverse, which is the direction that would silently serve
              authority-labelled units to a caller who switched the pipeline off"
      (let [res (freshness/decide-freshness {:workspace_state (assoc ws :authority_model model)}
                                            ws
                                            {})]
        (is (= :full_rebuild (:action res)))
        (is (= "authority_model_changed" (:reason res)))))

    (testing "a different model is as invalidating as no model"
      (let [res (freshness/decide-freshness
                 {:workspace_state (assoc ws :authority_model model)}
                 (assoc ws :authority_model (assoc model :policy_version "2"))
                 {})]
        (is (= "authority_model_changed" (:reason res)))))

    (testing "the rebuild is total rather than a delta, because an incremental
              update would leave every untouched file labelled under a model that
              no longer applies"
      (let [prev (assoc ws :files [{:path "src/foo.ts" :content_digest "old"}
                                   {:path "src/bar.ts" :content_digest "d2"}])
            res (freshness/decide-freshness {:workspace_state prev}
                                            (assoc ws :authority_model model)
                                            {:freshness_delta_rebuild_ratio 0.5})]
        (is (= :full_rebuild (:action res)))
        (is (= "authority_model_changed" (:reason res)))
        (is (empty? (:changed_paths res))
            "the model rule answers before the delta rule, so no partial path list
             is reported for a rebuild that is not driven by paths")))

    (testing "an unchanged model reuses exactly as before"
      (let [both (assoc ws :authority_model model)
            res (freshness/decide-freshness {:workspace_state both} both {})]
        (is (= :reuse (:action res)))
        (is (= "workspace_unchanged" (:reason res)))))))
