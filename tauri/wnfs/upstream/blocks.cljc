(ns awai.wnfs.blocks
  (:require [awai.wnfs.model :as m]
            #?(:cljs ["@noble/hashes/blake3" :as blake])
            #?(:cljs ["@noble/hashes/sha256" :as sha])
            #?(:cljs ["multiformats/cid" :refer [CID]])
            #?(:cljs ["multiformats/hashes/digest" :as digest])
            #?(:cljs ["@ipld/dag-cbor" :as cbor])
            #?(:cljs ["node:fs" :as fs])
            #?(:cljs ["node:path" :as path])
            #?(:cljs ["node:crypto" :as crypto])))

#?(:cljs (defn u8 [v] (if (m/u8? v) v (js/Uint8Array.from (clj->js (m/octets v))))))
#?(:cljs
   (defn bytes= [a b]
     (and (= (m/blen a) (m/blen b))
          (if (or (m/u8? a) (m/u8? b))
            (zero? (.compare js/Buffer (u8 a) (u8 b)))
            (= a b)))))
#?(:cljs
   (defn cid-of [codec bytes]
     (when-not (#{85 113} codec) (m/reject :unsupported-codec))
     (when (> (m/blen (m/octets bytes)) 262144) (m/reject :oversized-block))
     (.createV1 CID codec (digest/create 30 (blake/blake3 (u8 bytes))))))

#?(:cljs
   (defn admit-cid [cid]
     (when-not (and (.asCID CID cid) (= 1 (.-version cid))
                    (#{85 113} (.-code cid))
                    (#{18 30} (.-code (.-multihash cid)))
                    (= 32 (.-size (.-multihash cid))))
       (m/reject :unsupported-cid))
     cid))

#?(:cljs
   (defn verify-block [cid bytes]
     (admit-cid cid)
     (m/octets bytes)
     (when (> (m/blen bytes) 262144) (m/reject :oversized-block))
     (let [mh (.-multihash cid)
           hash (case (.-code mh)
                  30 (vec (blake/blake3 (u8 bytes)))
                  18 (vec (sha/sha256 (u8 bytes)))
                  (m/reject :unsupported-multihash))]
       (when-not (= hash (vec (.-digest mh))) (m/reject :cid-mismatch)))
     bytes))

#?(:cljs (defn encode [value] (vec (cbor/encode value))))
#?(:cljs
   (defn decode [bytes]
     (when (> (m/blen (m/octets bytes)) 262144) (m/reject :oversized-block))
     (let [decoded (cbor/decode (u8 bytes))]
       (when-not (bytes= bytes (encode decoded)) (m/reject :noncanonical-dag-cbor))
       decoded)))

;; Synchronous bounded Otehon I/O. Root is an application-owned directory.
;; No keys, plaintext or credentials are accepted by this ciphertext block API.
#?(:cljs
   (defn disk-store [root]
     (when-not (fs/existsSync root) (fs/mkdirSync root #js {:mode 448}))
     (when-not (.isDirectory (fs/lstatSync root)) (m/reject :invalid-store-root))
     (let [root (fs/realpathSync root)
           read-u8 (fn [cid]
                       (admit-cid cid)
                       (let [file (path/join root (.toString cid))
                             fd (fs/openSync file (bit-or (.-O_RDONLY fs/constants)
                                                          (.-O_NOFOLLOW fs/constants)
                                                          (.-O_NONBLOCK fs/constants)))]
                         (try
                           (let [stat (fs/fstatSync fd)]
                             (when-not (and (.isFile stat) (<= (.-size stat) 262144))
                               (m/reject :invalid-block-file))
                             (let [data (js/Buffer.alloc (inc (.-size stat)))
                                   n (fs/readSync fd data 0 (.-length data) 0)]
                               (verify-block cid (.subarray data 0 n))))
                           (finally (fs/closeSync fd)))))
           get-block (fn [cid] (vec (read-u8 cid)))]
       {:get get-block
        ;; Same verified bytes without the vector-of-integers conversion (large payloads).
        :get-u8 read-u8
        :put (fn [codec bytes]
               (let [cid (cid-of codec bytes)
                     target (path/join root (.toString cid))
                     temp (path/join root (str ".pending-" (crypto/randomUUID)))
                     fd (fs/openSync temp "wx" 384)]
                 (try
                   (try
                     (fs/writeFileSync fd (u8 bytes))
                     (fs/fsyncSync fd)
                     (finally (fs/closeSync fd)))
                   (try
                     ;; Atomic no-clobber publication; immutable existing bytes
                     ;; must verify rather than being overwritten on corruption.
                     (fs/linkSync temp target)
                     (catch :default e
                       (if (= "EEXIST" (.-code e))
                         (when-not (bytes= bytes (read-u8 cid)) (m/reject :existing-block-mismatch))
                         (throw e))))
                   (let [dir (fs/openSync root "r")]
                     (try (fs/fsyncSync dir) (finally (fs/closeSync dir))))
                   cid
                   (finally (fs/unlinkSync temp)))))})))
