(ns semidx.runtime.lsp-client
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.io BufferedInputStream BufferedOutputStream ByteArrayOutputStream EOFException)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent TimeUnit)))

(def ^:private default-timeout-ms 5000)
(def ^:private max-header-bytes 65536)
(def ^:private max-message-bytes (* 16 1024 1024))

(defn- command-vector [command]
  (cond
    (sequential? command) (mapv str command)
    (and (string? command) (not (str/blank? command))) [command]
    :else ["zls"]))

(defn write-message!
  "Write one Content-Length framed JSON-RPC message to an LSP stream."
  [^BufferedOutputStream out message]
  (let [payload (.getBytes (json/write-str message) StandardCharsets/UTF_8)
        header (.getBytes (str "Content-Length: " (alength payload) "\r\n\r\n")
                          StandardCharsets/US_ASCII)]
    (.write out header)
    (.write out payload)
    (.flush out)))

(defn- read-header-bytes [^BufferedInputStream in]
  (let [buffer (ByteArrayOutputStream.)]
    (loop [tail []]
      (let [value (.read in)]
        (when (neg? value)
          (throw (EOFException. "LSP stream closed while reading headers")))
        (.write buffer value)
        (when (> (.size buffer) max-header-bytes)
          (throw (ex-info "LSP header exceeded the bounded size"
                          {:type :lsp_header_too_large
                           :max_header_bytes max-header-bytes})))
        (let [tail* (->> (conj tail value) (take-last 4) vec)]
          (if (= [13 10 13 10] tail*)
            (.toByteArray buffer)
            (recur tail*)))))))

