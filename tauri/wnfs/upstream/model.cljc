(ns awai.wnfs.model)

(def spec-revision "5229f36dc023904bb5c71eea3cc56b4fd5fbbe13")
(def max-plaintext-block (- 262144 40))
(def snapshot-context "wnfs/1.0/snapshot key derivation from temporal")

(defn reject [code]
  (throw (ex-info (name code) {:code code})))

(defn u8?
  "True for a Uint8Array (cljs). Large payload bytes may be a Uint8Array instead of a vector of
  integers; a typed array is already bounded to 0..255, so it needs no per-element check."
  [value]
  #?(:cljs (instance? js/Uint8Array value) :default false))

(defn blen
  "Byte length of a vector or a Uint8Array (cljs `count` does not accept typed arrays)."
  [value]
  #?(:cljs (if (u8? value) (.-length value) (count value)) :default (count value)))

(defn octets [value]
  (when-not (or (u8? value)
                (and (vector? value)
                     (every? #(and (integer? %) (<= 0 % 255)) value)))
    (reject :invalid-bytes))
  value)

(defn- join-nonce [nonce ciphertext]
  #?(:cljs (if (u8? ciphertext)
             (let [out (js/Uint8Array. (+ 24 (.-length ciphertext)))]
               (.set out (js/Uint8Array.from (clj->js nonce)))
               (.set out ciphertext 24)
               out)
             (into nonce ciphertext))
     :default (into nonce ciphertext)))

(defn- slice-bytes [value from to]
  #?(:cljs (if (u8? value)
             (.subarray value from (or to (.-length value)))
             (subvec value from (or to (count value))))
     :default (subvec value from (or to (count value)))))

(defn sized [value size]
  (octets value)
  (when-not (= size (blen value)) (reject :invalid-length))
  value)

(defn bounded-int? [n]
  (and (integer? n) (<= 0 n 9007199254740991)))

(defn external-shape [{:keys [block-size block-count]}]
  (when-not (and (bounded-int? block-size) (<= 1 block-size max-plaintext-block)
                 (bounded-int? block-count) (pos? block-count))
    (reject :invalid-external-shape)))

(defn block-length [shape index length]
  (external-shape shape)
  (when-not (and (bounded-int? index) (< index (:block-count shape))
                 (bounded-int? length) (<= 1 length (:block-size shape))
                 (or (= index (dec (:block-count shape)))
                     (= length (:block-size shape))))
    (reject :invalid-block-length)))

(defprotocol Primitives
  (hash-bytes [provider material])
  (derive-key [provider context material])
  (seal-xchacha [provider key nonce plaintext])
  (open-xchacha [provider key nonce ciphertext]))

(defn snapshot-key [provider temporal-key]
  (sized (derive-key provider snapshot-context (sized temporal-key 32)) 32))

;; Low-level deterministic corpus entry. Production callers must generate a
;; fresh random key per content change and a fresh random nonce per block.
(defn seal-external [provider key nonce plaintext shape index]
  (sized key 32)
  (sized nonce 24)
  (octets plaintext)
  (block-length shape index (blen plaintext))
  (let [ciphertext (seal-xchacha provider key nonce plaintext)]
    (sized ciphertext (+ 16 (blen plaintext)))
    (join-nonce nonce ciphertext)))

(defn open-external [provider key sealed shape index]
  (sized key 32)
  (octets sealed)
  (block-length shape index (- (blen sealed) 40))
  (let [plaintext (open-xchacha provider key (slice-bytes sealed 0 24) (slice-bytes sealed 24 nil))]
    (sized plaintext (- (blen sealed) 40))))
