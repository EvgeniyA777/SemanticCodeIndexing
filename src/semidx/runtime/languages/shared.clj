(ns semidx.runtime.languages.shared
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def ^:private tree-sitter-line-re
  #"^\s*(\d+):(\d+)\s*-\s*(\d+):(\d+)(\s+)(.+?)\s*$")

(def ^:private ansi-escape-re
  #"\u001B\[[0-9;]*m")

(def ^:private tree-sitter-diagnostic-languages
  #{"clojure" "elixir" "java" "typescript" "javascript"})

(defonce ^:private tree-sitter-availability (atom {}))
(defonce ^:private tree-sitter-config-cache (atom {}))

(defn slurp-lines [file]
  (-> file slurp str/split-lines vec))

(defn trim-signature [line]
  (let [t (str/trim (or line ""))]
    (subs t 0 (min 180 (count t)))))

(defn safe-line [lines n]
  (let [idx (dec (max 1 n))]
    (if (< idx (count lines))
      (trim-signature (nth lines idx))
      "")))

(defn unit-end-lines [starts total-lines]
  (let [pairs (partition 2 1 (concat starts [(inc total-lines)]))]
    (mapv (fn [[s n]] (max s (dec n))) pairs)))

(defn tail-token [token]
  (some-> token str (str/split #"[\./#]") last))

(defn- present-path [value]
  (let [s (some-> value str str/trim)]
    (when-not (str/blank? s) s)))

(defn- executable-file? [path]
  (let [f (io/file path)]
    (and (.exists f) (.isFile f) (.canExecute f))))

(defn- managed-tree-sitter-cli-path [parser-opts]
  (let [grammar-dir (or (present-path (:tree_sitter_grammars_dir parser-opts))
                        (present-path (:tree-sitter-grammars-dir parser-opts))
                        (present-path (System/getenv "SEMIDX_TREE_SITTER_GRAMMARS_DIR"))
                        (present-path (str (io/file ".tree-sitter-grammars"))))
        managed (some-> grammar-dir io/file (io/file "bin/tree-sitter") .getPath)]
    (when (and managed (executable-file? managed))
      managed)))

(defn tree-sitter-cli-path
  ([]
   (tree-sitter-cli-path {}))
  ([parser-opts]
   (or (present-path (:tree_sitter_cli_path parser-opts))
       (present-path (:tree-sitter-cli-path parser-opts))
       (present-path (System/getenv "SEMIDX_TREE_SITTER_CLI_PATH"))
       (managed-tree-sitter-cli-path parser-opts)
       "tree-sitter")))

(defn tree-sitter-available?
  ([]
   (tree-sitter-available? {}))
  ([parser-opts]
   (let [cli-path (tree-sitter-cli-path parser-opts)]
     (if (contains? @tree-sitter-availability cli-path)
       (get @tree-sitter-availability cli-path)
       (let [{:keys [exit]} (try
                              (sh/sh cli-path "--version")
                              (catch Exception _ {:exit 127}))
             available? (zero? (int exit))]
         (swap! tree-sitter-availability assoc cli-path available?)
         available?)))))

(defn parser-grammar-path [parser-opts lang]
  (or (get-in parser-opts [:tree_sitter_grammars lang])
      (get-in parser-opts [:tree_sitter_grammars (keyword lang)])
      (get parser-opts (keyword (str "tree_sitter_" (name lang) "_grammar")))
      (System/getenv (case lang
                       :clojure "SEMIDX_TREE_SITTER_CLOJURE_GRAMMAR_PATH"
                       :elixir "SEMIDX_TREE_SITTER_ELIXIR_GRAMMAR_PATH"
                       :java "SEMIDX_TREE_SITTER_JAVA_GRAMMAR_PATH"
                       :typescript "SEMIDX_TREE_SITTER_TYPESCRIPT_GRAMMAR_PATH"
                       :javascript "SEMIDX_TREE_SITTER_JAVASCRIPT_GRAMMAR_PATH"
                       nil))))

(defn parse-tree-sitter-line [line]
  (let [clean-line (str/replace (str line) ansi-escape-re "")]
    (when-let [[_ sr sc er ec spacing text] (re-find tree-sitter-line-re clean-line)]
      (let [plain (str/trim (first (str/split text #"`")))
            source (if (str/includes? plain ":")
                     (second (str/split plain #":\s*" 2))
                     plain)
            node-type (last (str/split (str/trim source) #"\s+"))
            value (some-> (re-find #"`([^`]*)`" text) second)]
        {:indent (count spacing)
         :start-row (parse-long sr)
         :start-col (parse-long sc)
         :end-row (parse-long er)
         :end-col (parse-long ec)
         :text text
         :node-type node-type
         :value value}))))

(defn tree-sitter-config-path
  ([grammar-path]
   (tree-sitter-config-path grammar-path nil))
  ([grammar-path config-name]
   (let [parser-dir (some-> grammar-path io/file .getCanonicalFile .getParent)
         escaped-dir (-> (str parser-dir)
                         (str/replace "\\" "\\\\")
                         (str/replace "\"" "\\\""))
         cache-key [config-name parser-dir]
         parser-dir-hash (Math/abs (hash (str parser-dir)))
         file-name (if config-name
                     (format "sci-tree-sitter-%s-%s.json" (name config-name) parser-dir-hash)
                     (format "sci-tree-sitter-%s.json" parser-dir-hash))]
     (or (get @tree-sitter-config-cache cache-key)
         (let [config-file (io/file (System/getProperty "java.io.tmpdir") file-name)]
           (spit config-file (format "{\"parser-directories\":[\"%s\"]}" escaped-dir))
           (swap! tree-sitter-config-cache assoc cache-key (.getPath config-file))
           (.getPath config-file))))))

(defn tree-sitter-cst
  ([abs-path grammar-path]
   (tree-sitter-cst abs-path grammar-path nil {}))
  ([abs-path grammar-path config-name]
   (tree-sitter-cst abs-path grammar-path config-name {}))
  ([abs-path grammar-path config-name parser-opts]
   (let [cli-path (tree-sitter-cli-path parser-opts)
         config-path (tree-sitter-config-path grammar-path config-name)
         tmpdir (System/getProperty "java.io.tmpdir")
         {:keys [exit out err]}
         (try
           (sh/sh cli-path "parse" "--cst" "--config-path" config-path "--grammar-path" grammar-path abs-path
                  ;; `:env` REPLACES the child environment rather than adding to
                  ;; it, so the overrides have to be merged onto the inherited
                  ;; one. Passing only the three keys dropped PATH, which a
                  ;; native tree-sitter binary survives but the npm-installed
                  ;; CLI does not: its `#!/usr/bin/env node` shebang then fails
                  ;; with "node: No such file or directory". That is why this
                  ;; only ever broke on CI.
                  :env (assoc (into {} (System/getenv))
                              "XDG_CACHE_HOME" (or (System/getenv "XDG_CACHE_HOME") tmpdir)
                              "TMPDIR" tmpdir))
           (catch Exception e
             {:exit 127 :out "" :err (.getMessage e)}))]
     (if (zero? (int exit))
       {:ok? true
        :lines (->> (str/split-lines out) (keep parse-tree-sitter-line) vec)
        :err nil}
       {:ok? false
        :lines []
        :err (or err "tree-sitter parse failed")}))))

(defn add-tree-sitter-diag
  ([parsed enabled?]
   (add-tree-sitter-diag parsed enabled? {}))
  ([parsed enabled? language-or-parser-opts]
   (if (map? language-or-parser-opts)
     (if enabled?
       (if (tree-sitter-available? language-or-parser-opts)
         (update parsed :diagnostics conj {:code "tree_sitter_probe"
                                           :summary "tree-sitter CLI detected."})
         (update parsed :diagnostics conj {:code "tree_sitter_unavailable"
                                           :summary "tree-sitter requested but CLI is unavailable; using adapter parser."}))
       parsed)
     (add-tree-sitter-diag parsed enabled? language-or-parser-opts {})))
  ([parsed enabled? language parser-opts]
   (if (and enabled? (tree-sitter-diagnostic-languages (str language)))
     (add-tree-sitter-diag parsed true parser-opts)
     parsed)))
