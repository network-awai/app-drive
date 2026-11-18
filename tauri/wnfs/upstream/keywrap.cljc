(ns awai.wnfs.keywrap
  (:require [awai.wnfs.model :as m]
            [awai.wnfs.blocks :as b]
            #?(:cljs ["@noble/ciphers/aes" :as aes])))
#?(:cljs
   (defn wrap-key [key plaintext]
     (m/sized key 32) (m/octets plaintext)
     (when-not (<= 1 (count plaintext) 262128) (m/reject :keywrap-bound))
     (vec (.encrypt (aes/aeskwp (b/u8 key)) (b/u8 plaintext)))))
#?(:cljs
   (defn unwrap-key [key ciphertext]
     (m/sized key 32) (m/octets ciphertext)
     (when-not (and (<= 16 (count ciphertext) 262144) (zero? (mod (count ciphertext) 8)))
       (m/reject :keywrap-bound))
     (try (vec (.decrypt (aes/aeskwp (b/u8 key)) (b/u8 ciphertext)))
          (catch :default _ (m/reject :keywrap-authentication)))))
