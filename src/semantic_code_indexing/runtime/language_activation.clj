(ns semantic-code-indexing.runtime.language-activation
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [semantic-code-indexing.runtime.adapters :as adapters]))

(def ^:private supported-language-order
  ["clojure" "java" "elixir" "python" "typescript"])

(def ^:private manifest-hints
  [{:path "deps.edn" :language "clojure"}
   {:path "project.clj" :language "clojure"}
   {:path "shadow-cljs.edn" :language "clojure"}
   {:path "pom.xml" :language "java"}
   {:path "build.gradle" :language "java"}
   {:path "build.gradle.kts" :language "java"}
   {:path "mix.exs" :language "elixir"}
   {:path "pyproject.toml" :language "python"}
   {:path "requirements.txt" :language "python"}
   {:path "setup.py" :language "python"}
   {:path "package.json" :language "typescript"}
   {:path "tsconfig.json" :language "typescript"}])

(def ^:private selection-hint-text
  "Choose a core project language from the supported list. Additional languages can be activated later via refresh or prewarm.")

(defn supported-languages []
  supported-language-order)

(defn- relative-path [root file]
  (let [root-path (.toPath (io/file root))
        file-path (.toPath (io/file file))]
    (-> (.relativize root-path file-path)
        (.normalize)
        str)))

