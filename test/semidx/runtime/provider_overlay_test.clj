(ns semidx.runtime.provider-overlay-test
  "Stage 5a (plans/018, ADR-046) live-overlay boundary.

  The boundary is language-neutral, so most assertions drive it with an injected
  role: what is being proved is source identity, the failure taxonomy, coverage,
  delivery, and merge behaviour. One test starts the real TypeScript server, so
  the seam is exercised against a live process and not only against a stub."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [semidx.runtime.fact-arbitration :as fa]
            [semidx.runtime.provider-overlay :as overlay]
            [semidx.runtime.provider-selection :as selection]
            [semidx.runtime.providers.lsp-typescript :as lsp-typescript]
            [semidx.test-support.lsp-toolchain :as toolchain]))

(def ^:private corpus-root
  (.getPath (io/file "fixtures/provider-authority/corpus/typescript")))

(def ^:private corpus-paths ["src/orders.ts" "src/validator.ts" "src/index.ts"])

(defn- unit-fact
  ([path symbol] (unit-fact path symbol "definitions" nil))
  ([path symbol operation value]
   {:key {:fact_kind "unit"
          :language "typescript"
          :path path
          :owner "src.orders"
          :symbol symbol
          :overload_identity nil}
    :evidence [{:provider_id "typescript-lsp"
                :provider_version "1"
                :authority "exact"
                :operation operation
                :freshness "exact"
                :source_identity {:content_digest "sha256:overlay"}
                :evidence_location {:path path :start_line 1 :end_line 2}}]
    :value value}))

(defn- roles
  "An overlay role registry whose fact source is `fact-fn`. Substitution happens
  here, never on the boundary's own wrapper, so an injected role gets the same
  lifecycle and isolation a registered one does."
  [fact-fn & {:keys [open-fn status-fn]}]
  {"typescript-lsp" {:status-fn (or status-fn (fn [_] {:state "ready" :reason_codes []}))
                     :open-fn (or open-fn (fn [_] {:session :stub}))
                     :close-fn (fn [_] nil)
                     :fact-source-fn fact-fn}})

(defn- run-overlay [documents role-map & {:as extra}]
  (overlay/shadow-facts-for-overlay
   (merge {:root_path corpus-root
           :documents documents
           :overlay_roles role-map}
          extra)))

;; --- Source identity ----------------------------------------------------

(deftest clean-document-is-anchored-on-the-file-digest-test
  (testing "text read from disk anchors on the same digest the other tiers use,
            so agreement between tiers is agreement about one file"
    (let [resolved (overlay/resolve-document {:root_path corpus-root
                                              :path "src/orders.ts"})]
      (is (false? (:dirty resolved)))
      (is (= "file_bytes_sha256" (get-in resolved [:source_identity :digest_basis])))
      (is (str/starts-with? (get-in resolved [:source_identity :content_digest]) "sha256:")))))

(deftest dirty-document-is-anchored-on-its-own-text-test
  (testing "a live buffer that differs from disk gets a distinct basis, so its
            evidence cannot be mistaken for a claim about the file"
    (let [resolved (overlay/resolve-document {:root_path corpus-root
                                              :path "src/orders.ts"
                                              :text "export function live(): void {}"
                                              :document_version 9})]
      (is (true? (:dirty resolved)))
      (is (= overlay/overlay-text-digest-basis
             (get-in resolved [:source_identity :digest_basis])))
      (is (= 9 (get-in resolved [:source_identity :document_version]))))))

(deftest unreadable-document-is-stale-not-an-exception-test
  (let [resolved (overlay/resolve-document {:root_path corpus-root
                                            :path "src/does-not-exist.ts"})]
    (is (= "stale_document" (:failure resolved)))))

(deftest a-document-contradicting-the-expected-digest-is-refused-test
  (let [resolved (overlay/resolve-document {:root_path corpus-root
                                            :path "src/orders.ts"
                                            :expected_content_digest "sha256:something-else"})]
    (is (= "version_mismatch" (:failure resolved))
        "an overlay must not describe content the caller did not expect")))

;; --- Failure taxonomy ---------------------------------------------------

