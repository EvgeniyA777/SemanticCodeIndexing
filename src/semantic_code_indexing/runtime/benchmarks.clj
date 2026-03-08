(ns semantic-code-indexing.runtime.benchmarks
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [semantic-code-indexing.core :as sci]))

(defn- read-json [path]
  (with-open [r (io/reader path)]
    (json/read r :key-fn keyword)))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- build-benchmark-repo! [root]
  (let [order-padding (apply str (repeat 42 ";; pad\n"))
        order-body (str "(ns my.app.order\n"
                        "  (:require [clojure.string :as str]))\n\n"
                        order-padding
                        "(defn process-order [ctx order]\n"
                        "  (validate-order order)\n"
                        "  (str/join \"-\" [\"ok\" (:id order)]))\n\n"
                        "(defn validate-order [order]\n"
                        "  (if (:id order)\n"
                        "    order\n"
                        "    (throw (ex-info \"invalid\" {}))))\n")]
  (write-file! root "src/my/app/order.clj"
               order-body)
  (write-file! root "src/my/app/checkout.clj"
               "(ns my.app.checkout\n  (:require [my.app.order :as order]\n            [my.app.payments :as payments]\n            [my.app.fulfillment :as fulfillment]))\n\n(defn submit-order! [ctx order-input]\n  (let [order (order/process-order ctx order-input)\n        charge (payments/charge! ctx order)]\n    (fulfillment/schedule! ctx order charge)))\n")
  (write-file! root "src/my/app/payments.clj"
               "(ns my.app.payments\n  (:require [my.app.order :as order]))\n\n(defn charge! [ctx order]\n  (retry-charge! ctx order))\n\n(defn retry-charge! [ctx order]\n  (if (:id order)\n    {:ok true :order-id (:id order)}\n    {:ok false}))\n")
  (write-file! root "src/my/app/fulfillment.clj"
               "(ns my.app.fulfillment\n  (:require [my.app.order :as order]))\n\n(defn schedule! [ctx order charge]\n  {:scheduled true :id (:id order) :charge charge})\n")
  (write-file! root "test/my/app/order_test.clj"
               "(ns my.app.order-test\n  (:require [clojure.test :refer [deftest is]]\n            [my.app.order :as order]))\n\n(deftest process-order-test\n  (is (map? (order/validate-order {:id 1}))))\n")
  (write-file! root "test/my/app/checkout_test.clj"
               "(ns my.app.checkout-test\n  (:require [clojure.test :refer [deftest is]]\n            [my.app.checkout :as checkout]))\n\n(deftest submit-order-test\n  (is (map? (checkout/submit-order! {} {:id 1}))))\n"))
  )

(defn- parser-opts-for [fixture]
  (if (= "partial_parser" (:category fixture))
    {:clojure_engine :regex
     :tree_sitter_enabled false}
    {:clojure_engine :clj-kondo
     :tree_sitter_enabled true}))

(def confidence-rank {"low" 0 "medium" 1 "high" 2})
(def raw-rank {"none" 0 "target_span" 1 "enclosing_unit" 2 "local_neighborhood" 3 "whole_file" 4})

(defn- extract-codes [coded-items]
  (set (map :code coded-items)))

