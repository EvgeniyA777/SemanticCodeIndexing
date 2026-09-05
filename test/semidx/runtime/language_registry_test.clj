(ns semidx.runtime.language-registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [semidx.runtime.adapters :as adapters]
            [semidx.runtime.language-activation :as activation]
            [semidx.runtime.language-registry :as registry]
            [semidx.runtime.languages.typescript :as ts-language]
            [semidx.runtime.semantic-id :as semantic-id]
            [semidx.runtime.workspace-state :as ws]))

(def ^:private extension-examples
  [["src/app.clj" "clojure"]
   ["src/app.cljc" "clojure"]
   ["src/app.cljs" "clojure"]
   ["src/App.java" "java"]
   ["lib/app.ex" "elixir"]
   ["lib/app.exs" "elixir"]
   ["app/main.py" "python"]
   ["src/app.ts" "typescript"]
   ["src/app.tsx" "typescript"]
   ["src/app.js" "javascript"]
   ["src/app.jsx" "javascript"]
   ["src/app.mjs" "javascript"]
   ["src/app.cjs" "javascript"]
   ["src/app.lua" "lua"]
   ["src/app.zig" "zig"]
   ["public/index.html" "html"]
   ["public/index.htm" "html"]
   ["public/styles.css" "css"]])

(deftest language-registry-extension-detection-alignment-test
  (testing "registry, adapter facade, and semantic-id fallback agree"
    (doseq [[path expected] extension-examples]
      (is (= expected (registry/language-by-path path)) path)
      (is (= expected (adapters/language-by-path path)) path)
      (is (= expected (:language (semantic-id/public-shape {:path path
                                                            :kind "function"
                                                            :symbol "example/run"})))
          path)))
  (testing "unsupported paths remain unsupported"
    (is (nil? (registry/language-by-path "README.md")))
    (is (nil? (adapters/language-by-path "README.md")))
    (is (= "unknown" (:language (semantic-id/public-shape {:path "README.md"
                                                           :kind "section"
                                                           :symbol "README"}))))))

(deftest language-registry-public-metadata-alignment-test
  (is (= ["clojure" "java" "elixir" "python" "typescript" "javascript" "lua" "zig" "html" "css"]
         registry/supported-language-order))
  (is (= registry/supported-language-order
         (activation/supported-languages)))
  (is (= registry/provider-catalog ws/provider-catalog))
  (doseq [language registry/supported-language-order]
    (let [provider (registry/provider-for-language language)]
      (is (= "source" (:classification provider)) language)
      (is (seq (:provider_id provider)) language)
      (is (seq (:provider_version provider)) language))))

(deftest language-registry-ecmascript-test-path-classification-test
  (testing "shared helper recognizes TypeScript and JavaScript test suffixes"
    (doseq [path ["src/order.test.ts"
                  "src/order_test.tsx"
                  "src/order.spec.jsx"
                  "src/order_test.mjs"
                  "src/order.spec.cjs"
                  "test/helpers/order.js"]]
      (is (true? (registry/ecmascript-test-path? path)) path)))
  (testing "shared helper does not classify regular source files as tests"
    (doseq [path ["src/order.ts"
                  "src/order.js"
                  "src/test_helpers/order.js"]]
      (is (not (true? (registry/ecmascript-test-path? path))) path)))
  (testing "TypeScript parser consumes the shared helper"
    (let [parsed (ts-language/parse-file
                  "/tmp"
                  "src/order.spec.jsx"
                  ["export function verifiesOrder() {"
                   "  return true;"
                   "}"]
                  {})]
      (is (some #(and (= "src.order.spec/verifiesOrder" (:symbol %))
                      (= "test" (:kind %)))
                (:units parsed))))))
