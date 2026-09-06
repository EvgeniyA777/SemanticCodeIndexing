(ns semidx.runtime.providers.lsp-typescript-test
  "Stage 5a (plans/018, ADR-046) TypeScript live-overlay provider.

  Toolchain resolution and symbol mapping are deterministic and need no server.
  One end-to-end test starts the repo-managed `typescript-language-server` and is
  skipped when it does not resolve."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [semidx.runtime.providers.lsp-typescript :as tsl]
            [semidx.test-support.lsp-toolchain :as toolchain]))

(def ^:private corpus-root
  (.getPath (io/file "fixtures/provider-authority/corpus/typescript")))

;; --- Toolchain resolution ------------------------------------------------

(deftest resolution-returns-absolute-paths-test
  (testing "the session runs with the workspace as its working directory, and
            ProcessBuilder resolves a relative program against that directory,
            so a repo-managed path must be absolute or the server is not found"
    (when-let [command (tsl/resolve-command {})]
      (is (.isAbsolute (io/file command))))
    (when-let [tsserver (tsl/resolve-tsserver {})]
      (is (.isAbsolute (io/file tsserver))))))

(deftest unresolvable-toolchain-is-nil-not-a-throw-test
  (is (nil? (tsl/resolve-command {:lsp_toolchain_dir "/semidx/does-not-exist"
                                  :typescript_lsp_command "/semidx/does-not-exist/bin"})))
  (is (nil? (tsl/resolve-tsserver {:lsp_toolchain_dir "/semidx/does-not-exist"
                                   :typescript_lsp_tsserver_path "/semidx/nope.js"}))))

(deftest status-requires-both-halves-test
  (testing "a server with no tsserver to drive fails inside the initialize
            handshake, which is a worse place to discover it than the probe"
    (let [status (tsl/provider-status {:lsp_toolchain_dir "/semidx/does-not-exist"})]
      (is (= "unavailable" (:state status)))
      (is (= #{"typescript_lsp_server_missing" "typescript_lsp_tsserver_missing"}
             (set (:reason_codes status))))
      (is (= "typescript-lsp" (:provider_id status))))))

(deftest descriptor-is-the-catalog-entry-test
  (is (= :lsp (:provider_family tsl/descriptor)))
  (is (true? (:live_overlay tsl/descriptor)))
  (is (= :file (:scope tsl/descriptor))))

;; --- Symbol mapping ------------------------------------------------------

(defn- symbol* [name kind range & children]
  {:name name
   :kind kind
   :range {:start {:line (first range)} :end {:line (second range)}}
   :selectionRange {:start {:line (first range) :character 0}}
   :children (vec children)})

(deftest containers-give-ownership-not-units-test
  (testing "the regex tier mints no unit for a TypeScript class, so a tier that
            invented one would read as a permanent difference, not agreement"
    (let [walked (tsl/flatten-symbols
                  [(symbol* "OrderService" 5 [0 10]
                            (symbol* "handle" 6 [2 4]))
                   (symbol* "normalize" 12 [12 14])]
                  "src.orders")
          definitions (filterv :symbol walked)]
      (is (= ["src.orders.OrderService#handle" "src.orders/normalize"]
             (mapv :symbol definitions)))
      (is (= ["src.orders.OrderService" "src.orders"]
             (mapv :owner definitions)))
      (is (= ["method" "function"] (mapv :kind definitions))))))

(deftest unmodelled-kinds-are-recorded-not-guessed-test
  (let [walked (tsl/flatten-symbols [(symbol* "status" 7 [1 1])] "src.orders")]
    (is (= [{:unmapped "status" :reason "unmodelled_lsp_symbol_kind_7"}] walked))))

(deftest a-nested-function-is-not-a-member-of-its-enclosing-function-test
  (let [walked (tsl/flatten-symbols
                [(symbol* "outer" 12 [0 8]
                          (symbol* "inner" 12 [2 4]))]
                "src.orders")]
    (is (= ["src.orders/outer" "src.orders/inner"] (mapv :symbol walked))
        "only a container contributes an owner")))

;; --- End to end ----------------------------------------------------------

(deftest end-to-end-through-the-repo-managed-server
  (if (= "ready" (:state (tsl/provider-status {})))
    (let [session (tsl/open-session {:root_path corpus-root})]
      (try
        (let [text (slurp (io/file corpus-root "src/orders.ts"))
              result (tsl/document-facts session
                                         {:root_path corpus-root
                                          :path "src/orders.ts"
                                          :text text
                                          :source_identity {:content_digest "sha256:probe"}}
                                         {})
              by-operation (frequencies (map #(-> % :evidence first :operation)
                                             (:facts result)))]
          (is (pos? (get by-operation "definitions" 0)))
          (is (pos? (get by-operation "references" 0))
              "references resolve only while the document is still open, and only
               when the returned URI is compared as a path rather than as a string")
          (is (contains? (set (map #(get-in % [:key :symbol]) (:facts result)))
                         "src.orders/normalize"))
          (is (every? #(= "exact" (-> % :evidence first :authority)) (:facts result)))
          (testing "every reference location is inside the workspace"
            (is (every? (fn [fact]
                          (let [path (-> fact :evidence first :evidence_location :path)]
                            (and path (not (.isAbsolute (io/file path))))))
                        (:facts result)))))
        (finally (tsl/close-session session))))
    (toolchain/unresolved! "typescript-language-server" "provider end-to-end test")))
