(ns my.app.alt-order)

(defn validate-order [order]
  (assoc order :alt true))
