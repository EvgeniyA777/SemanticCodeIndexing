(ns semidx.runtime.project-context-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.core :as sci]
            [semidx.runtime.project-context :as project-context]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- fake-index [root-path snapshot-id active-languages]
  {:root_path root-path
   :snapshot_id snapshot-id
   :detected_languages active-languages
   :active_languages active-languages
   :supported_languages ["clojure" "java" "elixir" "python" "typescript" "javascript" "lua" "zig" "html" "css"]
   :language_fingerprint (str snapshot-id "-fp")
   :activation_state "ready"
   :selection_hint "Choose a core project language from the supported list. Additional languages can be activated later via refresh or prewarm."
   :manual_language_selection false})

(deftest language-activation-ignores-shadow-directories-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-shadow-discovery" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! tmp-root "src/my/app/core.clj" "(ns my.app.core)\n(defn run [] :ok)\n")
        _ (write-file! tmp-root "node_modules/pkg/index.ts" "export function ignoredTs(): string { return \"ts\"; }\n")
        _ (write-file! tmp-root ".venv/lib/python3.11/site-packages/helper.py" "def ignored_py():\n    return True\n")
        _ (write-file! tmp-root "dist/generated.ts" "export const generated = 1;\n")
        index (sci/create-index {:root_path tmp-root})]
    (testing "ignored directories do not activate extra language lanes"
      (is (= ["clojure"] (:detected_languages index)))
      (is (= ["clojure"] (:active_languages index))))
    (testing "ignored directories do not contribute indexed source files"
      (is (= ["src/my/app/core.clj"] (sort (keys (:files index))))))))

(deftest project-context-canonical-scope-and-tenant-isolation-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-project-scope" (make-array java.nio.file.attribute.FileAttribute 0)))
        dotted-root (.getPath (io/file tmp-root "."))
        registry (project-context/project-registry)
        shared-scope (project-context/project-scope tmp-root nil)
        dotted-scope (project-context/project-scope dotted-root nil)
        tenant-a-scope (project-context/project-scope tmp-root "tenant-a")
        tenant-b-scope (project-context/project-scope tmp-root "tenant-b")
        shared-entry (project-context/refresh-project-index! registry
                                                             shared-scope
                                                             #(fake-index (:root_path shared-scope) "shared-snap" ["python"]))
        tenant-a-entry (project-context/refresh-project-index! registry
                                                               tenant-a-scope
                                                               #(fake-index (:root_path tenant-a-scope) "tenant-a-snap" ["python"]))
        tenant-b-entry (project-context/refresh-project-index! registry
                                                               tenant-b-scope
                                                               #(fake-index (:root_path tenant-b-scope) "tenant-b-snap" ["typescript"]))]
    (testing "different path spellings collapse to one canonical project scope"
      (is (= (:registry_key shared-scope) (:registry_key dotted-scope)))
      (is (= shared-entry (project-context/project-entry registry dotted-scope))))
    (testing "tenant-aware scopes stay isolated for the same physical root"
      (is (= "tenant-a" (:tenant_id tenant-a-entry)))
      (is (= "tenant-b" (:tenant_id tenant-b-entry)))
      (is (not= (:snapshot_id tenant-a-entry) (:snapshot_id tenant-b-entry))))
    (is (= 3 (count @registry)))))

(deftest project-context-in-progress-errors-carry-retry-details-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-project-lock" (make-array java.nio.file.attribute.FileAttribute 0)))
        registry (project-context/project-registry)
        scope (project-context/project-scope tmp-root nil)
        started (promise)
        release (promise)
        worker (future
                 (project-context/refresh-project-index! registry
                                                         scope
                                                         #(do
                                                            (deliver started true)
                                                            @release
                                                            (fake-index (:root_path scope) "locked-snap" ["python"]))))]
    @started
    (let [error (try
                  (project-context/ensure-project-index! registry
                                                         scope
                                                         {:paths ["app/main.py"]}
                                                         #(fake-index (:root_path scope) "unexpected-snap" ["python"]))
                  nil
                  (catch Exception e e))]
      (is (= :language_activation_in_progress (:type (ex-data error))))
      (is (= 2 (get-in (ex-data error) [:details :retry_after_seconds])))
      (is (= "retry_same_request" (get-in (ex-data error) [:details :recommended_action])))
      (is (string? (get-in (ex-data error) [:details :activation_started_at]))))
    (deliver release true)
    (is (= "locked-snap" (:snapshot_id @worker)))))
