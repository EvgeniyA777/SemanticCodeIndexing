(ns my.app.order)

(defn process-order [ctx order]
  order)

(defn normalize-order [order]
  (assoc order :normalized true))
