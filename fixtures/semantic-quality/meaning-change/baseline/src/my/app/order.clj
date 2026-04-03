(ns my.app.order)

(defn validate-order [order]
  (if (:id order)
    order
    (throw (ex-info "invalid" {}))))
