(ns semidx.runtime.lsp-client-test
  (:require [clojure.test :refer [deftest is testing]]
            [semidx.runtime.lsp-client :as lsp])
  (:import (java.io BufferedInputStream BufferedOutputStream ByteArrayInputStream ByteArrayOutputStream)))

(deftest content-length-frame-round-trip-test
  (let [sink (ByteArrayOutputStream.)
        message {:jsonrpc "2.0"
                 :id 7
                 :method "textDocument/documentSymbol"
                 :params {:textDocument {:uri "file:///tmp/main.zig"}}}]
    (with-open [out (BufferedOutputStream. sink)]
      (lsp/write-message! out message))
    (with-open [in (BufferedInputStream. (ByteArrayInputStream. (.toByteArray sink)))]
      (is (= message (lsp/read-message! in))))))

(deftest malformed-frame-is-rejected-test
  (testing "a body without Content-Length cannot be consumed as LSP"
    (let [bytes (.getBytes "Content-Type: application/json\r\n\r\n{}" "UTF-8")]
      (with-open [in (BufferedInputStream. (ByteArrayInputStream. bytes))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Content-Length"
                              (lsp/read-message! in)))))))

(deftest oversized-frame-is-rejected-before-allocation-test
  (let [bytes (.getBytes "Content-Length: 16777217\r\n\r\n" "UTF-8")]
    (with-open [in (BufferedInputStream. (ByteArrayInputStream. bytes))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"bounded size"
                            (lsp/read-message! in))))))
