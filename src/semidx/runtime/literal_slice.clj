(ns semidx.runtime.literal-slice
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private max-line-span 400)
(def ^:private max-byte-count (* 32 1024))

(defn- invalid-request [message details]
  (throw (ex-info message
                  {:type :invalid_request
                   :message message
                   :details details})))

(defn- ensure-non-blank-string [value field-name]
  (when-not (and (string? value) (not (str/blank? value)))
    (invalid-request (str field-name " must be a non-empty string")
                     {:field field-name}))
  value)

(defn- ensure-positive-int [value field-name]
  (when-not (integer? value)
    (invalid-request (str field-name " must be an integer")
                     {:field field-name}))
  (when (<= (long value) 0)
    (invalid-request (str field-name " must be >= 1")
                     {:field field-name
                      :value value}))
  (long value))

(defn- selection-cache [index]
  (:selection_cache (meta index)))

(defn- selection-cache-state [index]
  (let [state @(selection-cache index)
        entries (cond
                  (map? (:entries state)) (:entries state)
                  (map? state) (dissoc state :entries :order :evicted :evicted_order :max_entries :max_evicted)
                  :else {})]
    {:entries entries
     :evicted (or (:evicted state) {})}))

(defn- current-selection [index selection-id]
  (get-in (selection-cache-state index) [:entries selection-id]))

(defn- evicted-selection [index selection-id]
  (get-in (selection-cache-state index) [:evicted selection-id]))

(defn- ensure-selection! [index selection-id snapshot-id]
  (let [selection (current-selection index selection-id)]
    (cond
      (some? (evicted-selection index selection-id))
      (throw (ex-info "selection_id was evicted"
                      {:type :selection_evicted
                       :message "selection_id was evicted"
                       :details {:selection_id selection-id
                                 :snapshot_id snapshot-id}}))

      (nil? selection)
      (throw (ex-info "selection_id not found"
                      {:type :selection_not_found
                       :message "selection_id not found"
                       :details {:selection_id selection-id
                                 :snapshot_id snapshot-id}}))

      (not= (str snapshot-id) (str (:snapshot_id selection)))
      (throw (ex-info "snapshot_id does not match selection"
                      {:type :snapshot_mismatch
                       :message "snapshot_id does not match selection"
                       :details {:selection_id selection-id
                                 :expected_snapshot_id (:snapshot_id selection)
                                 :provided_snapshot_id snapshot-id}}))

      :else
      selection)))

(defn- ensure-current-snapshot! [index snapshot-id]
  (when (not= (str snapshot-id) (str (:snapshot_id index)))
    (throw (ex-info "snapshot_id is not available on the current index"
                    {:type :snapshot_mismatch
                     :message "snapshot_id is not available on the current index"
                     :details {:expected_snapshot_id (:snapshot_id index)
                               :provided_snapshot_id snapshot-id}}))))

(defn- read-current-file-lines [index path]
  (when-not (contains? (:files index) path)
    (invalid-request "path is not present in the current index snapshot"
                     {:path path
                      :snapshot_id (:snapshot_id index)}))
  (let [f (io/file (:root_path index) path)]
    (if (.exists f)
      (-> f slurp str/split-lines vec)
      (invalid-request "path does not exist on disk for the current index snapshot"
                       {:path path
                        :snapshot_id (:snapshot_id index)}))))

(defn- read-selection-file-lines [selection path]
  (if-let [lines (get-in selection [:file_snapshots path])]
    lines
    (invalid-request "path is not present in the retained selection snapshot"
                     {:path path
                      :selection_id (:selection_id selection)
                      :snapshot_id (:snapshot_id selection)})))

(defn- requested-end-line [start-line end-line]
  (min end-line (+ start-line (dec max-line-span))))

(defn- utf8-bytes [s]
  (count (.getBytes (str s) java.nio.charset.StandardCharsets/UTF_8)))

(defn- limit-lines-bytes [lines]
  (loop [remaining lines
         chosen []
         byte-count 0]
    (if-let [line (first remaining)]
      (let [separator-bytes (if (seq chosen) 1 0)
            line-bytes (utf8-bytes line)
            next-byte-count (+ byte-count separator-bytes line-bytes)]
        (if (> next-byte-count max-byte-count)
          {:lines chosen
           :byte_count byte-count
           :truncated? true}
          (recur (rest remaining)
                 (conj chosen line)
                 next-byte-count)))
      {:lines chosen
       :byte_count byte-count
       :truncated? false})))

(defn- exact-slice [file-lines start-line end-line]
  (let [total-lines (count file-lines)]
    (when (> start-line total-lines)
      (invalid-request "start_line is beyond the end of file"
                       {:start_line start-line
                        :total_lines total-lines}))
    (let [capped-end (requested-end-line start-line end-line)
          slice-end (min total-lines capped-end)
          selected-lines (subvec file-lines (dec start-line) slice-end)
          line-cap-truncated? (< capped-end end-line)
          byte-limited (limit-lines-bytes selected-lines)
          returned-lines (:lines byte-limited)
          content (str/join "\n" returned-lines)
          returned-end (if (seq returned-lines)
                         (+ start-line (dec (count returned-lines)))
                         (dec start-line))
          truncation-reason (cond
                              line-cap-truncated? "line_cap_exceeded"
                              (:truncated? byte-limited) "byte_cap_exceeded"
                              :else nil)]
      {:requested_range {:start_line start-line
                         :end_line end-line}
       :returned_range {:start_line start-line
                        :end_line returned-end}
       :content content
       :line_count (count returned-lines)
       :byte_count (if (seq returned-lines) (utf8-bytes content) 0)
       :truncated (boolean truncation-reason)
       :truncation_reason truncation-reason})))

(defn literal-file-slice
  ([index request]
   (literal-file-slice index request {}))
  ([index {:keys [selection_id snapshot_id path start_line end_line]} _opts]
   (let [snapshot-id (ensure-non-blank-string snapshot_id "snapshot_id")
         path* (ensure-non-blank-string path "path")
         start-line* (ensure-positive-int start_line "start_line")
         end-line* (ensure-positive-int end_line "end_line")]
     (when (< end-line* start-line*)
       (invalid-request "end_line must be >= start_line"
                        {:start_line start-line*
                         :end_line end-line*}))
     (let [[lines selection] (if (some? selection_id)
                               (let [selection-id* (ensure-non-blank-string selection_id "selection_id")
                                     selection* (ensure-selection! index selection-id* snapshot-id)]
                                 [(read-selection-file-lines selection* path*) selection*])
                               (do
                                 (ensure-current-snapshot! index snapshot-id)
                                 [(read-current-file-lines index path*) nil]))
           slice (exact-slice lines start-line* end-line*)]
       (cond-> {:api_version "1.0"
                :projection_profile "literal_slice"
                :snapshot_id snapshot-id
                :path path*}
         selection (assoc :selection_id (:selection_id selection))
         true (merge slice))))))