(deftest every-failure-kind-is-reachable-test
  (let [document {:root_path corpus-root :path "src/orders.ts"}
        kind-of (fn [execution]
                  (or (first (:reason_codes execution))
                      (some :kind (vals (:documents execution)))))
        throwing (fn [type*]
                   (roles (fn [_ _ _] (throw (ex-info "boom" {:type type*})))))]
    (testing "per-document kinds"
      (is (= "timeout" (kind-of (overlay/execute-overlay
                                 "typescript-lsp" [document]
                                 {:overlay_roles (throwing :lsp_request_timeout)}))))
      (is (= "crash" (kind-of (overlay/execute-overlay
                               "typescript-lsp" [document]
                               {:overlay_roles (throwing :lsp_session_closed)}))))
      (is (= "malformed_response" (kind-of (overlay/execute-overlay
                                            "typescript-lsp" [document]
                                            {:overlay_roles (throwing :lsp_malformed_response)}))))
      (is (= "server_error" (kind-of (overlay/execute-overlay
                                      "typescript-lsp" [document]
                                      {:overlay_roles (throwing :lsp_request_failed)}))))
      (is (= "stale_document"
             (kind-of (overlay/execute-overlay
                       "typescript-lsp"
                       [{:root_path corpus-root :path "src/missing.ts"}]
                       {:overlay_roles (roles (fn [_ _ _] {:facts []}))})))
          "a document that cannot be read never reaches the server")
      (is (= "version_mismatch"
             (get-in (overlay/execute-overlay
                      "typescript-lsp"
                      [{:root_path corpus-root :path "src/orders.ts"
                        :expected_content_digest "sha256:nope"}]
                      {:overlay_roles (roles (fn [_ _ _] {:facts []}))})
                     [:documents "src/orders.ts" :kind]))))

    (testing "session-level kinds"
      (let [unavailable (overlay/execute-overlay
                         "typescript-lsp" [document]
                         {:overlay_roles (roles (fn [_ _ _] {:facts []})
                                                :open-fn (fn [_] (throw (ex-info "no server"
                                                                                 {:type :lsp_server_missing}))))})]
        (is (= "unavailable" (:result unavailable)))
        (is (= ["unavailable"] (:reason_codes unavailable))))

      (let [crashed (overlay/execute-overlay
                     "typescript-lsp" [document]
                     {:overlay_roles (roles (fn [_ _ _] {:facts []})
                                            :open-fn (fn [_] (throw (java.io.IOException. "pipe"))))})]
        (is (= "failed" (:result crashed)))
        (is (= ["crash"] (:reason_codes crashed)))))

    (testing "the taxonomy is closed"
      (is (every? overlay/failure-kinds
                  ["timeout" "crash" "malformed_response" "server_error"
                   "stale_document" "version_mismatch" "unavailable"])))))

(deftest a-failing-document-does-not-stop-the-others-test
  (let [execution (overlay/execute-overlay
                   "typescript-lsp"
                   [{:root_path corpus-root :path "src/orders.ts"}
                    {:root_path corpus-root :path "src/validator.ts"}]
                   {:overlay_roles (roles (fn [_ document _]
                                            (if (= "src/orders.ts" (:path document))
                                              (throw (ex-info "boom" {:type :lsp_request_timeout}))
                                              {:facts [(unit-fact "src/validator.ts" "src.validator/check")]})))})]
    (is (= "ready" (:result execution)))
    (is (= {"src/orders.ts" "timeout"} (get-in (overlay/document-states {"typescript-lsp" execution})
                                               ["typescript-lsp" :failed])))
    (is (= ["src/validator.ts"] (get-in (overlay/document-states {"typescript-lsp" execution})
                                        ["typescript-lsp" :analysed])))))

(deftest a-probe-that-throws-is-unavailable-test
  (let [statuses (overlay/overlay-statuses
                  corpus-paths {}
                  (roles (fn [_ _ _] {:facts []})
                         :status-fn (fn [_] (throw (ex-info "probe down" {})))))]
    (is (= "unavailable" (get-in statuses ["typescript-lsp" :state])))
    (is (= ["provider_status_probe_failed"] (get-in statuses ["typescript-lsp" :reason_codes])))))

;; --- Coverage and delivery ----------------------------------------------

(deftest only-analysed-documents-become-coverage-test
  (let [execution (overlay/execute-overlay
                   "typescript-lsp"
                   [{:root_path corpus-root :path "src/orders.ts"}
                    {:root_path corpus-root :path "src/missing.ts"}]
                   {:overlay_roles (roles (fn [_ _ _] {:facts []}))})
        coverage (overlay/overlay-coverage {"typescript-lsp" execution})]
    (is (= {"typescript-lsp" ["src/orders.ts"]} coverage)
        "a document that failed contributes nothing, so no file plans the overlay for it")))

(deftest overlay-facts-reach-the-document-they-belong-to-test
  (let [execution (overlay/execute-overlay
                   "typescript-lsp"
                   [{:root_path corpus-root :path "src/orders.ts"}]
                   {:overlay_roles (roles (fn [_ _ _]
                                            {:facts [(unit-fact "src/orders.ts" "src.orders/normalize")
                                                     (unit-fact "src/orders.ts" "src.orders/normalize"
                                                                "references" nil)]}))})
        runner (overlay/overlay-run-provider {"typescript-lsp" execution})]
    (is (= 1 (count (:facts (runner "typescript-lsp" {:path "src/orders.ts"
                                                      :operation :definitions})))))
    (is (= 1 (count (:facts (runner "typescript-lsp" {:path "src/orders.ts"
                                                      :operation :references})))))
    (is (empty? (:facts (runner "typescript-lsp" {:path "src/other.ts"
                                                  :operation :definitions}))))))

