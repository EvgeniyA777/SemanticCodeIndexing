(ns semidx.runtime.semantic-id-test
  "Snapshot enrichment, in particular the JSON round trip.

  A snapshot persisted to PostgreSQL comes back through
  `clojure.data.json/read-str` with `:key-fn keyword`, which keywordises every
  object key — including the unit ids that `:units` uses as data keys. Meanwhile
  `:unit_order` is a JSON array, so its entries stay strings. Enrichment used to
  look each `:unit_order` string up directly in the keyword-keyed `:units` map,
  which matched nothing and reloaded every stored snapshot with an empty
  `:units` while `snapshot_id`, `files`, and `unit_order` all looked healthy."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [semidx.runtime.semantic-id :as semantic-id]))

(defn- unit [unit-id path symbol-name]
  {:unit_id unit-id
   :path path
   :module "my.app.order"
   :symbol symbol-name
   :kind "function"
   :language "clojure"
   :start_line 1
   :end_line 3
   :parser_mode "full"})

(def ^:private units
  [(unit "src/my/app/order.clj::my.app.order/process-order"
         "src/my/app/order.clj" "my.app.order/process-order")
   (unit "src/my/app/order.clj::my.app.order/validate-order"
         "src/my/app/order.clj" "my.app.order/validate-order")])

(def ^:private in-memory-index
  {:snapshot_id "snap-1"
   :root_path "/tmp/repo"
   :unit_order (mapv :unit_id units)
   :units (into {} (map (juxt :unit_id identity)) units)})

(defn- json-round-trip [index]
  (json/read-str (json/write-str index) :key-fn keyword))

(deftest enrichment-survives-a-json-round-trip
  (let [enriched (semantic-id/enrich-index (json-round-trip in-memory-index))]
    (testing "every unit survives, keyed by its own unit id"
      (is (= 2 (count (:units enriched))))
      (is (= (set (map :unit_id units)) (set (keys (:units enriched))))
          "keys are the unit ids as strings, not the reader's keywords"))

    (testing "the units carry their semantic identity"
      (is (every? :semantic_id (vals (:units enriched))))
      (is (every? :semantic_fingerprint (vals (:units enriched)))))))

(deftest enrichment-is-unchanged-for-an-in-memory-index
  (let [enriched (semantic-id/enrich-index in-memory-index)]
    (is (= 2 (count (:units enriched))))
    (is (= (set (map :unit_id units)) (set (keys (:units enriched)))))))

(deftest a-unit-missing-from-unit-order-is-not-dropped
  ;; unit_order is an ordering hint, not the membership list. Enrichment used to
  ;; build the result solely from it, so a unit absent from the vector vanished.
  (let [index (assoc in-memory-index
                     :unit_order [(:unit_id (first units))])
        enriched (semantic-id/enrich-index index)]
    (is (= 2 (count (:units enriched)))
        "the unit missing from unit_order is retained")
    (is (contains? (:units enriched) (:unit_id (second units))))))

(deftest enrichment-leaves-a-shape-it-does-not-recognise-alone
  (testing "no unit_order vector"
    (let [index (dissoc in-memory-index :unit_order)]
      (is (= index (semantic-id/enrich-index index)))))

  (testing "units is not a map"
    (let [index (assoc in-memory-index :units [])]
      (is (= index (semantic-id/enrich-index index))))))
