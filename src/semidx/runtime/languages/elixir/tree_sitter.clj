(ns semidx.runtime.languages.elixir.tree-sitter
  (:require [clojure.string :as str]
            [semidx.runtime.languages.elixir.shared :as shared]))

(def def-like-targets
  #{"def" "defp" "defmacro" "defmacrop" "defdelegate" "test" "defmodule"})

(defn- node-role [node]
  (some->> (:text node)
           (re-find #"^\s*([A-Za-z_]+):")
           second))

(defn- annotate-tree [nodes]
  (let [indexed-nodes (vec (map-indexed (fn [idx node] (assoc node :idx idx)) nodes))]
    (loop [remaining indexed-nodes
         stack []
         parents {}
         children {}]
    (if-let [node (first remaining)]
      (let [stack* (loop [s stack]
                     (if (and (seq s) (<= (:indent node) (:indent (peek s))))
                       (recur (pop s))
                       s))
            parent (peek stack*)
            parent-idx (:idx parent)
            children* (if (some? parent-idx)
                        (update children parent-idx (fnil conj []) (:idx node))
                        children)]
        (recur (next remaining)
               (conj stack* node)
               (cond-> parents
                 (some? parent-idx) (assoc (:idx node) parent-idx))
               children*))
      {:nodes indexed-nodes
       :parents parents
       :children children}))))

(defn- node-by-idx [tree idx]
  (nth (:nodes tree) idx))

(defn- child-idxs [tree idx]
  (get (:children tree) idx []))

(defn- child-nodes [tree idx]
  (mapv #(node-by-idx tree %) (child-idxs tree idx)))

(defn- find-child [tree idx pred]
  (some (fn [child]
          (when (pred child)
            child))
        (child-nodes tree idx)))

(defn- node-value [node]
  (str (:value node)))

(defn- direct-target-value [tree idx]
  (some-> (find-child tree idx #(= "target" (node-role %)))
          node-value))

(defn- first-descendant [tree idx pred]
  (loop [queue (seq (child-idxs tree idx))]
    (when-let [node-idx (first queue)]
      (let [node (node-by-idx tree node-idx)]
        (if (pred node)
          node
          (recur (concat (child-idxs tree node-idx) (rest queue))))))))

(defn- nearest-ancestor-call [tree idx target-set]
  (loop [current (get (:parents tree) idx)]
    (when current
      (let [node (node-by-idx tree current)
            target (when (= "call" (:node-type node))
                     (direct-target-value tree current))]
        (if (contains? target-set target)
          node
          (recur (get (:parents tree) current)))))))

(defn- module-call? [tree node]
  (and (= "call" (:node-type node))
       (= "defmodule" (direct-target-value tree (:idx node)))))

(defn- definition-call? [tree node]
  (and (= "call" (:node-type node))
       (contains? def-like-targets (direct-target-value tree (:idx node)))))

(defn- module-raw-name [src-lines start-row]
  (some->> (nth src-lines start-row "")
           (re-find shared/ex-module-re)
           second))

(defn- module-full-name [tree module-map node src-lines]
  (let [idx (:idx node)
        raw-name (module-raw-name src-lines (:start-row node))
        parent-module (nearest-ancestor-call tree idx #{"defmodule"})
        parent-name (some-> parent-module :idx module-map)]
    (cond
      (str/blank? raw-name) nil
      (str/includes? raw-name ".") raw-name
      (seq parent-name) (str parent-name "." raw-name)
      :else raw-name)))

(defn- call-body-lines [src-lines node]
  (subvec src-lines (:start-row node) (inc (:end-row node))))

(defn- signature-call [tree idx]
  (when-let [arguments-node (find-child tree idx #(= "arguments" (:node-type %)))]
    (first-descendant tree (:idx arguments-node) #(= "call" (:node-type %)))))

(defn- signature-name [tree idx]
  (when-let [sig (signature-call tree idx)]
    (direct-target-value tree (:idx sig))))

(defn- top-level-module-directive? [tree node]
  (let [target (direct-target-value tree (:idx node))
        owner (nearest-ancestor-call tree (:idx node) def-like-targets)]
    (and (contains? #{"alias" "import" "use"} target)
         (or (nil? owner)
             (= "defmodule" (direct-target-value tree (:idx owner)))))))

(defn- file-alias-map [src-lines tree]
  (reduce
   (fn [acc node]
     (if (and (= "call" (:node-type node))
              (top-level-module-directive? tree node)
              (= "alias" (direct-target-value tree (:idx node))))
       (reduce (fn [m [alias full]] (assoc m alias full))
               acc
               (or (shared/ex-alias-entries-for-line (nth src-lines (:start-row node) "") acc) []))
       acc))
   {}
   (:nodes tree)))

(defn- file-directive-targets [src-lines tree directive-re alias-map target-name]
  (->> (:nodes tree)
       (filter #(and (= "call" (:node-type %))
                     (top-level-module-directive? tree %)
                     (= target-name (direct-target-value tree (:idx %)))))
       (mapcat (fn [node]
                 (shared/ex-directive-targets [(nth src-lines (:start-row node) "")] directive-re alias-map)))
       distinct
       vec))

(defn parse-file [path src-lines cst-lines]
  (let [tree (annotate-tree cst-lines)
        module-nodes (filterv #(module-call? tree %) (:nodes tree))
        module-map (reduce (fn [acc node]
                             (assoc acc (:idx node) (module-full-name tree acc node src-lines)))
                           {}
                           module-nodes)
        module-name (some-> module-nodes first :idx module-map)
        alias-map (file-alias-map src-lines tree)
        import-modules (file-directive-targets src-lines tree shared/ex-import-only-re alias-map "import")
        use-modules (file-directive-targets src-lines tree shared/ex-use-only-re alias-map "use")
        call-expansion-modules (->> (concat import-modules use-modules) distinct vec)
        imports (->> (concat import-modules use-modules (vals alias-map)) distinct vec)
        test-target-modules (shared/ex-test-target-modules module-name imports use-modules path)
        def-nodes (filterv #(and (definition-call? tree %)
                                 (not= "defmodule" (direct-target-value tree (:idx %))))
                           (:nodes tree))
        local-call-arities (reduce
                            (fn [acc node]
                              (let [form (direct-target-value tree (:idx node))
                                    signature-line (nth src-lines (:start-row node) "")
                                    method-arity (when-not (= "test" form)
                                                   (shared/ex-def-arity signature-line))
                                    fn-name (when-not (= "test" form)
                                              (signature-name tree (:idx node)))]
                                (if (seq fn-name)
                                  (update acc fn-name (fnil conj #{}) method-arity)
                                  acc)))
                            {}
                            def-nodes)
        unit-records
        (mapv
         (fn [node]
           (let [form (direct-target-value tree (:idx node))
                 owner-module (some-> (nearest-ancestor-call tree (:idx node) #{"defmodule"})
                                      :idx
                                      module-map)
                 start-line (inc (:start-row node))
                 end-line (inc (:end-row node))
                 signature-line (nth src-lines (:start-row node) "")
                 body-lines (call-body-lines src-lines node)
                 body (str/join "\n" body-lines)
                 method-arity (when-not (= "test" form)
                                (shared/ex-def-arity signature-line))
                 unit-name (if (= "test" form)
                             ;; The test name lives on the string's
                             ;; `quoted_content` leaf, not on the `string` node
                             ;; itself, which carries no value. Reading the
                             ;; `string` node named every ExUnit test
                             ;; "test-unnamed", so the tree-sitter lane disagreed
                             ;; with the regex lane on unit identity for every
                             ;; test in the corpus. `string` is kept as a
                             ;; fallback for a grammar that inlines the text.
                             (let [descendant-value (fn [node-type]
                                                      (some-> (first-descendant tree (:idx node)
                                                                                #(= node-type (:node-type %)))
                                                              node-value))
                                   test-name (or (descendant-value "quoted_content")
                                                 (descendant-value "string")
                                                 "unnamed")]
                               (shared/ex-test-symbol owner-module test-name))
                             (str (or owner-module "Elixir.Unknown") "/" (signature-name tree (:idx node))))
                 call-arity-index (if (= "defdelegate" form)
                                    {}
                                    (shared/ex-call-arity-index body))]
             {:unit {:unit_id (cond-> (str path "::" unit-name)
                                (some? method-arity) (str "$arity" method-arity))
                     :kind (if (= "test" form)
                             "test"
                             (if (str/includes? path "/test/") "test" "function"))
                     :symbol unit-name
                     :path path
                     :module owner-module
                     :start_line start-line
                     :end_line end-line
                     :signature (shared/trim-signature signature-line)
                     :summary (str form " " unit-name)
                     :docstring_excerpt nil
                     :imports imports
                     :calls (if (= "defdelegate" form)
                              (shared/ex-delegate-calls body alias-map)
                              (shared/extract-ex-calls body owner-module alias-map call-expansion-modules local-call-arities call-arity-index))
                     :method_arity method-arity
                     :call_arity_by_token (if (= "defdelegate" form)
                                            {}
                                            call-arity-index)
                     :ex_form form
                     :parser_mode "full"}
              :use-expansion-imports
              (when (and (= "defmacro" form)
                         (= (str owner-module "/__using__") unit-name))
                (shared/ex-use-expansion-imports body alias-map))}))
         def-nodes)
        units (mapv :unit unit-records)
        use-expansion-imports (->> unit-records
                                   (mapcat #(or (:use-expansion-imports %) []))
                                   distinct
                                   vec)]
    (if (seq units)
      {:ok? true
       :result {:language "elixir"
                :module module-name
                :imports imports
                :use_modules use-modules
                :use_expansion_imports use-expansion-imports
                :test_target_modules test-target-modules
                :units units
                :diagnostics [{:code "tree_sitter_active"
                               :summary "Elixir analyzed using tree-sitter CST extraction."}]
                :parser_mode "full"}}
      {:ok? false
       :reason {:code "tree_sitter_no_units"
                :summary "tree-sitter did not extract Elixir units."}})))
