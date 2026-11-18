(ns awai.wnfs.accumulator
  (:require [awai.wnfs.model :as m]
            [awai.wnfs.blocks :as b]
            [awai.wnfs.otehon :as host]
            #?(:cljs ["@noble/curves/abstract/modular" :as modular])
            #?(:cljs ["@noble/curves/abstract/utils" :as utils])
            #?(:cljs ["node:crypto" :as crypto])))

;; Arithmetic uses the established provider; no private RSA factors are generated.
#?(:cljs (defn integer-from [bytes] (utils/bytesToNumberBE (b/u8 bytes))))
#?(:cljs (defn integer-bytes [value length] (vec (utils/numberToBytesBE value length))))
#?(:clj (defn prime? [_] (m/reject :otehon-provider-required)))
#?(:cljs (defn prime? [bytes] (crypto/checkPrimeSync (b/u8 bytes) #js {:checks 64})))
(defn counter-le [n]
  (when-not (and (integer? n) (<= 0 n 4294967295)) (m/reject :invalid-prime-counter))
  (mapv #(mod (quot n %) 256) [1 256 65536 16777216]))
(defn candidate [provider domain bytes length counter]
  (when-not (and (integer? length) (<= 1 length 32)) (m/reject :prime-length))
  (let [hash (subvec (m/derive-key provider domain (into (m/octets bytes) (counter-le counter))) 0 length)]
    ;; Matches wnfs-nameaccumulator 0.3.0: counter LE, then force odd.
    (update hash (dec length) bit-or 1)))
(defn hash-prime [provider domain bytes length]
  (loop [counter 0]
    (when (> counter 65535) (m/reject :prime-search-bound))
    (let [value (candidate provider domain bytes length counter)]
      (if (prime? value) {:bytes value :counter counter} (recur (inc counter))))))
(defn admit-element [element]
  (m/sized element 32)
  (when-not (prime? element) (m/reject :nonprime-element))
  element)
#?(:cljs
   (defn accumulate [modulus state element]
     (m/sized modulus 256) (m/sized state 256) (admit-element element)
     (let [n (integer-from modulus) u (integer-from state)]
       (when-not (and (>= (first modulus) 128) (odd? (last modulus))
                      (> u (js/BigInt 1)) (< u n))
         (m/reject :invalid-accumulator))
       ;; Refuse values outside the unit group.
       (modular/invert u n)
       (integer-bytes (modular/pow u (integer-from element) n) 256))))
#?(:cljs (defn random-element [] (vec (js/Uint8Array. (crypto/generatePrimeSync 256 #js {:bigint false})))))