(deftest an-unavailable-overlay-leaves-the-plan-untouched-test
  (let [result (run-overlay [{:path "src/orders.ts"}]
                            (roles (fn [_ _ _] {:facts []}))
                            :overlay_statuses {"typescript-lsp" {:state "unavailable"
                                                                 :reason_codes ["typescript_lsp_server_missing"]}})
        plan (:plan (first (:files result)))
        admitted (mapv :provider_id (get-in plan [:operations :definitions :providers]))]
    (is (empty? (:planned_provider_ids result)))
    (is (= {} (:overlay_coverage result)))
    (is (not (contains? (set admitted) "typescript-lsp"))
        "an unavailable overlay is never admitted")
    (is (= [:definitions] (vec (keys (:operations plan))))
        "an observed-but-unavailable tier is the same absence as an unobserved
         one, so it must not widen the plan into a permanent references gap")
    (is (= "provider_unavailable"
           (->> (get-in plan [:operations :definitions :excluded])
                (filter #(= "typescript-lsp" (:provider_id %)))
                first
                :reason))
        "and its absence is recorded with the reason, not silently dropped")))

(deftest a-failed-document-does-not-admit-the-overlay-for-that-file-test
  (testing "provider status says the session started, not that this document was
            analysed; an overlay provider is file-scoped, so without a
            path-scoped gate a timed-out document would still plan it"
    (let [result (run-overlay [{:path "src/orders.ts"} {:path "src/validator.ts"}]
                              (roles (fn [_ document _]
                                       (if (= "src/orders.ts" (:path document))
                                         (throw (ex-info "boom" {:type :lsp_request_timeout}))
                                         {:facts [(unit-fact "src/validator.ts"
                                                             "src.validator/check")]}))))
          plan-for (fn [path]
                     (:plan (first (filter #(= path (:path %)) (:files result)))))
          failed-plan (plan-for "src/orders.ts")
          ok-plan (plan-for "src/validator.ts")]
      (is (= {"typescript-lsp" ["src/validator.ts"]} (:overlay_coverage result)))

      (testing "the failed document falls back to the tiers below"
        (is (not (contains? (set (mapv :provider_id
                                       (get-in failed-plan [:operations :definitions :providers])))
                            "typescript-lsp")))
        (is (= [:definitions] (vec (keys (:operations failed-plan))))
            "and reports no references gap for an operation nothing would answer")
        (is (= ["overlay_timeout"]
               (->> (get-in failed-plan [:operations :definitions :excluded])
                    (filter #(= "typescript-lsp" (:provider_id %)))
                    first
                    :reason_codes))
            "the downgrade carries the reason the document actually failed with"))

      (testing "the analysed document is unaffected"
        (is (contains? (set (mapv :provider_id
                                  (get-in ok-plan [:operations :definitions :providers])))
                       "typescript-lsp"))
        (is (= [:definitions :references] (vec (keys (:operations ok-plan)))))))))

(deftest an-unobserved-overlay-does-not-widen-the-default-plan-test
  (testing "the catalog knowing a tier that could answer references is not a
            reason to plan that operation on every file"
    (let [plan (selection/provider-plan {:path "src/orders.ts"})]
      (is (= [:definitions] (vec (keys (:operations plan))))
          "without an observed status the LSP tier widens nothing")
      (is (not (contains? (set (keys (:statuses plan))) "typescript-lsp"))))))

;; --- Merge behaviour ----------------------------------------------------

(deftest clean-agreement-merges-into-one-exact-fact-test
  (let [result (run-overlay [{:path "src/orders.ts"}]
                            (roles (fn [_ _ _]
                                     {:facts [(unit-fact "src/orders.ts" "src.orders/normalize")]})))
        facts (:facts (first (:files result)))
        normalize (first (filter #(= "src.orders/normalize" (get-in % [:core_key :symbol])) facts))]
    (is (some? normalize))
    (is (= "exact" (:authority normalize))
        "the overlay tier raises authority over the heuristic tier")
    (is (= #{"typescript-lsp" "typescript-regex"}
           (set (map :provider_id (:evidence normalize))))
        "both tiers' evidence is retained on one canonical fact")
    (is (= 1 (count (filter #(= "src.orders/normalize" (get-in % [:core_key :symbol])) facts)))
        "no duplicate identity")))

(deftest dirty-overlay-evidence-stays-in-the-overlay-scope-test
  (let [live-symbol "src.orders/liveOnly"
        result (run-overlay [{:path "src/orders.ts"
                              :text (str (slurp (io/file corpus-root "src/orders.ts"))
                                         "\nexport function liveOnly(): void {}\n")
                              :document_version 42}]
                            (roles (fn [_ document _]
                                     {:facts [(assoc-in (unit-fact "src/orders.ts" live-symbol)
                                                        [:evidence 0 :source_identity]
                                                        (:source_identity document))]})))
        facts (:facts (first (:files result)))
        live (first (filter #(= live-symbol (get-in % [:core_key :symbol])) facts))]
    (is (= ["src/orders.ts"] (get-in (:documents result) ["typescript-lsp" :dirty])))
    (is (some? live) "a symbol that exists only in the buffer is still a fact")
    (is (= overlay/overlay-text-digest-basis
           (get-in (first (:evidence live)) [:source_identity :digest_basis]))
        "its evidence is anchored on the buffer, never on the file on disk")
    (is (= 42 (get-in (first (:evidence live)) [:source_identity :document_version])))))

(deftest a-stale-batch-artifact-leaves-the-overlay-as-the-only-exact-tier-test
  (testing "a SCIP document dropped by the batch stale gate contributes no
            coverage, so only the live overlay supplies exact evidence"
    (let [result (run-overlay [{:path "src/orders.ts"}]
                              (roles (fn [_ _ _]
                                       {:facts [(unit-fact "src/orders.ts" "src.orders/normalize")]}))
                              ;; The batch tier reported the document stale, so it
                              ;; is absent from batch coverage entirely.
                              :observed_statuses {"scip-typescript" {:state "ready" :reason_codes []}})
          plan (:plan (first (:files result)))
          admitted (mapv :provider_id (get-in plan [:operations :definitions :providers]))
          facts (:facts (first (:files result)))
          normalize (first (filter #(= "src.orders/normalize" (get-in % [:core_key :symbol])) facts))]
      (is (not (contains? (set admitted) "scip-typescript"))
          "a ready status without coverage still admits nothing")
      (is (= #{"typescript-lsp" "typescript-regex"}
             (set (map :provider_id (:evidence normalize))))))))

(deftest equal-authority-disagreement-is-reported-test
  (testing "two exact tiers describing one fact differently must be observable"
    (let [key* {:fact_kind "unit" :language "typescript" :path "src/orders.ts"
                :owner "src.orders" :symbol "src.orders/normalize"}
          fact (fn [provider-id value]
                 {:key key*
                  :evidence [{:provider_id provider-id :provider_version "1"
                              :authority "exact" :operation "definitions"
                              :freshness "exact"
                              :source_identity {:content_digest "sha256:x"}}]
                  :value value})
          {:keys [facts diagnostics]}
          (fa/arbitrate-facts [(fact "scip-typescript" {:kind "term" :signature "const normalize"})
                               (fact "typescript-lsp" {:kind "function" :signature "function normalize"})])
          conflict (first (filter #(= :equal_authority_value_conflict (:code %)) diagnostics))]
      (is (= 1 (count facts)) "the merge itself is unchanged")
      (is (some? conflict))
      (is (= [:kind :signature] (:fields conflict)))
      (is (= ["scip-typescript" "typescript-lsp"] (:provider_ids conflict))))))

;; --- End to end over a real server --------------------------------------

(deftest end-to-end-through-the-repo-managed-server
  (if (= "ready" (:state (lsp-typescript/provider-status {})))
    (let [result (overlay/shadow-facts-for-overlay
                  {:root_path corpus-root
                   :documents (mapv (fn [path] {:path path}) corpus-paths)})
          states (get (:documents result) "typescript-lsp")
          orders (first (filter #(= "src/orders.ts" (:path %)) (:files result)))
          exact (filter #(= "exact" (:authority %)) (:facts orders))]
      (is (= ["typescript-lsp"] (:planned_provider_ids result)))
      (is (= "ready" (:result states)))
      (is (= (sort corpus-paths) (:analysed states)))
      (is (empty? (:failed states)))
      (is (empty? (:dirty states)) "reading from disk is not a dirty buffer")
      (is (seq exact) "the live server raises real definitions to exact")
      (is (every? (fn [fact]
                    (contains? (set (map :provider_id (:evidence fact))) "typescript-lsp"))
                  exact))
      (is (contains? (set (map #(get-in % [:core_key :symbol]) (:facts orders)))
                     "src.orders/normalize")))
    (toolchain/unresolved! "typescript-language-server" "overlay end-to-end test")))
