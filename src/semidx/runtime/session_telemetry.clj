(ns semidx.runtime.session-telemetry
  "Stage 2 of plans/022: the offline join between a host session transcript and
  semidx usage events. Read-only, and it emits no verdict.

  Why offline. semidx sees only what it returned; the host model's own token
  spend, its cache splits, and what the agent did after a retrieval live in the
  host's transcript. Stage 0 measured a second reason: the MCP usage event does
  not carry `selected_paths`, so the load-bearing rule below is not computable
  from the database at all. The transcript supplies the selection and the
  follow-up behaviour; the database supplies identity, timing, and outcome.

  Two layers, deliberately separated because `ideas/016` requires one adapter
  per client while the rules stay shared:

  - `parse-transcript` and `session-calls` know the Claude Code `.jsonl` shape;
  - `classify-followup`, `follow-up-summary`, and `join-events` know only the
    neutral shape those produce.

  What this namespace does **not** do: score, rank, or decide whether a
  retrieval worked. `trace_verdict_policy_v1` may only be written once the
  distributions below show what the data can support, and inventing rules after
  looking at data fits them to it instead of testing them."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def semidx-tool-prefix "mcp__semidx__")

;; ---------------------------------------------------------------------------
;; Claude Code transcript adapter
;; ---------------------------------------------------------------------------

(defn parse-transcript
  "Records from one `.jsonl` transcript, skipping lines that do not parse.

  A malformed line is skipped rather than fatal: these files are appended to by
  a live process, so the last line can be a partial write."
  [path]
  (with-open [reader (io/reader (io/file (str path)))]
    (->> (line-seq reader)
         (keep (fn [line]
                 (try
                   (when-not (str/blank? line)
                     (json/read-str line :key-fn keyword))
                   (catch Exception _ nil))))
         vec)))

(defn- content-items [record]
  (let [content (get-in record [:message :content])]
    (if (sequential? content) content [])))

(defn- tool-uses [record]
  (filter #(= "tool_use" (:type %)) (content-items record)))

(defn- tool-results [record]
  (filter #(= "tool_result" (:type %)) (content-items record)))

(defn- result-text [item]
  (let [content (:content item)]
    (cond
      (string? content) content
      (sequential? content) (str/join (keep :text content))
      :else nil)))

(defn- parse-result [item]
  (when-let [text (result-text item)]
    (try (json/read-str text :key-fn keyword) (catch Exception _ nil))))

(defn- usage-of [record]
  (when-let [usage (get-in record [:message :usage])]
    {:input_tokens (:input_tokens usage)
     :cache_read_input_tokens (:cache_read_input_tokens usage)
     :cache_creation_input_tokens (:cache_creation_input_tokens usage)
     :output_tokens (:output_tokens usage)}))

(defn- short-tool-name [name*]
  (if (str/starts-with? (str name*) semidx-tool-prefix)
    (subs (str name*) (count semidx-tool-prefix))
    (str name*)))

(defn user-turn?
  "True for a record that is a real user prompt.

  The overwhelming majority of `user` records are tool results being fed back to
  the model; meta and sidechain records are machinery. What remains is the one
  boundary in the whole stream that the agent does not draw itself, which is why
  it is the unit of attribution."
  [record]
  (and (= "user" (:type record))
       (not (:isMeta record))
       (not (:isSidechain record))
       (not (some #(= "tool_result" (:type %)) (content-items record)))))

(defn timeline
  "Flat, ordered timeline of tool calls with their results, the host usage
  recorded on the message that issued them, and the user turn they belong to.

  `:turn` counts real user prompts, so every call carries an independently
  observed boundary rather than one the agent declared."
  [records]
  (let [results (into {}
                      (for [record records
                            item (tool-results record)]
                        [(:tool_use_id item) (parse-result item)]))]
    (vec
     (:calls
      (reduce
       (fn [{:keys [turn calls]} record]
         (if (user-turn? record)
           {:turn (inc turn) :calls calls}
           {:turn turn
            :calls (into calls
                         (for [item (tool-uses record)]
                           {:tool_use_id (:id item)
                            :tool (str (:name item))
                            :semidx? (str/starts-with? (str (:name item)) semidx-tool-prefix)
                            :operation (short-tool-name (:name item))
                            :input (:input item)
                            :turn turn
                            :timestamp (:timestamp record)
                            :usage (usage-of record)
                            :result (get results (:id item))}))}))
       {:turn 0 :calls []}
       records)))))

;; ---------------------------------------------------------------------------
;; Neutral rules
;; ---------------------------------------------------------------------------

(defn selected-paths
  "Workspace-relative paths a `resolve_context` result returned.

  This is the field the MCP usage event does not carry, and the reason the join
  needs the transcript at all."
  [result]
  (->> (:focus result)
       (keep :path)
       distinct
       vec))

(def lexical-command-re
  "Commands that mean the agent went looking for text itself. A test run or a
  git command after a retrieval is ordinary work, not a fallback, so reading the
  command is required — `Bash` is the general-purpose tool in these sessions."
  #"(?:^|[|;&\s])(?:rg|grep|ag|ack)\b|find\s+\S+\s+-name")

(defn classify-followup
  "What one tool call after a retrieval says about it, in the vocabulary of
  `ideas/016`.

  `:in_selection_read` is the trap the rules must get right: this project's own
  instructions tell an agent to locate with semidx and then read the exact lines
  it is about to patch, so a naive \"a read after retrieval means failure\" rule
  would score the prescribed workflow as failure."
  [call selection]
  (let [tool (:tool call)
        input (:input call)]
    (cond
      (:semidx? call)
      (case (:operation call)
        ("expand_context" "fetch_context_detail" "skeletons") :staged_continuation
        ("resolve_context" "impact_analysis" "traverse_relations") :requery
        :other_semidx)

      (= "Read" tool)
      (let [path (str (:file_path input))
            relative (last (str/split path #"/"))]
        (if (some (fn [selected]
                    (or (str/ends-with? path (str selected))
                        (= relative (last (str/split (str selected) #"/")))))
                  selection)
          :in_selection_read
          :out_of_selection_read))

      (= "Bash" tool)
      (if (re-find lexical-command-re (str (:command input)))
        :lexical_search
        :other_bash)

      (contains? #{"Edit" "Write" "NotebookEdit"} tool) :edit
      (contains? #{"Grep" "Glob"} tool) :lexical_search
      :else :other_tool)))

(defn- staged-continuation?
  "True when a call continues the given retrieval by reference.

  `expand_context` and `fetch_context_detail` carry the `selection_id` the
  retrieval returned, so the staged flow is linked exactly — the way a span
  links to its parent — and needs no heuristic window at all. Stage 2 originally
  swept these into the same window as everything else, which was the wrong tool
  for a relationship the data states outright."
  [call selection-id]
  (and (:semidx? call)
       (some? selection-id)
       (= selection-id (get-in call [:input :selection_id]))))

(defn session-calls
  "One entry per semidx retrieval, with what it returned and what followed.

  Two kinds of following work, deliberately separated:

  - `:staged` — calls linked to this retrieval by `selection_id`. Exact, and
    counted regardless of how far away they are.
  - `:followups` — everything else the agent did **within the same user turn**.
    The turn is the boundary because the retrieval was made in service of that
    turn, and because the agent does not draw it. Attribution stops at the next
    user prompt even if the agent keeps working, since later work answers a
    different request."
  ([records] (session-calls records {}))
  ([records _opts]
   (let [calls (timeline records)
         retrievals (keep-indexed (fn [i call]
                                    (when (and (:semidx? call)
                                               (= "resolve_context" (:operation call)))
                                      [i call]))
                                  calls)]
     (vec
      (for [[index call] retrievals]
        (let [selection (selected-paths (:result call))
              selection-id (get-in call [:result :selection_id])
              turn (:turn call)
              after (drop (inc index) calls)
              in-turn (take-while #(= turn (:turn %)) after)
              staged (filterv #(staged-continuation? % selection-id) in-turn)
              staged-ids (set (map :tool_use_id staged))
              followups (remove #(contains? staged-ids (:tool_use_id %)) in-turn)]
          {:tool_use_id (:tool_use_id call)
           :timestamp (:timestamp call)
           :turn turn
           :confidence_level (get-in call [:result :confidence_level])
           :result_status (get-in call [:result :result_status])
           :selection_size (count selection)
           :selection selection
           :selection_id selection-id
           :snapshot_id (get-in call [:result :snapshot_id])
           :staged (mapv (fn [c] {:tool (:tool c) :operation (:operation c)}) staged)
           :calls_in_turn (count in-turn)
           :calls_until_next_retrieval
           (count (take-while (fn [next-call]
                                (not (and (:semidx? next-call)
                                          (= "resolve_context" (:operation next-call)))))
                              after))
           :followups (mapv (fn [next-call]
                              {:tool (:tool next-call)
                               :kind (classify-followup next-call selection)})
                            followups)
           :requeried_in_turn? (boolean
                                (some #(and (:semidx? %)
                                            (= "resolve_context" (:operation %)))
                                      in-turn))}))))))

(defn boundary-comparison
  "The same session read through the turn boundary and through fixed windows.

  Kept as evidence for the choice rather than as an option. `ideas/016` never
  bounded \"what the agent did next\", and an arbitrary window made a session read
  as a miss or not depending on the number: measured here, `out_of_selection_read`
  appeared only once the window widened. The turn is not another arbitrary
  number — it is the request the retrieval was serving, and the agent does not
  draw it — so this function exists to show the difference, not to let a caller
  tune it."
  [records fixed-windows]
  (let [calls (session-calls records)
        by-kind (fn [followups] (->> followups (map :kind) frequencies (into (sorted-map))))
        timeline* (timeline records)]
    {:turn_boundary (by-kind (mapcat :followups calls))
     :fixed_windows
     (into (sorted-map)
           (map (fn [window]
                  [window
                   (by-kind
                    (for [[index call] (keep-indexed
                                        (fn [i c]
                                          (when (and (:semidx? c)
                                                     (= "resolve_context" (:operation c)))
                                            [i c]))
                                        timeline*)
                          next-call (take window (drop (inc index) timeline*))]
                      {:kind (classify-followup next-call
                                                (selected-paths (:result call)))}))]))
           fixed-windows)}))

(defn follow-up-summary
  "Distribution of follow-up kinds across a set of calls. A distribution, not a
  score: nothing here says a retrieval worked."
  [calls]
  (->> calls
       (mapcat :followups)
       (map :kind)
       frequencies
       (into (sorted-map))))

(defn host-usage-totals
  "Host token spend recorded in the transcript. semidx never sees these numbers;
  they exist only here."
  [records]
  (->> records
       (keep usage-of)
       (reduce (fn [acc usage]
                 (merge-with + acc (into {} (filter (comp number? val) usage))))
               {})))

;; ---------------------------------------------------------------------------
;; Join with usage events
;; ---------------------------------------------------------------------------

(defn join-events
  "Pair transcript retrievals with semidx usage events.

  There is no shared identifier: the host does not send its own session id, and
  the events carry the server's. The join is therefore positional within a
  session — the nth `resolve_context` in the transcript against the nth
  `resolve_context` event — which holds only when both records cover the same
  session and nothing else drove the server at the same time. Reported as
  `:join_basis` so a reader can see what the pairing rests on."
  [calls events]
  (let [retrieval-events (->> events
                              (filter #(= "resolve_context" (:operation %)))
                              (sort-by :occurred_at)
                              vec)]
    {:join_basis "positional_within_session"
     :transcript_retrievals (count calls)
     :event_retrievals (count retrieval-events)
     :paired (mapv (fn [call event]
                     {:tool_use_id (:tool_use_id call)
                      :selection_size (:selection_size call)
                      :transcript_confidence (:confidence_level call)
                      :event_confidence (:confidence_level event)
                      :event_session_id (:session_id event)
                      :event_task_id (:task_id event)
                      :estimated_tokens (get-in event [:payload :estimated_tokens])
                      :confidence_agrees? (= (:confidence_level call)
                                             (:confidence_level event))})
                   calls retrieval-events)
     :unpaired_transcript (max 0 (- (count calls) (count retrieval-events)))
     :unpaired_events (max 0 (- (count retrieval-events) (count calls)))}))

(defn session-report
  "Everything Stage 2 can say about one session, with no verdict attached."
  [records]
  (let [calls (session-calls records)]
    {:retrievals (count calls)
     :turns (count (filter user-turn? records))
     :host_usage (host-usage-totals records)
     :followup_distribution (follow-up-summary calls)
     :staged_continuations (reduce + 0 (map (comp count :staged) calls))
     :requeried_in_turn (count (filter :requeried_in_turn? calls))
     :confidence_levels (->> calls (map :confidence_level) frequencies (into (sorted-map)))
     :selection_sizes (mapv :selection_size calls)
     :calls calls}))
