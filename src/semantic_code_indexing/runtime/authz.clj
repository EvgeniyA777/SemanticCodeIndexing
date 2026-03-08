(ns semantic-code-indexing.runtime.authz
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- map-get [m k]
  (or (get m k)
      (when (keyword? k) (get m (name k)))
      (when (string? k) (get m (keyword k)))))

(defn- operation-name [op]
  (if (keyword? op)
    (-> op name (str/replace "-" "_"))
    (str (or op "operation"))))

(defn- canonical-path [path]
  (-> (io/file (str (or path "."))) .getCanonicalPath))

(defn- path-prefix? [candidate prefix]
  (.startsWith (.normalize (.toPath (io/file candidate)))
               (.normalize (.toPath (io/file prefix)))))

(defn- normalize-relative-path [raw]
  (let [input (-> (str raw)
                  str/trim
                  (str/replace "\\" "/")
                  (str/replace #"^\./+" ""))]
    (cond
      (str/blank? input) nil
      (= "." input) nil
      (str/starts-with? input "/") nil
      (re-find #"^[A-Za-z]:/" input) nil
      :else
      (let [segments (->> (str/split input #"/")
                          (remove str/blank?)
                          vec)]
        (if (or (empty? segments)
                (some #{".."} segments))
          nil
          (str/join "/" segments))))))

(defn- normalize-path-prefix [raw]
  (let [v (-> (str (or raw "")) str/trim)]
    (cond
      (or (str/blank? v) (= v "*") (= v ".")) :all
      :else (normalize-relative-path v))))

(defn- path-allowed? [path allowed-prefix]
  (or (= :all allowed-prefix)
      (= path allowed-prefix)
      (str/starts-with? path (str allowed-prefix "/"))))

(defn- tenant-rules [policy tenant-id]
  (let [tenants (or (map-get policy :tenants) {})]
    (or (map-get tenants tenant-id)
        (map-get tenants (keyword tenant-id)))))

(defn policy-authorizer [policy]
  (fn [{:keys [tenant_id root_path paths operation]}]
    (let [tenant-id (some-> tenant_id str str/trim)
          op-name (operation-name operation)]
      (try
        (cond
          (str/blank? tenant-id)
          {:allowed? false
           :code :invalid_request
           :message "x-tenant-id is required for authz policy checks"}

          :else
          (if-let [rules (tenant-rules policy tenant-id)]
            (let [request-root (canonical-path (or root_path "."))
                  raw-allowed-roots (vec (or (map-get rules :allowed_roots) []))
                  allowed-roots (mapv canonical-path raw-allowed-roots)
                  raw-path-prefixes (vec (or (map-get rules :allowed_path_prefixes) []))
                  has-path-rules? (seq raw-path-prefixes)
                  path-prefixes (mapv normalize-path-prefix raw-path-prefixes)]
              (cond
                (empty? allowed-roots)
                {:allowed? false
                 :code :forbidden
                 :message (str "tenant " tenant-id " has no allowed_roots configured")}

                (not (some #(path-prefix? request-root %) allowed-roots))
                {:allowed? false
                 :code :forbidden
                 :message (str "tenant " tenant-id " is not allowed for root_path " request-root)}

                (and (some? paths) (not (sequential? paths)))
                {:allowed? false
                 :code :invalid_request
                 :message "paths must be an array of relative paths"}

                (and has-path-rules? (some nil? path-prefixes))
                {:allowed? false
                 :code :forbidden
                 :message (str "tenant " tenant-id " has invalid allowed_path_prefixes configuration")}

                (and has-path-rules? (not (seq paths)))
                {:allowed? false
                 :code :forbidden
                 :message (str "tenant " tenant-id " requires explicit paths for " op-name)}

                :else
                (let [normalized-paths (if (seq paths) (mapv normalize-relative-path paths) [])
                      invalid-path? (some nil? normalized-paths)
                      denied-path? (and has-path-rules?
                                        (some (fn [path]
                                                (not (some #(path-allowed? path %) path-prefixes)))
                                              normalized-paths))]
                  (cond
                    invalid-path?
                    {:allowed? false
                     :code :invalid_request
                     :message "paths must be relative and must not contain '..'"}

                    denied-path?
                    {:allowed? false
                     :code :forbidden
                     :message (str "tenant " tenant-id " is not allowed for one or more requested paths")}

                    :else
                    {:allowed? true}))))
            {:allowed? false
             :code :forbidden
             :message (str "tenant " tenant-id " is not configured in authz policy")}))
        (catch Exception e
          {:allowed? false
           :code :internal_error
           :message (or (.getMessage e) "authz policy evaluation failed")})))))

(defn load-policy [path]
  (-> path io/file slurp edn/read-string))

(defn load-policy-authorizer [path]
  (policy-authorizer (load-policy path)))

(defn evaluate [authz-check request]
  (if (nil? authz-check)
    {:allowed? true}
    (try
      (let [result (authz-check request)]
        (cond
          (true? result)
          {:allowed? true}

          (false? result)
          {:allowed? false
           :code :forbidden
           :message "authz policy denied request"}

          (map? result)
          (if (:allowed? result)
            {:allowed? true}
            {:allowed? false
             :code (or (:code result) :forbidden)
             :message (or (:message result) "authz policy denied request")})

          :else
          {:allowed? false
           :code :internal_error
           :message "authz callback returned unsupported response"}))
      (catch Exception e
        {:allowed? false
         :code :internal_error
         :message (or (.getMessage e) "authz callback failed")}))))
