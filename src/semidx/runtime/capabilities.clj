(ns semidx.runtime.capabilities
  (:require [semidx.runtime.language-registry :as registry]))

(def current-capability-version "1.0")

(defn- confidence-ceiling [strength]
  ;; Currently, strength directly dictates the ceiling since it's non-compensating
  strength)

(defn- provider-authority-capability
  "What this server can do with provider evidence (plans/018 Stage 6.4).

  Without it the per-language `confidence_ceiling` below reads as the whole
  truth, and since Stage 6.2 it is not: it is the ceiling a language earns from
  its own parser, and a selection whose units all carry exact provider evidence
  rises above it. A client that plans around `typescript: low` forever would be
  planning around a number this server can now beat.

  Static on purpose. It describes what the server is capable of, not which mode a
  particular build ran in — that belongs to the build's own provider summary, and
  putting it here would make a capability response depend on the last index.

  Resolved lazily so the capabilities path, which every surface calls, does not
  pull the provider namespaces into its load graph."
  []
  {:policy_version @(requiring-resolve 'semidx.runtime.provider-authority/authority-policy-version)
   :languages (vec (sort @(requiring-resolve 'semidx.runtime.provider-authority/authority-languages)))
   :modes (vec (sort (map name @(requiring-resolve 'semidx.runtime.index/provider-pipeline-modes))))
   :evidence_raises_confidence_ceiling true})

(defn capabilities-payload
  [server-name server-version]
  {:capability_version current-capability-version
   :server {:name server-name
            :version server-version}
   :languages
   (mapv (fn [{:keys [language extensions provider strength]}]
           {:language language
            :extensions extensions
            :provider provider
            :strength strength
            :confidence_ceiling (confidence-ceiling strength)})
         registry/language-lanes)
   :language_policy_options registry/supported-language-order
   :provider_authority (provider-authority-capability)})
