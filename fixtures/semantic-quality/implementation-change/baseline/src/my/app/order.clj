(ns my.app.order)

(defn process-order [ctx order]
  (validate-order order))

(defn validate-order [order]
  order)
