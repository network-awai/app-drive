(ns awai.wnfs.ratchet
  (:require [awai.wnfs.model :as m]))

(def temporal-context "wnfs/1.0/temporal derivation from ratchet")
(def revision-context "wnfs/1.0/revision segment derivation from ratchet")
(defn admit [r]
  (when-not (= #{:salt :large :medium :small :mediumCounter :smallCounter} (set (keys r)))
    (m/reject :invalid-ratchet))
  (doseq [k [:salt :large :medium :small]] (m/sized (get r k) 32))
  (doseq [k [:mediumCounter :smallCounter]]
    (when-not (and (integer? (get r k)) (<= 0 (get r k) 255))
      (m/reject :invalid-ratchet-counter)))
  r)
(defn material [r] (admit r) (vec (concat (:large r) (:medium r) (:small r))))
(defn temporal-key [provider r] (m/derive-key provider temporal-context (material r)))
(defn zero-state [provider salt pre-large]
  (m/sized salt 32) (m/sized pre-large 32)
  (let [hash #(m/hash-bytes provider (vec %))
        pre-medium (hash (concat salt pre-large))]
    {:salt salt :large (hash pre-large) :medium (hash pre-medium)
     :small (hash (concat salt pre-medium)) :mediumCounter 0 :smallCounter 0}))
(defn medium-step [provider r]
  (admit r)
  (if (= 255 (:mediumCounter r))
    (zero-state provider (:salt r) (:large r))
    (let [pre-medium (m/hash-bytes provider (:medium r))]
      (assoc r :medium (m/hash-bytes provider pre-medium)
               :small (m/hash-bytes provider (into (:salt r) pre-medium))
               :mediumCounter (inc (:mediumCounter r)) :smallCounter 0))))
(defn step [provider r]
  (admit r)
  (if (= 255 (:smallCounter r)) (medium-step provider r)
      (assoc r :small (m/hash-bytes provider (:small r)) :smallCounter (inc (:smallCounter r)))))
(defn advance [provider r n]
  (admit r)
  (when-not (and (integer? n) (<= 0 n 16777216)) (m/reject :ratchet-step-bound))
  (loop [r r n n]
    (let [to-large (- 65536 (+ (* 256 (:mediumCounter r)) (:smallCounter r)))
          to-medium (- 256 (:smallCounter r))]
      (cond (zero? n) r
            (>= n to-large) (recur (zero-state provider (:salt r) (:large r)) (- n to-large))
            (>= n to-medium) (recur (medium-step provider r) (- n to-medium))
            :else (recur (step provider r) (dec n))))))
