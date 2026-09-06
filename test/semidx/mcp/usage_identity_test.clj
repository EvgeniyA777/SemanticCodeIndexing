(ns semidx.mcp.usage-identity-test
  "plans/022 Stage 1a: MCP usage-event identity and query-text redaction.

  Both behaviours were defects found by Stage 0 against a live database:
  `resolve_context` was the one operation that lost its session id, and the
  user's query text was written verbatim by default."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [semidx.mcp.core :as core]
            [semidx.runtime.usage-metrics :as usage]))

(defn- sample-repo! []
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "sci-usage-identity" (make-array java.nio.file.attribute.FileAttribute 0)))
        f (io/file root "src/my/app/order.clj")]
    (.mkdirs (.getParentFile f))
    (spit f "(ns my.app.order)\n\n(defn process-order [ctx order]\n  (validate-order order))\n\n(defn validate-order [order]\n  order)\n")
    root))

(defn- session-with-sink []
  (let [sink (usage/in-memory-usage-metrics)
        state (core/new-session-state {:usage-metrics sink
                                       :session-id "server-session-1"})]
    (swap! state assoc :client-info {:name "test-client"})
    [state sink]))

(def ^:private trace-id-1 "11111111-1111-4111-8111-111111111111")
(def ^:private trace-id-2 "22222222-2222-4222-8222-222222222222")

