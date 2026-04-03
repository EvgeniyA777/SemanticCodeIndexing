(ns semidx.compression-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [semidx.core :as sci]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- create-compression-repo! [root]
  (write-file! root "deps.edn" "{:paths [\"src\" \"test\"]}")
  (write-file! root "src/my/app/core.clj"
               "(ns my.app.core\n  (:require [my.app.api.user :as user-api]\n            [mount.core :as mount]))\n\n(defn -main [& _args]\n  (mount/start)\n  (user-api/create-user! {:id 1}))\n")
  (write-file! root "src/my/app/api/user.clj"
               "(ns my.app.api.user\n  (:require [my.app.service.user :as service]))\n\n(defn create-user! [payload]\n  (service/create-user payload))\n")
  (write-file! root "src/my/app/service/user.clj"
               "(ns my.app.service.user\n  (:require [my.app.domain.user :as domain]\n            [my.app.db.user :as user-db]))\n\n(defn create-user [payload]\n  (user-db/save-user (domain/map->User payload)))\n")
  (write-file! root "src/my/app/domain/user.clj"
               "(ns my.app.domain.user)\n\n(defrecord User [id])\n\n(defprotocol UserLookup\n  (lookup-user [this user-id]))\n")
  (write-file! root "src/my/app/db/user.clj"
               "(ns my.app.db.user)\n\n(defn save-user [user]\n  user)\n")
  (write-file! root "test/my/app/api/user_test.clj"
               "(ns my.app.api.user-test\n  (:require [clojure.test :refer [deftest is]]\n            [my.app.api.user :as user-api]))\n\n(deftest create-user-test\n  (is (map? (user-api/create-user! {:id 1}))))\n")
  (.mkdirs (io/file root ".git" "hooks")))

(deftest compress-project-builds-architecture-summary-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-compression-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-compression-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        artifact (sci/compress-project index)]
    (testing "artifact contains expected architectural sections"
      (is (= "1.0" (:schema_version artifact)))
      (is (some #{"my.app.core/-main"} (:entrypoints artifact)))
      (is (some #(and (= "User" (:name %)) (= "record" (:kind %))) (:domain_model artifact)))
      (is (some #(= {:from "my.app.api.user" :to "my.app.service.user"} %) (:dependency_edges artifact)))
      (is (= ["my.app.api.user"] (get (:namespace_categories artifact) "api")))
      (is (str/includes? (:summary_markdown artifact) "## Entry Points"))
      (is (str/includes? (:summary_markdown artifact) "## Namespace Categories"))
      (is (str/includes? (:summary_markdown artifact) "my.app.service.user")))))

(deftest refresh-project-compression-writes-artifacts-and-detects-drift-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-compression-refresh" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-compression-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        first-refresh (sci/refresh-project-compression index)
        second-refresh (sci/refresh-project-compression index {:changed_only true})
        markdown-path (get-in first-refresh [:paths :markdown_path])
        state-path (get-in first-refresh [:paths :state_path])]
    (testing "artifacts are written and unchanged refresh is a no-op"
      (is (= "refreshed" (:status first-refresh)))
      (is (.exists (io/file markdown-path)))
      (is (.exists (io/file state-path)))
      (is (= "unchanged" (:status second-refresh))))
    (testing "drift is detected after relevant source changes"
      (write-file! tmp-root "src/my/app/service/user.clj"
                   "(ns my.app.service.user\n  (:require [my.app.domain.user :as domain]\n            [my.app.db.user :as user-db]))\n\n(defn create-user [payload]\n  (user-db/save-user (assoc (domain/map->User payload) :role :admin)))\n")
      (let [updated-index (sci/create-index {:root_path tmp-root})
            drift (sci/compression-drift-report updated-index)]
        (is (:stale? drift))))))

(deftest init-project-compression-installs-hook-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-compression-init" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-compression-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        result (sci/init-project-compression! index)
        hook-path (io/file tmp-root ".git" "hooks" "pre-push")]
    (is (= "refreshed" (get-in result [:refresh :status])))
    (is (true? (get-in result [:hook :installed?])))
    (is (.exists hook-path))
    (is (str/includes? (slurp hook-path) "SEMIDX_CCC_PRE_PUSH"))))
