(ns my.app.order)

(defn validate-order [payload]
  (if (:id payload)
    payload
    (throw (ex-info "invalid" {}))))
