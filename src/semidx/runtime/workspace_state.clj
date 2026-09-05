(ns semidx.runtime.workspace-state
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as set]
            [semidx.runtime.adapters :as adapters]
            [semidx.runtime.language-activation :as activation]
            [semidx.runtime.language-registry :as language-registry]))

(def provider-registry-version "4")
(def semantic-pipeline-version "1")

(def provider-catalog
  language-registry/provider-catalog)

(defn sha256-hex [^String s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest digest (.getBytes s "UTF-8"))]
    (letfn [(byte-to-hex [b]
              (format "%02x" b))]
      (apply str (map byte-to-hex hash-bytes)))))

(defn sha256-file [^java.io.File file]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        buffer (make-array Byte/TYPE 8192)]
    (with-open [is (io/input-stream file)]
      (loop []
        (let [read (.read is buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur)))))
    (let [hash-bytes (.digest digest)]
      (letfn [(byte-to-hex [b]
                (format "%02x" b)) ]
        (apply str (map byte-to-hex hash-bytes))))))

(defn- file-modified-at [^java.io.File file]
  (let [instant (java.time.Instant/ofEpochMilli (.lastModified file))]
    (str instant)))

(defn compute-workspace-fingerprint
  [discovery-profile-hash provider-registry-version* semantic-pipeline-version* files]
  (let [sorted-files (sort-by :path files)
        fingerprint-data {:discovery_profile_hash discovery-profile-hash
                          :provider_registry_version provider-registry-version*
                          :semantic_pipeline_version semantic-pipeline-version*
                          :files (mapv #(select-keys % [:path :content_digest :provider_id :provider_version]) sorted-files)}
        serialized (pr-str fingerprint-data)]
    (str "sha256:" (sha256-hex serialized))))

(defn capture-workspace-state
  [root-path discovery-profile provider-catalog-version & [prior-workspace-state allowed-paths]]
  ;; `allowed-paths`, when supplied, restricts the manifest to the exact set of
  ;; paths the indexer will actually index (i.e. the active language set). This
  ;; keeps freshness deltas from including files excluded by `:language_policy`.
  (let [discovery-profile-hash (sha256-hex (pr-str discovery-profile))
        allowed (when (seq allowed-paths) (set allowed-paths))
        paths (cond->> (activation/source-files root-path)
                allowed (filter allowed))
        prior-files (when prior-workspace-state
                      (into {} (map (juxt :path identity) (:files prior-workspace-state))))
        files (keep (fn [path]
                      (let [file (io/file root-path path)]
                        (when (.exists file)
                          (let [size (.length file)
                                mtime (file-modified-at file)
                                language (adapters/language-by-path path)
                                provider (get provider-catalog language)
                                prior-file (get prior-files path)
                                content-digest (if (and prior-file
                                                        (= (:size_bytes prior-file) size)
                                                        (= (:modified_at prior-file) mtime))
                                                 (:content_digest prior-file)
                                                 (str "sha256:" (sha256-file file)))]
                            (merge
                             {:path path
                              :content_digest content-digest
                              :size_bytes size
                              :modified_at mtime}
                             provider)))))
                    paths)
        sorted-files (vec (sort-by :path files))
        fingerprint (compute-workspace-fingerprint
                     discovery-profile-hash
                     provider-registry-version
                     semantic-pipeline-version
                     sorted-files)]
    {:schema_version            "1"
     :root_path                 root-path
     :discovery_profile_hash    discovery-profile-hash
     :provider_registry_version provider-registry-version
     :semantic_pipeline_version semantic-pipeline-version
     :files                     sorted-files
     :workspace_fingerprint     fingerprint}))

(defn diff-workspace-state
  [previous current]
  (let [prev-files (into {} (map (juxt :path identity) (:files previous)))
        curr-files (into {} (map (juxt :path identity) (:files current)))
        
        prev-paths (set (keys prev-files))
        curr-paths (set (keys curr-files))
        
        added (sort (vec (set/difference curr-paths prev-paths)))
        deleted (sort (vec (set/difference prev-paths curr-paths)))
        
        common (set/intersection prev-paths curr-paths)
        
        changed (->> common
                     (filter (fn [path]
                               (not= (get-in prev-files [path :content_digest])
                                     (get-in curr-files [path :content_digest]))))
                     sort
                     vec)
        
        unchanged (->> common
                       (filter (fn [path]
                                 (= (get-in prev-files [path :content_digest])
                                    (get-in curr-files [path :content_digest]))))
                       sort
                       vec)
        
        compatibility-changes (cond-> []
                                (not= (:discovery_profile_hash previous) (:discovery_profile_hash current))
                                (conj :discovery_profile_hash_changed)
                                (not= (:provider_registry_version previous) (:provider_registry_version current))
                                (conj :provider_registry_version_changed)
                                (not= (:semantic_pipeline_version previous) (:semantic_pipeline_version current))
                                (conj :semantic_pipeline_version_changed))]
    {:added_paths added
     :changed_paths changed
     :deleted_paths deleted
     :unchanged_paths unchanged
     :compatibility_changes compatibility-changes}))
