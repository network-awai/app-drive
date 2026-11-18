(ns awai.wnfs.forest
  (:require [awai.wnfs.model :as m]))

(defn binary-compare [a b]
  (loop [i 0]
    (cond
      (= i (min (count a) (count b))) (compare (count a) (count b))
      (= (nth a i) (nth b i)) (recur (inc i))
      :else (compare (nth a i) (nth b i)))))

;; Explicit bounded CID profile: raw CIDv1, BLAKE3-256 or SHA-256.
;; Other multihashes need an admitted implementation, not silent reinterpretation.
(defn raw-cid [cid]
  (m/sized cid 36)
  (when-not (and (= [1 85] (subvec cid 0 2))
                 (#{18 30} (nth cid 2)) (= 32 (nth cid 3)))
    (m/reject :unsupported-ciphertext-cid))
  cid)

(defn cid-set [cids]
  (when-not (and (vector? cids) (seq cids)) (m/reject :empty-ciphertext-set))
  (vec (sort binary-compare (set (map raw-cid cids)))))

(defn admit-cid-set [cids]
  (when-not (= cids (cid-set cids)) (m/reject :noncanonical-ciphertext-set))
  cids)

(defn label [modulus value]
  (m/sized modulus 256)
  (m/sized value 256)
  (when-not (and (some pos? value) (neg? (binary-compare value modulus)))
    (m/reject :invalid-name-accumulator))
  value)

(defn nibble [hash depth]
  (m/sized hash 32)
  (when-not (and (integer? depth) (<= 0 depth 63)) (m/reject :hamt-depth))
  (let [b (nth hash (quot depth 2))]
    (if (even? depth) (bit-shift-right b 4) (bit-and b 15))))

(defn sparse-index [[low high] slot]
  (when-not (and (integer? slot) (<= 0 slot 15)) (m/reject :hamt-slot))
  (let [mask (bit-or low (bit-shift-left high 8))]
    (when (bit-test mask slot)
      (count (filter #(bit-test mask %) (range slot))))))

(defn- node [hash-label records depth]
  (when (> depth 63) (m/reject :hamt-hash-collision))
  (let [groups (sort-by key (group-by #(nibble (hash-label (first %)) depth) records))
        mask (reduce (fn [bits [slot _]] (bit-set bits slot)) 0 groups)]
    [[(bit-and mask 255) (bit-and (bit-shift-right mask 8) 255)]
     (mapv (fn [[_ entries]]
             (let [entries (vec (sort #(binary-compare (hash-label (first %1)) (hash-label (first %2))) entries))]
               (if (<= (count entries) 3)
                 entries
                 {:node (node hash-label entries (inc depth))}))) groups)]))

(defn build [hash-label modulus records]
  (m/sized modulus 256)
  (when-not (and (vector? records) (<= (count records) 10000))
    (m/reject :invalid-forest-records))
  (let [merged (reduce (fn [acc [name cids :as entry]]
                         (when-not (= 2 (count entry)) (m/reject :invalid-forest-entry))
                         (label modulus name)
                         (update acc name #(cid-set (into (or % []) (cid-set cids)))))
                       {} records)]
    (node hash-label (mapv identity merged) 0)))

(defn lookup [hash-label tree name]
  ;; tree is constructed by build, not an unvalidated network decode.
  (loop [[mask entries] tree depth 0]
    (if-let [index (sparse-index mask (nibble (hash-label name) depth))]
      (let [entry (nth entries index)]
        (if (map? entry)
          (recur (:node entry) (inc depth))
          (some (fn [[label cids]] (when (= label name) cids)) entry)))
      nil)))

(defn lookup-hash [hash-label tree hash]
  (m/sized hash 32)
  (loop [[mask entries] tree depth 0]
    (when-let [index (sparse-index mask (nibble hash depth))]
      (let [entry (nth entries index)]
        (if (map? entry)
          (recur (:node entry) (inc depth))
          (some (fn [[label cids]]
                  (when (= hash (hash-label label)) {:name label :cids cids})) entry))))))

(defn records [[_ entries]]
  (vec (mapcat #(if (map? %) (records (:node %)) %) entries)))
