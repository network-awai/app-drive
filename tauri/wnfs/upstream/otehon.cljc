(ns awai.wnfs.otehon
  (:require [awai.wnfs.model :as model]
            #?(:cljs ["@noble/hashes/blake3" :as blake])
            #?(:cljs ["@noble/ciphers/chacha" :as chacha])
            #?(:cljs ["node:crypto" :as crypto])))

#?(:cljs
   (defn- ->u8 [x]
     ;; A Uint8Array is used as is (no copy); a vector of integers is converted as before.
     (if (instance? js/Uint8Array x) x (js/Uint8Array.from (clj->js x)))))

#?(:cljs
   (def provider
     (reify model/Primitives
       (hash-bytes [_ material]
         (vec (blake/blake3 (js/Uint8Array.from (clj->js material)))))
       (derive-key [_ context material]
         (vec (blake/blake3 (js/Uint8Array.from (clj->js material))
                           #js {:context context})))
       (seal-xchacha [_ key nonce plaintext]
         (let [out (.encrypt (chacha/xchacha20poly1305 (->u8 key) (->u8 nonce)) (->u8 plaintext))]
           (if (instance? js/Uint8Array plaintext) out (vec out))))
       (open-xchacha [_ key nonce ciphertext]
         (try
           (let [out (.decrypt (chacha/xchacha20poly1305 (->u8 key) (->u8 nonce)) (->u8 ciphertext))]
             (if (instance? js/Uint8Array ciphertext) out (vec out)))
           (catch :default _ (model/reject :authentication-failed)))))))

#?(:cljs (defn random-key [] (vec (crypto/randomBytes 32))))
#?(:cljs
   (defn seal-block [key plaintext shape index]
     (model/seal-external provider key (vec (crypto/randomBytes 24)) plaintext shape index)))

#?(:cljs
   (defn hash-label [label]
     (model/sized label 256)
     (vec (blake/blake3 (js/Uint8Array.from (clj->js label))))))
