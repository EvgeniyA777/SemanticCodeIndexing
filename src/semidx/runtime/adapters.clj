(ns semidx.runtime.adapters
  (:require [clojure.java.io :as io]
            [semidx.runtime.languages.clojure :as clj-language]
            [semidx.runtime.languages.css :as css-language]
            [semidx.runtime.languages.html :as html-language]
            [semidx.runtime.languages.javascript :as js-language]
            [semidx.runtime.languages.java :as java-language]
            [semidx.runtime.languages.lua :as lua-language]
            [semidx.runtime.languages.python :as py-language]
            [semidx.runtime.languages.shared :as shared-language]
            [semidx.runtime.languages.typescript :as ts-language]
            [semidx.runtime.languages.zig :as zig-language]
            [semidx.runtime.language-registry :as language-registry]
            [semidx.runtime.semantic-ir :as semantic-ir]))

(defn language-by-path [path]
  (language-registry/language-by-path path))

(defn source-path? [path]
  (language-registry/source-path? path))

(defn with-parser-context
  "Run f with per-index parser resources, such as a shared Zig ZLS session."
  [root-path paths parser-opts f]
  (zig-language/with-parser-context root-path paths parser-opts f))

(defn- slurp-lines [file]
  (shared-language/slurp-lines file))

(defn- trim-signature [line]
  (shared-language/trim-signature line))

(defn- fallback-unit [path lines language reason]
  (let [line-count (max 1 (count lines))]
    {:language (or language "unknown")
     :module nil
     :imports []
     :units [{:unit_id (str path "::fallback")
              :kind "section"
              :symbol (str path "::fallback")
              :path path
              :module nil
              :start_line 1
              :end_line line-count
              :signature (if (seq lines) (trim-signature (first lines)) "")
              :summary "fallback section"
              :docstring_excerpt nil
              :imports []
              :calls []
              :parser_mode "fallback"}]
     :diagnostics [{:code "parser_fallback" :summary reason}]
     :parser_mode "fallback"}))

(defn- parse-elixir-language-file [root-path file-path lines parser-opts]
  ((requiring-resolve 'semidx.runtime.languages.elixir/parse-file)
   root-path
   file-path
   lines
   parser-opts))

(defn- parse-html [root-path file-path lines parser-opts]
  (html-language/parse-file root-path file-path lines parser-opts))

(defn- parse-css [root-path file-path lines parser-opts]
  (css-language/parse-file root-path file-path lines parser-opts))

(defn parse-file
  ([root-path file-path] (parse-file root-path file-path {}))
  ([root-path file-path parser-opts]
   (let [abs (io/file root-path file-path)
         language (language-by-path file-path)]
     (try
       (let [lines (slurp-lines abs)]
         (->> (case language
                "clojure" (clj-language/parse-file root-path file-path lines parser-opts)
                "java" (java-language/parse-file root-path file-path lines parser-opts)
                "elixir" (parse-elixir-language-file root-path file-path lines parser-opts)
                "python" (py-language/parse-file root-path file-path lines parser-opts)
                "typescript" (ts-language/parse-file root-path file-path lines parser-opts)
                "javascript" (js-language/parse-file root-path file-path lines parser-opts)
                "lua" (lua-language/parse-file root-path file-path lines parser-opts)
                "zig" (zig-language/parse-file root-path file-path lines parser-opts)
                "html" (parse-html root-path file-path lines parser-opts)
                "css" (parse-css root-path file-path lines parser-opts)
                (fallback-unit file-path lines language "unsupported_language"))
              (semantic-ir/finalize-parsed-file file-path language)))
       (catch Exception _
         (let [lines (try (slurp-lines abs) (catch Exception _ []))]
           (semantic-ir/finalize-parsed-file
            file-path
            language
            (fallback-unit file-path lines language "parse_exception"))))))))