(defn- subset-check [have want]
  (every? #(contains? (set have) %) want))

(defn- unit-id-match? [actual expected]
  (or (= actual expected)
      (str/ends-with? actual expected)
      (let [expected-symbol (second (str/split expected #"::" 2))
            actual-symbol (second (str/split actual #"::" 2))]
        (and expected-symbol actual-symbol
             (or (= actual-symbol expected-symbol)
                 (str/ends-with? actual-symbol expected-symbol)
                 (str/ends-with? actual expected-symbol))))))

(defn- contains-unit-id? [actual-ids expected-id]
  (some #(unit-id-match? % expected-id) actual-ids))

(defn- fail [fixture-id msg]
  {:fixture_id fixture-id :ok false :error msg})

(defn- pass [fixture-id]
  {:fixture_id fixture-id :ok true})

(defn- evaluate-fixture [index fixture]
  (let [fixture-id (:fixture_id fixture)
        query (get-in fixture [:input :query])
        expected (:expected fixture)
        result (sci/resolve-context index query)
        packet (:context_packet result)
        diagnostics (:diagnostics_trace result)
        guardrails (:guardrail_assessment result)
        relevant-paths (set (map :path (:relevant_units packet)))
        reason-codes (extract-codes (get-in packet [:evidence :selection_reasons]))
        warning-codes (extract-codes (:warnings diagnostics))
        degradation-codes (extract-codes (:degradations diagnostics))
        risk-codes (extract-codes (:risk_flags guardrails))
        top-targets (set (get-in diagnostics [:result :top_authority_targets]))
        posture (:autonomy_posture guardrails)
        confidence (:level (:confidence packet))
        raw-level (get-in diagnostics [:result :raw_fetch_level_reached])
        rank-by-id (into {} (map (juxt :unit_id :rank_band) (:relevant_units packet)))]
    (cond
      (and (seq (:must_include_paths expected))
           (not (subset-check relevant-paths (:must_include_paths expected))))
      (fail fixture-id (str "missing required paths: " (:must_include_paths expected)))

      (and (seq (:must_include_selection_reason_codes expected))
           (not (subset-check reason-codes (:must_include_selection_reason_codes expected))))
      (fail fixture-id (str "missing selection reason codes: " (:must_include_selection_reason_codes expected)))

      (and (seq (:must_include_warning_codes expected))
           (not (subset-check warning-codes (:must_include_warning_codes expected))))
      (fail fixture-id (str "missing warning codes: " (:must_include_warning_codes expected)))

      (and (seq (:must_include_degradation_codes expected))
           (not (subset-check degradation-codes (:must_include_degradation_codes expected))))
      (fail fixture-id (str "missing degradation codes: " (:must_include_degradation_codes expected)))

      (and (seq (:required_risk_flags expected))
           (not (subset-check risk-codes (:required_risk_flags expected))))
      (fail fixture-id (str "missing risk flags: " (:required_risk_flags expected)))

      (and (seq (:top_authority_units expected))
           (some #(not (contains-unit-id? top-targets %)) (:top_authority_units expected)))
      (fail fixture-id (str "missing top authority units: " (:top_authority_units expected)))

      (and (seq (:rank_band_expectations expected))
           (some (fn [{:keys [unit_id rank_band]}]
                   (let [matched (->> rank-by-id
                                      (filter (fn [[actual-id _]] (unit-id-match? actual-id unit_id)))
                                      first
                                      second)]
                     (not= rank_band matched)))
                 (:rank_band_expectations expected)))
      (fail fixture-id "rank band expectation mismatch")

      (and (:confidence_level expected)
           (not= confidence (:confidence_level expected)))
      (fail fixture-id (str "confidence mismatch: expected " (:confidence_level expected) ", got " confidence))

      (and (:confidence_level_at_least expected)
           (< (get confidence-rank confidence -1)
              (get confidence-rank (:confidence_level_at_least expected) 0)))
      (fail fixture-id (str "confidence too low: expected at least " (:confidence_level_at_least expected) ", got " confidence))

      (and (:autonomy_posture expected)
           (not= posture (:autonomy_posture expected)))
      (fail fixture-id (str "autonomy posture mismatch: expected " (:autonomy_posture expected) ", got " posture))

      (and (seq (:autonomy_posture_allowed expected))
           (not (contains? (set (:autonomy_posture_allowed expected)) posture)))
      (fail fixture-id (str "autonomy posture not allowed: " posture))

      (and (seq (:result_status_allowed expected))
           (not (contains? (set (:result_status_allowed expected)) (get-in diagnostics [:result :result_status]))))
      (fail fixture-id (str "result status not allowed: " (get-in diagnostics [:result :result_status])))

      (and (:raw_fetch_level_ceiling expected)
           (> (get raw-rank raw-level 0)
              (get raw-rank (:raw_fetch_level_ceiling expected) 0)))
      (fail fixture-id (str "raw fetch level exceeded: " raw-level))

      (and (:review_required expected)
           (not= posture "autonomy_blocked"))
      (fail fixture-id "review-required fixture must be autonomy_blocked")

      (and (seq (:review_required_if_posture expected))
           (contains? (set (:review_required_if_posture expected)) posture)
           (not (some #(= "human_review_required" (:code %)) (:required_next_steps guardrails))))
      (fail fixture-id "expected human_review_required in required_next_steps")

      :else
      (pass fixture-id))))

(defn- load-fixtures []
  (let [corpus (read-json "fixtures/retrieval/corpus.json")]
    (mapv (fn [{:keys [path]}]
            (read-json (str "fixtures/retrieval/" path)))
          (:fixtures corpus))))

(defn run-benchmarks []
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-bench-repo" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (build-benchmark-repo! tmp-root)
        fixtures (load-fixtures)
        results (mapv (fn [fixture]
                        (let [index (sci/create-index {:root_path tmp-root
                                                       :parser_opts (parser-opts-for fixture)})]
                          (evaluate-fixture index fixture)))
                      fixtures)
        failures (filterv (complement :ok) results)]
    (doseq [r results]
      (if (:ok r)
        (println "benchmark_ok" (:fixture_id r))
        (println "benchmark_fail" (:fixture_id r) "-" (:error r))))
    {:total (count results)
     :passed (- (count results) (count failures))
     :failed (count failures)
     :failures failures}))

(defn -main [& _]
  (let [{:keys [total passed failed]} (run-benchmarks)]
    (println (str "benchmark_summary total=" total " passed=" passed " failed=" failed))
    (System/exit (if (zero? failed) 0 1))))
