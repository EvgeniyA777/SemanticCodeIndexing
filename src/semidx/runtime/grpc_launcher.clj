(ns semidx.runtime.grpc-launcher
  (:require [semidx.runtime.grpc-prep :as grpc-prep]))

(defn -main [& args]
  (grpc-prep/ensure-grpc-classes!)
  (apply (requiring-resolve 'semidx.runtime.grpc/-main) args))