(defn- resolve-events [sink]
  (filter #(= "resolve_context" (:operation %)) (usage/emitted-events sink)))

(defn- call-resolve! [state root query-or-intent]
  (let [created (core/handle-tools-call state {:name "create_index"
                                               :arguments {:root_path root}})
        index-id (-> (get-in created [:content 0 :text])
                     (json/read-str :key-fn keyword)
                     :index_id)]
    (core/handle-tools-call state
                            {:name "resolve_context"
                             :arguments (merge {:index_id index-id} query-or-intent)})))

;; --- Session identity ---------------------------------------------------

(deftest server-session-id-survives-a-query-without-a-trace-test
  (testing "a query with no trace must not blank the session's own identity"
    (let [root (sample-repo!)
          [state sink] (session-with-sink)]
      (call-resolve! state root {:intent "find the order flow"})
      (let [event (first (resolve-events sink))]
        (is (= "server-session-1" (:session_id event)))
        (is (= "test-client" (:actor_id event))
            "the client identity survives too; nils no longer overwrite it")))))

(deftest server-session-id-survives-a-trace-without-a-session-id-test
  (let [root (sample-repo!)
        [state sink] (session-with-sink)]
    (call-resolve! state root
                   {:query {:schema_version "1.0"
                            :intent {:purpose "code_understanding" :details "order flow"}
                            :targets {:symbols ["process-order"]}
                            :trace {:trace_id trace-id-1 :request_id "r-1"}}})
    (let [event (first (resolve-events sink))]
      (is (= "server-session-1" (:session_id event)))
      (is (= trace-id-1 (:trace_id event)))
      (is (= "r-1" (:request_id event))))))

(deftest a-client-session-id-cannot-replace-the-server-one-test
  (testing "the server session id is the identity of the MCP session itself"
    (let [root (sample-repo!)
          [state sink] (session-with-sink)]
      (call-resolve! state root
                     {:query {:schema_version "1.0"
                              :intent {:purpose "code_understanding" :details "order flow"}
                              :targets {:symbols ["process-order"]}
                              :trace {:trace_id trace-id-2
                                      :request_id "r-2"
                                      :session_id "client-supplied"
                                      :task_id "task-7"}}})
      (let [event (first (resolve-events sink))]
        (is (= "success" (:status event)) "the query itself must be valid")
        (is (= "server-session-1" (:session_id event))
            "a trace refines identity; it never replaces it")
        (is (= "task-7" (:task_id event)) "and every other trace field is kept")
        (is (= trace-id-2 (:trace_id event)))
        (is (= "r-2" (:request_id event)))))))

(deftest a-losing-client-session-id-is-kept-as-evidence-test
  (testing "the client id is what an offline join against a host transcript keys
            on, so losing the precedence contest must not mean being discarded"
    (let [root (sample-repo!)
          [state sink] (session-with-sink)]
      (call-resolve! state root
                     {:query {:schema_version "1.0"
                              :intent {:purpose "code_understanding" :details "order flow"}
                              :targets {:symbols ["process-order"]}
                              :trace {:trace_id trace-id-2
                                      :request_id "r-3"
                                      :session_id "host-session-abc"}}})
      (let [event (first (resolve-events sink))]
        (is (= "server-session-1" (:session_id event)))
        (is (= "host-session-abc" (get-in event [:payload :client_session_id]))
            "kept in the payload rather than in the column")))))

(deftest no-client-session-id-adds-no-payload-noise-test
  (testing "the common case is unchanged: nothing is recorded when the client
            sent no session id of its own"
    (let [root (sample-repo!)
          [state sink] (session-with-sink)]
      (call-resolve! state root {:intent "find the order flow"})
      (let [event (first (resolve-events sink))]
        (is (not (contains? (:payload event) :client_session_id)))))))

(deftest every-operation-in-one-session-shares-the-session-id-test
  (testing "grouping is the whole point: create_index and resolve_context must
            agree, which they did not before Stage 1a"
    (let [root (sample-repo!)
          [state sink] (session-with-sink)]
      (call-resolve! state root {:intent "find the order flow"})
      (let [events (usage/emitted-events sink)]
        (is (< 1 (count events)))
        (is (= #{"server-session-1"} (set (map :session_id events))))))))

;; --- Query text redaction ------------------------------------------------

(deftest query-text-is-not-written-by-default-test
  (let [summary {:purpose "code_understanding"
                 :details "where do we validate orders"
                 :target_keys ["paths"]
                 :token_budget 3200
                 :include_tests false}
        redacted (core/redact-query-summary summary)]
    (testing "the prompt itself is replaced by a digest and a length"
      (is (nil? (:details redacted)))
      (is (= 27 (:details_chars redacted)))
      (is (string? (:details_hash redacted)))
      (is (not (re-find #"validate" (pr-str redacted)))))

    (testing "structure is kept, so the event stays analysable"
      (is (= "code_understanding" (:purpose redacted)))
      (is (= ["paths"] (:target_keys redacted)))
      (is (= 3200 (:token_budget redacted))))

    (testing "the same text hashes the same way, so repeats are still visible"
      (is (= (:details_hash redacted)
             (:details_hash (core/redact-query-summary summary)))))

    (testing "different text hashes differently"
      (is (not= (:details_hash redacted)
                (:details_hash (core/redact-query-summary
                                (assoc summary :details "something else"))))))))

(deftest raw-query-text-is-opt-in-test
  (with-redefs [core/capture-query-text? (constantly true)]
    (let [redacted (core/redact-query-summary {:purpose "code_understanding"
                                               :details "kept verbatim"})]
      (is (= "kept verbatim" (:details redacted)))
      (is (nil? (:details_hash redacted))))))

(deftest a-recorded-event-carries-no-query-text-test
  (testing "end to end through a real tool call, not only through the helper"
    (let [root (sample-repo!)
          [state sink] (session-with-sink)]
      (call-resolve! state root {:intent "where do we validate orders"})
      (let [event (first (resolve-events sink))
            summary (get-in event [:payload :normalized_query_summary])]
        (is (nil? (:details summary)))
        (is (string? (:details_hash summary)))
        (is (not (re-find #"validate orders" (pr-str event)))
            "the user's words must not reach the database by default")))))

(deftest the-caller-still-sees-its-normalized-query-test
  (testing "redaction is a telemetry concern; a client asking what its query
            normalized to must still get an answer"
    (let [root (sample-repo!)
          [state sink] (session-with-sink)
          result (call-resolve! state root {:intent "where do we validate orders"})
          text (get-in result [:content 0 :text])
          payload (json/read-str text :key-fn keyword)]
      (is (= "where do we validate orders"
             (get-in payload [:normalized_query_summary :details]))))))
