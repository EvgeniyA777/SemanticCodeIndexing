(ns semidx.runtime.projections)

(def ^:private profile->wire
  {:structural "structural"
   :summary "summary"
   :selection "selection"
   :api-shape "api_shape"
   :detail "detail"
   :literal-slice "literal_slice"
   :diff "diff"})

(defn profile-name [profile]
  (or (get profile->wire profile)
      (name profile)))

(defn with-projection
  ([payload profile]
   (assoc payload :projection_profile (profile-name profile)))
  ([payload profile recommended-profile]
   (cond-> (with-projection payload profile)
     recommended-profile
     (assoc :recommended_projection_profile
            (profile-name recommended-profile)))))

(defn projection-meta
  ([profile]
   {:projection_profile (profile-name profile)})
  ([profile recommended-profile]
   (cond-> (projection-meta profile)
     recommended-profile
     (assoc :recommended_projection_profile
            (profile-name recommended-profile)))))
