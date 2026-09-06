(ns semidx.runtime.session-telemetry-test
  "plans/022 Stage 2: the offline transcript join.

  Driven by a synthetic transcript in the real Claude Code shape, so the rules
  are tested rather than the machine's own history."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [semidx.runtime.session-telemetry :as st]))

(defn- assistant [tool-use usage]
  {:type "assistant"
   :timestamp "2026-09-06T10:00:00Z"
   :message (cond-> {:content [tool-use]}
              usage (assoc :usage usage))})

(defn- tool-use [id name input]
  {:type "tool_use" :id id :name name :input input})

(defn- tool-result [id payload]
  {:type "user"
   :message {:content [{:type "tool_result"
                        :tool_use_id id
                        :content [{:type "text" :text (json/write-str payload)}]}]}})

(def ^:private resolve-result
  {:selection_id "sel-1"
   :snapshot_id "snap-1"
   :confidence_level "medium"
   :focus [{:path "src/semidx/runtime/providers.clj" :symbol "run-provider"}
           {:path "src/semidx/runtime/provider_overlay.clj" :symbol "overlay-statuses"}]})

(defn- transcript []
  [(assistant (tool-use "t1" "mcp__semidx__resolve_context" {:intent "where is provider status"})
              {:input_tokens 10 :cache_read_input_tokens 1000
               :cache_creation_input_tokens 5 :output_tokens 20})
   (tool-result "t1" resolve-result)
   (assistant (tool-use "t2" "Read" {:file_path "/repo/src/semidx/runtime/providers.clj"}) nil)
   (tool-result "t2" {})
   (assistant (tool-use "t3" "Bash" {:command "rg overlay-statuses src/"}) nil)
   (tool-result "t3" {})
   (assistant (tool-use "t4" "Bash" {:command "clojure -M:test"}) nil)
   (tool-result "t4" {})
   (assistant (tool-use "t5" "Read" {:file_path "/repo/docs/unrelated.md"}) nil)
   (tool-result "t5" {})
   (assistant (tool-use "t6" "Edit" {:file_path "/repo/src/semidx/runtime/providers.clj"}) nil)
   (tool-result "t6" {})])

(deftest a-result-is-recovered-and-its-selection-read-test
  (testing "the selection is what the MCP event does not carry, so recovering it
            from the transcript is the whole reason this join exists"
    (let [calls (st/session-calls (transcript) {:window 10})
          call (first calls)]
      (is (= 1 (count calls)))
      (is (= ["src/semidx/runtime/providers.clj" "src/semidx/runtime/provider_overlay.clj"]
             (:selection call)))
      (is (= "medium" (:confidence_level call)))
      (is (= "sel-1" (:selection_id call))))))

(deftest follow-ups-are-classified-by-what-they-mean-test
  (let [call (first (st/session-calls (transcript) {:window 10}))
        kinds (mapv :kind (:followups call))]
    (testing "a read inside the selection is the prescribed workflow, not a miss"
      (is (= :in_selection_read (nth kinds 0))))
    (testing "a lexical search over the same question is a fallback"
      (is (= :lexical_search (nth kinds 1))))
    (testing "but a test run is ordinary work, so the command must be read"
      (is (= :other_bash (nth kinds 2))))
    (testing "a read outside the selection is a different signal from one inside"
      (is (= :out_of_selection_read (nth kinds 3))))
    (is (= :edit (nth kinds 4)))))

(deftest the-attribution-window-bounds-what-is-attributed-test
  (testing "without a bound, follow-ups run to the next retrieval — hours of
            unrelated work in a real session"
    (let [narrow (first (st/session-calls (transcript) {:window 2}))
          wide (first (st/session-calls (transcript) {:window 10}))]
      (is (= 2 (count (:followups narrow))))
      (is (= 5 (count (:followups wide))))
      (is (= 5 (:calls_until_next_retrieval narrow))
          "the unbounded count is still reported, so the bound stays visible"))))

(deftest window-sensitivity-is-reported-not-hidden-test
  (testing "the window is a choice with no empirical basis yet; if the
            distribution moves with it, no verdict rule can sit on top"
    (let [sensitivity (st/window-sensitivity (transcript) [1 3 10])]
      (is (= [1 3 10] (keys sensitivity)))
      (is (= {:in_selection_read 1} (get sensitivity 1)))
      (is (contains? (get sensitivity 10) :out_of_selection_read))
      (is (not (contains? (get sensitivity 1) :out_of_selection_read))
          "the same session reads as a miss or not depending on the window"))))

(deftest host-usage-comes-only-from-the-transcript-test
  (testing "these are the numbers semidx never sees"
    (let [totals (st/host-usage-totals (transcript))]
      (is (= 1000 (:cache_read_input_tokens totals)))
      (is (= 20 (:output_tokens totals))))))

(deftest the-join-states-what-it-rests-on-test
  (testing "there is no shared identifier, so the pairing is positional and must
            say so rather than look authoritative"
    (let [calls (st/session-calls (transcript))
          events [{:operation "resolve_context"
                   :occurred_at "2026-09-06T10:00:01Z"
                   :session_id "server-1"
                   :task_id "task-alpha"
                   :confidence_level "medium"
                   :payload {:estimated_tokens 249}}]
          joined (st/join-events calls events)]
      (is (= "positional_within_session" (:join_basis joined)))
      (is (= 1 (count (:paired joined))))
      (is (true? (:confidence_agrees? (first (:paired joined)))))
      (is (= 249 (:estimated_tokens (first (:paired joined)))))
      (is (= "task-alpha" (:event_task_id (first (:paired joined)))))
      (is (zero? (:unpaired_transcript joined)))))

  (testing "an unequal count is reported rather than silently truncated"
    (let [joined (st/join-events (st/session-calls (transcript)) [])]
      (is (= 1 (:unpaired_transcript joined)))
      (is (empty? (:paired joined))))))

(deftest a-malformed-line-does-not-stop-the-parse-test
  (testing "these files are appended to by a live process, so the last line can
            be a partial write"
    (let [file (java.io.File/createTempFile "transcript" ".jsonl")]
      (try
        (spit file (str (json/write-str {:type "user" :message {:content []}}) "\n{\"partial\":"))
        (is (= 1 (count (st/parse-transcript file))))
        (finally (.delete file))))))
