(ns my.app.order)

(defn process-order [ctx order]
  (let [validated (validate-order order)]
    validated))

(defn validate-order [order]
  order)
