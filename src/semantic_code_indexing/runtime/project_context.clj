(ns semantic-code-indexing.runtime.project-context
  (:require [semantic-code-indexing.runtime.language-activation :as activation]))

(defn project-registry []
  (atom {}))

(defn merge-language-policy [server-policy request-policy]
  (activation/merge-language-policies server-policy request-policy))

(defn project-entry [registry root-path]
  (get @registry root-path))

(defn- activation-in-progress! [root-path entry]
  (throw (ex-info "language activation is already in progress for this project"
                  {:type :language_activation_in_progress
                   :message "language activation is already in progress for this project"
                   :details {:root_path root-path
                             :active_languages (:active_languages entry)
                             :detected_languages (:detected_languages entry)}})))

(defn refresh-project-index!
  [registry root-path build-fn]
  (let [claimed? (atom false)]
    (swap! registry
           (fn [state]
             (let [entry (get state root-path)]
               (cond
                 (= "activation_in_progress" (:activation_state entry))
                 (do
                   (reset! claimed? false)
                   state)

                 :else
                 (do
                   (reset! claimed? true)
                   (assoc state root-path (merge entry
                                                 {:activation_state "activation_in_progress"})))))))
    (when-not @claimed?
      (activation-in-progress! root-path (project-entry registry root-path)))
    (try
      (let [index (build-fn)
            entry {:root_path root-path
                   :index index
                   :snapshot_id (:snapshot_id index)
                   :detected_languages (:detected_languages index)
                   :active_languages (:active_languages index)
                   :supported_languages (:supported_languages index)
                   :language_fingerprint (:language_fingerprint index)
                   :activation_state (:activation_state index)
                   :selection_hint (:selection_hint index)
                   :manual_language_selection (:manual_language_selection index)}]
        (swap! registry assoc root-path entry)
        entry)
      (catch Exception e
        (swap! registry update root-path #(when % (assoc % :activation_state "refresh_required")))
        (throw e)))))

(defn ensure-project-index!
  [registry root-path request build-fn]
  (if-let [entry (project-entry registry root-path)]
    (do
      (when (= "activation_in_progress" (:activation_state entry))
        (activation-in-progress! root-path entry))
      (activation/ensure-request-languages-active! entry request)
      entry)
    (refresh-project-index! registry root-path build-fn)))
