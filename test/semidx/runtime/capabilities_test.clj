(ns semidx.runtime.capabilities-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]
            [clojure.data.json :as json]
            [semidx.contracts.schemas :as schemas]
            [semidx.runtime.capabilities :as cap]
            [semidx.runtime.language-registry :as registry]
            [semidx.core :as sci]
            [semidx.mcp.core :as mcp]
            [semidx.runtime.http :as runtime-http]
            [semidx.runtime.grpc :as runtime-grpc]
            [semidx.runtime.grpc-proto :as grpc-proto])
  (:import [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.net URI]
           [io.grpc CallOptions ManagedChannelBuilder]
           [io.grpc.stub ClientCalls]))

(deftest capabilities-payload-test
  (testing "Generates schema-valid capabilities payload with all languages"
    (let [payload (cap/capabilities-payload "test-server" "1.0.0")]
      (is (= "1.0" (:capability_version payload)))
      (is (= "test-server" (get-in payload [:server :name])))
      (is (= "1.0.0" (get-in payload [:server :version])))
      (is (= (count registry/language-lanes) (count (:languages payload))))
      (is (= registry/supported-language-order (:language_policy_options payload)))
      (let [zig (some #(when (= "zig" (:language %)) %) (:languages payload))]
        (is (= [".zig"] (:extensions zig)))
        (is (= "zig-zls" (get-in zig [:provider :provider_id])))
        (is (= "2" (get-in zig [:provider :provider_version])))
        (is (= "low" (:strength zig)))
        (is (= "low" (:confidence_ceiling zig))))
      
      (testing "the provider authority capability describes what evidence can do"
        (let [authority (:provider_authority payload)]
          (is (= ["java" "typescript"] (:languages authority)))
          (is (= ["authority" "off" "shadow"] (:modes authority)))
          (is (true? (:evidence_raises_confidence_ceiling authority))
              "the per-language confidence_ceiling above is what a lane's own
               parser earns; a wholly exact selection rises above it")
          (is (string? (:policy_version authority)))))

      (let [schema (get schemas/contracts :example/capabilities)
            explain (m/explain schema payload)]
        (is (nil? explain) (pr-str (when explain (me/humanize explain))))))))

(deftest capabilities-parity-test
  (testing "Cross-surface capabilities parity"
    (let [library-payload (sci/capabilities)
          
          ;; MCP payload
          state (mcp/new-session-state {})
          mcp-payload (mcp/tool-capabilities state {})
          
          ;; HTTP payload
          http-server (runtime-http/start-server {:host "127.0.0.1" :port 0})
          http-port (-> http-server .getAddress .getPort)
          client (HttpClient/newHttpClient)
          request (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" http-port "/capabilities")))
                      (.header "Content-Type" "application/json")
                      (.GET)
                      (.build))
          http-response (.send client request (HttpResponse$BodyHandlers/ofString))
          http-payload (json/read-str (.body http-response) :key-fn keyword)
          
          ;; gRPC payload
          grpc-server (runtime-grpc/start-server {:host "127.0.0.1" :port 0})
          grpc-port (:port grpc-server)
          channel (-> (ManagedChannelBuilder/forAddress "127.0.0.1" (int grpc-port))
                      (.usePlaintext)
                      (.build))
          grpc-health-resp (ClientCalls/blockingUnaryCall channel
                                                          runtime-grpc/health-method
                                                          CallOptions/DEFAULT
                                                          (grpc-proto/health-request))
          grpc-health-map (grpc-proto/health-response->map grpc-health-resp)
          grpc-payload (json/read-str (:capabilities_json grpc-health-map) :key-fn keyword)]
          
      (try
        (is (= "1.0" (:capability_version library-payload)))
        
        ;; Assert core structural equality (servers might have different names/versions)
        (is (= (:languages library-payload) (:languages mcp-payload) (:languages http-payload) (:languages grpc-payload)))
        (is (= (:language_policy_options library-payload) (:language_policy_options mcp-payload) (:language_policy_options http-payload) (:language_policy_options grpc-payload)))

        ;; plans/018 Stage 6.4. Without this, a client reading capabilities plans
        ;; around a per-language ceiling that exact provider evidence can now
        ;; beat, and every surface must say so identically or the parity is only
        ;; on the fields that were already there.
        (is (some? (:provider_authority library-payload)))
        (is (= (:provider_authority library-payload)
               (:provider_authority mcp-payload)
               (:provider_authority http-payload)
               (:provider_authority grpc-payload)))

        
        ;; Check schema validity for all payloads
        (let [schema (get schemas/contracts :example/capabilities)]
          (is (nil? (m/explain schema library-payload)))
          (is (nil? (m/explain schema mcp-payload)))
          (is (nil? (m/explain schema http-payload)))
          (is (nil? (m/explain schema grpc-payload))))
        
        (finally
          (.stop http-server 0)
          (.shutdown channel)
          (.shutdown (:server grpc-server)))))))
