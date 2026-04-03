(ns semidx.runtime.compression
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [semidx.runtime.projections :as projections])
  (:import [java.math BigInteger]
           [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(def ^:private schema-version "1.0")
(def ^:private default-markdown-path "docs/code-context.md")
(def ^:private default-json-path ".ccc/repo-map.json")
(def ^:private default-dot-path ".ccc/dependency-graph.dot")
(def ^:private default-cache-path ".ccc/cache.edn")
(def ^:private default-state-path ".ccc/state.edn")
(def ^:private relevant-prefixes ["src/" "test/" "resources/"])
(def ^:private manifest-paths ["deps.edn" "project.clj" "bb.edn"])
(def ^:private category-order ["app/core" "api" "service" "domain" "db" "infra" "util" "test" "other"])
(def ^:private hook-marker "SEMIDX_CCC_PRE_PUSH")

(defn- sha1-bytes [^bytes bytes]
  (let [digest (MessageDigest/getInstance "SHA-1")]
    (.update digest bytes)
    (format "%040x" (BigInteger. 1 (.digest digest)))))

(defn- sha1-str [value]
  (sha1-bytes (.getBytes (str value) StandardCharsets/UTF_8)))

(defn- root-file [root-path rel-path]
  (io/file root-path rel-path))

(defn- existing-file? [root-path rel-path]
  (.exists (root-file root-path rel-path)))

(defn- ensure-parent-dir! [path]
  (some-> path io/file .getParentFile .mkdirs))

(defn- read-edn-file [path]
  (when (.exists (io/file path))
    (with-open [rdr (java.io.PushbackReader. (io/reader path))]
      (edn/read rdr))))

(defn- write-edn-file! [path data]
  (ensure-parent-dir! path)
  (spit path (pr-str data)))

(defn- write-json-file! [path data]
  (ensure-parent-dir! path)
  (with-open [w (io/writer path)]
    (json/write data w :indent true)))

(defn- write-text-file! [path content]
  (ensure-parent-dir! path)
  (spit path content))

(defn- project-name [root-path]
  (let [name (-> root-path io/file .getCanonicalFile .getName)]
    (if (str/blank? name) "project" name)))

(defn- relevant-source-path? [path]
  (or (some #(str/starts-with? (str path) %) relevant-prefixes)
      (some #(= (str path) %) manifest-paths)))

(defn- relevant-paths [index]
  (let [root-path (:root_path index)]
    (->> (concat manifest-paths (keys (:files index)))
         (filter #(or (contains? #{"deps.edn" "project.clj" "bb.edn"} %)
                      (relevant-source-path? %)))
         (filter #(existing-file? root-path %))
         distinct
         sort
         vec)))

(defn- read-rel-file [root-path rel-path]
  (slurp (root-file root-path rel-path)))

(defn source-fingerprint [index]
  (let [root-path (:root_path index)
        parts (->> (relevant-paths index)
                   (map (fn [path]
                          (str path "\n" (read-rel-file root-path path))))
                   (str/join "\n--FILE--\n"))]
    (sha1-str parts)))

(defn- truncate-path [path max-depth]
  (->> (str/split (str path) #"/")
       (take max-depth)
       (str/join "/")))

(defn- insert-tree-path [tree path]
  (let [parts (str/split path #"/")]
    (if (empty? parts)
      tree
      (update-in tree parts #(or % {})))))

(defn- tree-map [paths max-depth]
  (reduce (fn [acc path]
            (insert-tree-path acc (truncate-path path max-depth)))
          {}
          paths))

(defn- render-tree-lines
  ([tree] (render-tree-lines tree ""))
  ([tree prefix]
   (let [entries (sort-by key tree)
         last-idx (dec (count entries))]
     (mapcat (fn [[idx [label children]]]
               (let [last? (= idx last-idx)
                     branch (if last? "└ " "├ ")
                     next-prefix (str prefix (if last? "  " "│ "))
                     line (str prefix branch label)]
                 (if (seq children)
                   (cons line (render-tree-lines children next-prefix))
                   [line])))
             (map-indexed vector entries)))))

(defn- tree-lines [index]
  (let [paths (relevant-paths index)
        tree (tree-map paths 3)]
    (vec (cons (project-name (:root_path index))
               (render-tree-lines tree)))))

(defn- require-aliases [content]
  (->> (re-seq #"\[([A-Za-z0-9\.\-_]+)\s+:as\s+([A-Za-z0-9\.\-_]+)\]" content)
       (map (fn [[_ dep alias]] [alias dep]))
       (into (sorted-map))))

(defn- namespace-category [path namespace]
  (let [path* (str/lower-case (str path))
        ns* (str/lower-case (str namespace))]
    (cond
      (or (str/includes? path* "/test/")
          (str/starts-with? path* "test/")
          (str/ends-with? path* "_test.clj")
          (str/ends-with? ns* "-test")) "test"
      (or (str/includes? path* "/api/")
          (str/includes? ns* ".api.")) "api"
      (or (str/includes? path* "/service/")
          (str/includes? ns* ".service.")) "service"
      (or (str/includes? path* "/domain/")
          (str/includes? ns* ".domain.")) "domain"
      (or (str/includes? path* "/db/")
          (str/includes? path* "/repo/")
          (str/includes? ns* ".db.")) "db"
      (or (str/includes? path* "/infra/")
          (str/includes? ns* ".infra.")) "infra"
      (or (str/includes? path* "/util/")
          (str/includes? ns* ".util.")) "util"
      (or (str/ends-with? ns* ".core")
          (str/includes? path* "/core.clj")
          (str/includes? path* "/main.clj")) "app/core"
      :else "other")))

(defn- top-symbols-for-module [index module]
  (->> (vals (:units index))
       (filter #(= module (:module %)))
       (sort-by (juxt :start_line :symbol))
       (keep (fn [u]
               (when-let [sym (:symbol u)]
                 (cond-> {:symbol sym
                          :kind (:kind u)}
                   (:dispatch_value u) (assoc :dispatch_value (:dispatch_value u))))))
       distinct
       (take 12)
       vec))

(defn- namespace-entry [index path {:keys [module imports] :as _file-rec}]
  (let [content (read-rel-file (:root_path index) path)]
    {:namespace module
     :path path
     :requires (vec (sort (distinct (or imports []))))
     :aliases (require-aliases content)
     :category (namespace-category path module)
     :symbols (top-symbols-for-module index module)}))

(defn- namespace-index [index]
  (->> (:files index)
       (filter (fn [[path file-rec]]
                 (and (= "clojure" (:language file-rec))
                      (:module file-rec)
                      (relevant-source-path? path))))
       (sort-by key)
       (mapv (fn [[path file-rec]]
               (namespace-entry index path file-rec)))))

(defn- dependency-edges [namespace-entries]
  (->> namespace-entries
       (mapcat (fn [{:keys [namespace requires]}]
                 (for [dep requires]
                   {:from namespace :to dep})))
       distinct
       (sort-by (juxt :from :to))
       vec))

(defn- symbol-name [qualified-symbol]
  (some-> (str qualified-symbol) (str/split #"/") last))

(defn- scan-domain-model [index]
  (->> (:files index)
       (filter (fn [[path file-rec]]
                 (and (= "clojure" (:language file-rec))
                      (relevant-source-path? path))))
       (mapcat (fn [[path _file-rec]]
                 (let [content (read-rel-file (:root_path index) path)
                       records (for [[_ kind name] (re-seq #"(?m)^\s*\((defrecord|defprotocol|deftype)\s+([^\s\[\)\]]+)" content)]
                                 {:name name
                                  :kind (case kind
                                          "defrecord" "record"
                                          "defprotocol" "protocol"
                                          "deftype" "type")
                                  :path path})
                       schemas (for [[_ kind name] (re-seq #"(?m)^\s*\((defschema|s/def|m/=>|m/schema)\s+([^\s\[\)\]]+)" content)]
                                 {:name name
                                  :kind (case kind
                                          "defschema" "schema"
                                          "s/def" "spec"
                                          "m/schema" "malli-schema"
                                          "m/=>" "malli-schema")
                                  :path path})]
                   (concat records schemas))))
       distinct
       (sort-by (juxt :kind :name :path))
       vec))

(defn- ring-handler-symbols [content]
  (->> (re-seq #"(?m)^\s*\(def\s+([^\s\)]+).*\bring-handler\b" content)
       (map second)))

(defn- scan-entrypoints [index namespace-entries]
  (->> namespace-entries
       (mapcat (fn [{:keys [namespace path symbols]}]
                 (let [content (read-rel-file (:root_path index) path)
                       mains (for [{:keys [symbol]} symbols
                                   :when (str/ends-with? (str symbol) "/-main")]
                               (str symbol))
                       ring-vars (for [local (ring-handler-symbols content)]
                                   (str namespace "/" local))]
                   (concat mains ring-vars))))
       distinct
       sort
       vec))

(defn- category-map [namespace-entries]
  (reduce (fn [acc {:keys [category namespace]}]
            (update acc category (fnil conj []) namespace))
          (zipmap category-order (repeat []))
          namespace-entries))

(defn- bounded-lines [heading items render-item limit]
  (cond-> [heading]
    true
    (into (if (seq items)
            (map render-item (take limit items))
            ["- none"]))
    (> (count items) limit)
    (conj (str "- ... +" (- (count items) limit) " more"))))

(defn- format-symbol-line [{:keys [symbol kind dispatch_value]}]
  (str "- " (symbol-name symbol)
       (when (seq kind) (str " [" kind "]"))
       (when (seq dispatch_value) (str " " dispatch_value))))

(defn- summary-lines [artifact]
  (let [namespace-entries (:namespaces artifact)
        category-lines (mapcat (fn [category]
                                 (bounded-lines (str "### " category)
                                                (get (:namespace_categories artifact) category)
                                                #(str "- " %)
                                                8))
                               category-order)
        dependency-lines (bounded-lines "## Dependency Graph"
                                        (:dependency_edges artifact)
                                        #(str "- " (:from %) " -> " (:to %))
                                        20)
        domain-lines (bounded-lines "## Domain Model"
                                    (:domain_model artifact)
                                    #(str "- " (:name %) " [" (:kind %) "]")
                                    20)
        entrypoint-lines (bounded-lines "## Entry Points"
                                        (:entrypoints artifact)
                                        #(str "- " %)
                                        12)
        namespace-lines (mapcat (fn [{:keys [namespace path requires aliases symbols]}]
                                  (concat
                                   [(str "### " namespace)
                                    (str "- path: " path)
                                    (str "- requires: " (if (seq requires)
                                                          (str/join ", " requires)
                                                          "none"))
                                    (str "- aliases: " (if (seq aliases)
                                                         (str/join ", " (map (fn [[alias dep]]
                                                                               (str alias " -> " dep))
                                                                             aliases))
                                                         "none"))
                                    "- symbols:"]
                                   (if (seq symbols)
                                     (map format-symbol-line (take 8 symbols))
                                     ["- none"])))
                                (take 12 namespace-entries))]
    (vec
     (concat
      [(str "# Code Context: " (:project_name artifact))
       ""
       (str "- fingerprint: " (:source_fingerprint artifact))
       ""
       "## Tree"
       "```text"]
      (:tree_lines artifact)
      ["```"
       ""]
      entrypoint-lines
      ["" "## Namespace Categories"]
      category-lines
      [""]
      domain-lines
      [""]
      dependency-lines
      ["" "## Namespaces"]
      namespace-lines))))

(defn render-dot [artifact]
  (str/join
   "\n"
   (concat
    ["digraph dependency_graph {"]
    (for [{:keys [from to]} (:dependency_edges artifact)]
      (str "  \"" from "\" -> \"" to "\";"))
    ["}"])))

(defn render-markdown [artifact]
  (str (str/join "\n" (:summary_lines artifact)) "\n"))

(defn default-artifact-paths [root-path]
  {:markdown_path (.getPath (root-file root-path default-markdown-path))
   :json_path (.getPath (root-file root-path default-json-path))
   :dot_path (.getPath (root-file root-path default-dot-path))
   :cache_path (.getPath (root-file root-path default-cache-path))
   :state_path (.getPath (root-file root-path default-state-path))})

(defn compress-project
  ([index] (compress-project index {}))
  ([index _opts]
   (let [namespaces (namespace-index index)
         deps (dependency-edges namespaces)
         domain-model (scan-domain-model index)
         entrypoints (scan-entrypoints index namespaces)
         artifact {:schema_version schema-version
                   :project_name (project-name (:root_path index))
                   :root_path (:root_path index)
                   :snapshot_id (:snapshot_id index)
                   :indexed_at (:indexed_at index)
                   :source_fingerprint (source-fingerprint index)
                   :tree_lines (tree-lines index)
                   :namespaces namespaces
                   :dependency_edges deps
                   :entrypoints entrypoints
                   :domain_model domain-model
                   :namespace_categories (category-map namespaces)}
         summary (summary-lines artifact)]
     (projections/with-projection
      (assoc artifact
             :summary_lines summary
             :summary_markdown (render-markdown (assoc artifact :summary_lines summary))
             :dependency_graph_dot (render-dot artifact))
      :summary
      :selection))))

(defn compression-drift-report
  ([index] (compression-drift-report index {}))
  ([index opts]
   (let [artifact (compress-project index opts)
         paths (merge (default-artifact-paths (:root_path index))
                      (select-keys opts [:markdown_path :json_path :dot_path :cache_path :state_path]))
         markdown-path (:markdown_path paths)
         actual (when (.exists (io/file markdown-path))
                  (slurp markdown-path))
         expected (:summary_markdown artifact)]
     {:stale? (not= expected actual)
      :artifact artifact
      :expected_markdown expected
      :actual_markdown actual
      :paths paths})))

(defn refresh-project-compression
  ([index] (refresh-project-compression index {}))
  ([index opts]
   (let [paths (merge (default-artifact-paths (:root_path index))
                      (select-keys opts [:markdown_path :json_path :dot_path :cache_path :state_path]))
         changed-only? (boolean (:changed_only opts))
         state (read-edn-file (:state_path paths))
         current-fingerprint (source-fingerprint index)
         markdown-exists? (.exists (io/file (:markdown_path paths)))
         unchanged? (and changed-only?
                         markdown-exists?
                         (= current-fingerprint (:source_fingerprint state)))]
     (if unchanged?
       {:status "unchanged"
        :changed? false
        :source_fingerprint current-fingerprint
        :paths paths}
       (let [artifact (compress-project index opts)
             state* {:schema_version schema-version
                     :root_path (:root_path index)
                     :snapshot_id (:snapshot_id index)
                     :source_fingerprint current-fingerprint
                     :artifact_paths paths}]
         (write-text-file! (:markdown_path paths) (:summary_markdown artifact))
         (write-json-file! (:json_path paths) artifact)
         (write-text-file! (:dot_path paths) (:dependency_graph_dot artifact))
         (write-edn-file! (:cache_path paths) artifact)
         (write-edn-file! (:state_path paths) state*)
         {:status "refreshed"
          :changed? true
          :artifact artifact
          :source_fingerprint current-fingerprint
          :paths paths})))))

(defn- hook-script []
  (str "#!/bin/sh\n"
       "# " hook-marker "\n"
       "set -eu\n"
       "repo_root=$(git rev-parse --show-toplevel)\n"
       "cd \"$repo_root\"\n"
       "clojure -M:ccc refresh --root \"$repo_root\" --changed >/dev/null\n"
       "if ! git diff --quiet -- docs/code-context.md; then\n"
       "  echo \"code context summary changed; stage docs/code-context.md and retry push\" >&2\n"
       "  exit 1\n"
       "fi\n"))

(defn install-pre-push-hook!
  ([root-path] (install-pre-push-hook! root-path {}))
  ([root-path {:keys [force]}]
   (let [hook-path (io/file root-path ".git" "hooks" "pre-push")
         existing (when (.exists hook-path) (slurp hook-path))
         ours? (and existing (str/includes? existing hook-marker))]
     (cond
       (and (.exists hook-path) existing (not ours?) (not force))
       {:installed? false
        :hook_path (.getPath hook-path)
        :reason "hook_exists"}

       :else
       (do
         (.mkdirs (.getParentFile hook-path))
         (spit hook-path (hook-script))
         (.setExecutable hook-path true)
         {:installed? true
          :hook_path (.getPath hook-path)
          :reason (if ours? "hook_updated" "hook_created")})))))

(defn init-project-compression!
  ([index] (init-project-compression! index {}))
  ([index opts]
   (let [refresh-result (refresh-project-compression index opts)
         install-hook? (not (false? (:install_hook opts)))
         hook-result (if install-hook?
                       (install-pre-push-hook! (:root_path index) opts)
                       {:installed? false
                        :reason "hook_skipped"})]
     {:refresh refresh-result
      :hook hook-result})))
