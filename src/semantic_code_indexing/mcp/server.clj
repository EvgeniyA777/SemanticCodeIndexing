(ns semantic-code-indexing.mcp.server
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [semantic-code-indexing.core :as sci]
            [semantic-code-indexing.mcp.core :as core]
            [semantic-code-indexing.runtime.retrieval-policy :as rp]
            [semantic-code-indexing.runtime.usage-metrics :as usage])
  (:import [java.io ByteArrayOutputStream InputStream OutputStream PushbackInputStream]))

(def ^:private default-max-indexes core/default-max-indexes)

(defn- parse-args [args]
  (loop [m {} xs args]
    (if (empty? xs)
      m
      (let [[k v & rest] xs]
        (case k
          "--max-indexes" (recur (assoc m :max_indexes (or (some-> v parse-long)
                                                           default-max-indexes))
                                 rest)
          "--policy-registry-file" (recur (assoc m :policy_registry_file v) rest)
          (recur m rest))))))

(defn- handle-tools-call [state params]
  (core/handle-tools-call state params))

(defn- headers-complete? [^bytes bytes]
  (let [n (alength bytes)]
    (or (and (>= n 4)
             (= 13 (aget bytes (- n 4)))
             (= 10 (aget bytes (- n 3)))
             (= 13 (aget bytes (- n 2)))
             (= 10 (aget bytes (- n 1))))
        (and (>= n 2)
             (= 10 (aget bytes (- n 2)))
             (= 10 (aget bytes (- n 1)))))))

(defn- header-terminator-length [^bytes bytes]
  (let [n (alength bytes)]
    (cond
      (and (>= n 4)
           (= 13 (aget bytes (- n 4)))
           (= 10 (aget bytes (- n 3)))
           (= 13 (aget bytes (- n 2)))
           (= 10 (aget bytes (- n 1))))
      4

      (and (>= n 2)
           (= 10 (aget bytes (- n 2)))
           (= 10 (aget bytes (- n 1))))
      2

      :else
      nil)))

(defn- read-header-block [^InputStream input-stream]
  (let [buffer (ByteArrayOutputStream.)]
    (loop []
      (let [b (.read input-stream)]
        (cond
          (= -1 b)
          (if (zero? (.size buffer))
            nil
            (throw (ex-info "unexpected EOF while reading MCP headers" {:type :protocol_error})))

          :else
          (do
            (.write buffer b)
            (let [bytes (.toByteArray buffer)]
              (if (headers-complete? bytes)
                (String. bytes 0 (- (alength bytes) (header-terminator-length bytes)) "UTF-8")
                (recur)))))))))

(defn- read-json-line-text [^PushbackInputStream input-stream first-byte]
  (let [buffer (ByteArrayOutputStream.)]
    (.write buffer first-byte)
    (loop []
      (let [b (.read input-stream)]
        (cond
          (= -1 b)
          (str/trim (String. (.toByteArray buffer) "UTF-8"))

          (= 10 b)
          (str/trim (String. (.toByteArray buffer) "UTF-8"))

          :else
          (do
            (.write buffer b)
            (recur)))))))