(defn- content-length [header-bytes]
  (let [header (String. ^bytes header-bytes StandardCharsets/US_ASCII)
        value (some (fn [line]
                      (let [[name raw] (str/split line #":" 2)]
                        (when (= "content-length" (some-> name str/lower-case str/trim))
                          (some-> raw str/trim Long/parseLong))))
                    (str/split-lines header))]
    (when-not (and value (not (neg? value)))
      (throw (ex-info "LSP message is missing a valid Content-Length header"
                      {:type :invalid_lsp_header})))
    (when (> value max-message-bytes)
      (throw (ex-info "LSP message exceeded the bounded size"
                      {:type :lsp_message_too_large
                       :content_length value
                       :max_message_bytes max-message-bytes})))
    value))

(defn- read-exactly [^BufferedInputStream in length]
  (let [payload (byte-array length)]
    (loop [offset 0]
      (if (= offset length)
        payload
        (let [read-count (.read in payload offset (- length offset))]
          (when (neg? read-count)
            (throw (EOFException. "LSP stream closed while reading a message body")))
          (recur (+ offset read-count)))))))

(defn read-message!
  "Read one Content-Length framed JSON-RPC message from an LSP stream."
  [^BufferedInputStream in]
  (let [length (content-length (read-header-bytes in))
        payload (read-exactly in length)]
    (json/read-str (String. ^bytes payload StandardCharsets/UTF_8)
                   :key-fn keyword)))

(defn- server-request-result [session {:keys [method params]}]
  (case method
    "workspace/configuration" (mapv (constantly nil) (or (:items params) []))
    "workspace/workspaceFolders" [{:uri (:root_uri session)
                                     :name (:root_name session)}]
    "client/registerCapability" nil
    "client/unregisterCapability" nil
    "window/workDoneProgress/create" nil
    nil))

(defn- respond-to-server-request! [session message]
  (write-message! (:out session)
                  {:jsonrpc "2.0"
                   :id (:id message)
                   :result (server-request-result session message)}))

(defn- await-response! [session request-id]
  (loop []
    (let [message (read-message! (:in session))]
      (cond
        (and (contains? message :id)
             (contains? message :method))
        (do (respond-to-server-request! session message)
            (recur))

        (= request-id (:id message))
        (if-let [error (:error message)]
          (throw (ex-info "LSP request failed"
                          {:type :lsp_request_failed
                           :request_id request-id
                           :error error}))
          (:result message))

        :else (recur)))))

(declare close-session!)

(defn request!
  ([session method params]
   (request! session method params (:timeout_ms session)))
  ([session method params timeout-ms]
   (locking (:lock session)
     (when @(:closed? session)
       (throw (ex-info "LSP session is closed" {:type :lsp_session_closed})))
     (let [request-id (swap! (:next_id session) inc)
           _ (write-message! (:out session)
                             {:jsonrpc "2.0"
                              :id request-id
                              :method method
                              :params (or params {})})
           response (future (await-response! session request-id))
           result (deref response (long (or timeout-ms default-timeout-ms)) ::timeout)]
       (if (= ::timeout result)
         (do
           (future-cancel response)
           (close-session! session)
           (throw (ex-info "LSP request timed out"
                           {:type :lsp_request_timeout
                            :method method
                            :timeout_ms timeout-ms})))
         result)))))

(defn notify! [session method params]
  (locking (:lock session)
    (when-not @(:closed? session)
      (write-message! (:out session)
                      {:jsonrpc "2.0"
                       :method method
                       :params (or params {})}))))

(defn close-session! [session]
  (when (compare-and-set! (:closed? session) false true)
    (try (.close ^BufferedOutputStream (:out session)) (catch Exception _))
    (try (.close ^BufferedInputStream (:in session)) (catch Exception _))
    (let [^Process process (:process session)]
      (.destroy process)
      (when-not (.waitFor process 250 TimeUnit/MILLISECONDS)
        (.destroyForcibly process)))))

(defn stop-session! [session]
  (when-not @(:closed? session)
    (try
      (request! session "shutdown" {} (min 1000 (:timeout_ms session)))
      (notify! session "exit" {})
      (catch Exception _))
    (close-session! session)))

(defn start-session!
  [{:keys [root_path command timeout_ms client_name]
    :or {timeout_ms default-timeout-ms
         client_name "semidx"}}]
  (let [root-file (.getCanonicalFile (java.io.File. (str root_path)))
        root-uri (str (.toURI root-file))
        command* (command-vector command)
        builder (doto (ProcessBuilder. ^java.util.List command*)
                  (.directory root-file)
                  (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD))
        process (.start builder)
        session {:process process
                 :in (BufferedInputStream. (.getInputStream process))
                 :out (BufferedOutputStream. (.getOutputStream process))
                 :next_id (atom 0)
                 :closed? (atom false)
                 :lock (Object.)
                 :root_uri root-uri
                 :root_name (.getName root-file)
                 :timeout_ms (long timeout_ms)
                 :command command*}]
    (try
      (let [result (request!
                    session
                    "initialize"
                    {:processId (.pid (java.lang.ProcessHandle/current))
                     :clientInfo {:name client_name :version "1"}
                     :rootUri root-uri
                     :workspaceFolders [{:uri root-uri :name (.getName root-file)}]
                     :capabilities {:workspace {:configuration true
                                                :workspaceFolders true}
                                    :textDocument {:documentSymbol
                                                   {:hierarchicalDocumentSymbolSupport true}}}})]
        (notify! session "initialized" {})
        (assoc session :server_capabilities (:capabilities result)
                       :server_info (:serverInfo result)))
      (catch Exception error
        (close-session! session)
        (throw error)))))

(defn text-document-symbols!
  [session root-path path text]
  (let [uri (str (.toURI (.getCanonicalFile (java.io.File. (str root-path) (str path)))))]
    (notify! session "textDocument/didOpen"
             {:textDocument {:uri uri
                             :languageId "zig"
                             :version 1
                             :text text}})
    (try
      (request! session "textDocument/documentSymbol"
                {:textDocument {:uri uri}})
      (finally
        (notify! session "textDocument/didClose"
                 {:textDocument {:uri uri}})))))
