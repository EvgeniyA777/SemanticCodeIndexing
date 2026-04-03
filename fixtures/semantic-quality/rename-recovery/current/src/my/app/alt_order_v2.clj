(ns my.app.alt-order-v2)

(defn validate-order [order]
  (assoc order :alt true))