(defn normalize-language-policy [policy]
  (let [supported (set supported-language-order)
        normalize-list (fn [xs]
                         (when (some? xs)
                           (->> xs
                                (keep #(some-> % str str/lower-case))
                                (filter supported)
                                distinct
                                vec)))]
    {:allow_languages (normalize-list (:allow_languages policy))
     :disable_languages (or (normalize-list (:disable_languages policy)) [])
     :prewarm_languages (or (normalize-list (:prewarm_languages policy)) [])}))

(defn merge-language-policies [server-policy request-policy]
  (let [server* (normalize-language-policy server-policy)
        request* (normalize-language-policy request-policy)
        server-allow (:allow_languages server*)
        request-allow (:allow_languages request*)
        allowed (cond
                  (and (some? server-allow) (some? request-allow))
                  (->> supported-language-order
                       (filter (set server-allow))
                       (filter (set request-allow))
                       vec)

                  (some? server-allow)
                  (vec server-allow)

                  (some? request-allow)
                  (vec request-allow)

                  :else
                  nil)
        allowed-set (set (or allowed supported-language-order))
        disabled (->> (concat (:disable_languages server*)
                              (:disable_languages request*))
                      distinct
                      vec)
        prewarm (->> (concat (:prewarm_languages server*)
                             (:prewarm_languages request*))
                     distinct
                     (filter allowed-set)
                     vec)]
    {:allow_languages allowed
     :disable_languages disabled
     :prewarm_languages prewarm}))

(defn- manifest-hint-entries [root-path]
  (->> manifest-hints
       (keep (fn [{:keys [path language]}]
               (when (.exists (io/file root-path path))
                 {:path path
                  :language language})))
       vec))

(defn discover-languages [root-path]
  (let [source-paths (->> (file-seq (io/file root-path))
                          (filter #(.isFile ^java.io.File %))
                          (map #(.getPath ^java.io.File %))
                          (map #(relative-path root-path %))
                          (filter adapters/source-path?)
                          vec)
        detected-language-set (->> source-paths
                                   (keep adapters/language-by-path)
                                   distinct
                                   set)
        manifest-entries (manifest-hint-entries root-path)
        manifest-language-set (->> manifest-entries (map :language) set)
        detected-languages (->> supported-language-order
                                (filter detected-language-set)
                                vec)
        detected-extensions (->> source-paths
                                 (map #(second (re-find #"\.([^.]+)$" %)))
                                 (keep identity)
                                 (map #(str "." %))
                                 distinct
                                 sort
                                 vec)
        language-counts (reduce (fn [acc path]
                                  (if-let [language (adapters/language-by-path path)]
                                    (update acc language (fnil inc 0))
                                    acc))
                                {}
                                source-paths)]
    {:supported_languages supported-language-order
     :source_paths source-paths
     :detected_languages detected-languages
     :detected_extensions detected-extensions
     :manifest_hints manifest-entries
     :manifest_languages (->> supported-language-order
                              (filter manifest-language-set)
                              vec)
     :language_counts language-counts}))

(defn resolve-activation [discovery language-policy]
  (let [policy (normalize-language-policy language-policy)
        allowed (set (or (:allow_languages policy) supported-language-order))
        manual-allow (set (or (:allow_languages policy) []))
        disabled (set (:disable_languages policy))
        prewarm (set (:prewarm_languages policy))
        detected (set (:detected_languages discovery))
        active-set (->> supported-language-order
                        (filter #(and (or (contains? detected %)
                                          (contains? prewarm %)
                                          (and (empty? detected)
                                               (contains? manual-allow %)))
                                      (contains? allowed %)
                                      (not (contains? disabled %))))
                        set)
        manual-selection? (boolean (and (empty? detected)
                                        (seq (:allow_languages policy))
                                        (seq active-set)))
        activation-state (cond
                           (and (empty? detected) (empty? active-set)) "awaiting_language_selection"
                           :else "ready")
        active-languages (->> supported-language-order
                              (filter active-set)
                              vec)
        fingerprint-payload {:detected_languages (:detected_languages discovery)
                             :active_languages active-languages
                             :detected_extensions (:detected_extensions discovery)
                             :manifest_hints (:manifest_hints discovery)
                             :allow_languages (:allow_languages policy)
                             :disable_languages (:disable_languages policy)
                             :prewarm_languages (:prewarm_languages policy)}
        language-fingerprint (str (hash (pr-str fingerprint-payload)))
        recommended-core (or (first (:manifest_languages discovery))
                             (first (:detected_languages discovery)))]
    {:supported_languages (:supported_languages discovery)
     :detected_languages (:detected_languages discovery)
     :detected_extensions (:detected_extensions discovery)
     :manifest_hints (:manifest_hints discovery)
     :active_languages active-languages
     :language_fingerprint language-fingerprint
     :activation_state activation-state
     :manual_language_selection manual-selection?
     :recommended_core_language recommended-core
     :selection_hint selection-hint-text
     :effective_language_policy policy}))

(defn active-source-paths [discovery activation]
  (let [active (set (:active_languages activation))]
    (->> (:source_paths discovery)
         (filter #(contains? active (adapters/language-by-path %)))
         vec)))

(defn no-supported-languages-details [discovery activation]
  {:supported_languages (:supported_languages discovery)
   :detected_languages (:detected_languages activation)
   :detected_extensions (:detected_extensions discovery)
   :detected_manifest_hints (:manifest_hints discovery)
   :recommended_core_language (:recommended_core_language activation)
   :selection_hint (:selection_hint activation)
   :activation_state "awaiting_language_selection"})

(defn activation-metadata [discovery activation]
  {:supported_languages (:supported_languages discovery)
   :detected_languages (:detected_languages activation)
   :detected_extensions (:detected_extensions discovery)
   :manifest_hints (:manifest_hints discovery)
   :active_languages (:active_languages activation)
   :language_fingerprint (:language_fingerprint activation)
   :activation_state (:activation_state activation)
   :manual_language_selection (:manual_language_selection activation)
   :recommended_core_language (:recommended_core_language activation)
   :selection_hint (:selection_hint activation)
   :effective_language_policy (:effective_language_policy activation)})

(defn requested-languages [{:keys [paths query]}]
  (let [path-languages (->> (concat (or paths [])
                                   (get-in query [:targets :paths] []))
                            (keep adapters/language-by-path)
                            distinct
                            vec)]
    path-languages))

(defn ensure-request-languages-active! [{:keys [active_languages supported_languages]} request]
  (let [requested (requested-languages request)
        active (set active_languages)
        supported (set supported_languages)
        inactive-requested (->> requested
                                (filter #(and (contains? supported %)
                                              (not (contains? active %))))
                                distinct
                                vec)]
    (when (seq inactive-requested)
      (throw (ex-info "request references languages that are not active in the current project context"
                      {:type :language_refresh_required
                       :message "request references languages that are not active in the current project context"
                       :details {:requested_languages requested
                                 :inactive_languages inactive-requested
                                 :active_languages active_languages
                                 :supported_languages supported_languages
                                 :recommended_action "refresh_project_context"}})))))