(defn- read-next-byte [^PushbackInputStream input-stream]
  (loop []
    (let [b (.read input-stream)]
      (cond
        (= -1 b) nil
        (contains? #{9 10 13 32} b) (recur)
        :else b))))

(defn- parse-headers [header-text]
  (reduce (fn [acc line]
            (let [[k v] (str/split line #":" 2)]
              (assoc acc (str/lower-case (str/trim k)) (str/trim v))))
          {}
          (remove str/blank? (str/split-lines header-text))))

(defn- read-body-bytes [^InputStream input-stream length]
  (let [buffer (byte-array length)]
    (loop [offset 0]
      (if (= offset length)
        buffer
        (let [read-count (.read input-stream buffer offset (- length offset))]
          (when (= -1 read-count)
            (throw (ex-info "unexpected EOF while reading MCP body" {:type :protocol_error})))
          (recur (+ offset read-count)))))))

(defn- read-framed-message! [^InputStream input-stream]
  (when-let [header-text (read-header-block input-stream)]
    (let [headers (parse-headers header-text)
          content-length (some-> (get headers "content-length") parse-long)]
      (when-not content-length
        (throw (ex-info "missing Content-Length header" {:type :protocol_error})))
      {:transport-format :headers
       :message (clojure.data.json/read-str
                 (String. ^bytes (read-body-bytes input-stream (int content-length)) "UTF-8")
                 :key-fn keyword)})))

(defn- read-message! [^PushbackInputStream input-stream]
  (when-let [first-byte (read-next-byte input-stream)]
    (if (= (int \{) first-byte)
      {:transport-format :line
       :message (clojure.data.json/read-str (read-json-line-text input-stream first-byte) :key-fn keyword)}
      (do
        (.unread input-stream first-byte)
        (read-framed-message! input-stream)))))

(defn- send-message! [^OutputStream output-stream transport-format payload]
  (case transport-format
    :line
    (let [line-bytes (.getBytes (str (core/format-json payload) "\n") "UTF-8")]
      (.write output-stream line-bytes)
      (.flush output-stream))

    (let [body-bytes (.getBytes (core/format-json payload) "UTF-8")
          header-bytes (.getBytes (str "Content-Length: " (count body-bytes) "\r\n\r\n") "UTF-8")]
      (.write output-stream header-bytes)
      (.write output-stream body-bytes)
      (.flush output-stream))))

(defn start-server-loop! [{:keys [max-indexes usage_metrics policy_registry]
                           :or {max-indexes default-max-indexes}}]
  (let [state (core/new-session-state {:max-indexes max-indexes
                                       :policy-registry policy_registry
                                       :usage-metrics usage_metrics})
        input-stream (PushbackInputStream. System/in 8)
        output-stream System/out]
    (loop []
      (let [outcome (try
                      (if-let [{:keys [transport-format message]} (read-message! input-stream)]
                        (do
                          (swap! state assoc :transport-format transport-format)
                          (when-let [response (core/handle-jsonrpc-message! state message)]
                            (send-message! output-stream transport-format response))
                          :continue)
                        :eof)
                      (catch Exception e
                        (if (= :protocol_error (:type (ex-data e)))
                          (do
                            (core/log! "mcp_protocol_error" (.getMessage e))
                            (send-message! output-stream
                                           (or (:transport-format @state) :headers)
                                           (core/jsonrpc-error nil -32700 (.getMessage e)))
                            :continue)
                          (do
                            (core/log! "mcp_internal_error" (.getMessage e))
                            (throw e)))))]
        (when (= :continue outcome)
          (recur))))))

(defn -main [& args]
  (let [{:keys [max_indexes policy_registry_file]} (parse-args args)
        max-indexes (or max_indexes
                        (some-> (System/getenv "SCI_MCP_MAX_INDEXES") parse-long)
                        default-max-indexes)
        policy-registry-file* (or policy_registry_file
                                  (System/getenv "SCI_MCP_POLICY_REGISTRY_FILE"))
        policy-registry (when (seq policy-registry-file*)
                          (rp/load-registry policy-registry-file*))
        usage-metrics (when-let [jdbc-url (System/getenv "SCI_USAGE_METRICS_JDBC_URL")]
                        (sci/postgres-usage-metrics {:jdbc-url jdbc-url
                                                     :user (System/getenv "SCI_USAGE_METRICS_DB_USER")
                                                     :password (System/getenv "SCI_USAGE_METRICS_DB_PASSWORD")}))]
    (core/log! "semantic_code_indexing_mcp_started" {:max_indexes max-indexes})
    (when usage-metrics
      (usage/safe-record-event! usage-metrics {:surface "mcp"
                                               :operation "server_start"
                                               :status "success"
                                               :payload {:max_indexes max-indexes}}))
    (start-server-loop! {:max-indexes max-indexes
                         :policy_registry policy-registry
                         :usage_metrics usage-metrics})))
