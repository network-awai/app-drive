(ns awai.wnfs.private-node
  (:require [awai.wnfs.model :as m]
            [awai.wnfs.blocks :as b]
            [awai.wnfs.otehon :as h]
            [awai.wnfs.keywrap :as kw]
            [awai.wnfs.ratchet :as ratchet]
            [awai.wnfs.accumulator :as a]
            [awai.wnfs.forest :as forest]
            #?(:cljs ["multiformats/cid" :refer [CID]])))

#?(:cljs
   (do
     (defn bytes [value size]
       (when-not (instance? js/Uint8Array value) (m/reject :expected-byte-string))
       (let [value (vec value)] (if size (m/sized value size) (m/octets value))))
     (defn raw-link [value]
       (b/admit-cid value)
       (when-not (= 85 (.-code value)) (m/reject :expected-ciphertext-link))
       value)
     (defn same-cid? [a b] (= (vec (.-bytes a)) (vec (.-bytes b))))
     (defn wrap-content [key data]
       (let [nonce (subvec (h/random-key) 0 24)]
         (into nonce (m/seal-xchacha h/provider (m/sized key 32) nonce data))))
     (defn open-content [key cipher]
       (when-not (<= 40 (count cipher) 262144) (m/reject :ciphertext-bound))
       (m/open-xchacha h/provider (m/sized key 32) (subvec cipher 0 24) (subvec cipher 24)))
     (defn ratchet-object [r]
       (ratchet/admit r)
       #js {:salt (b/u8 (:salt r)) :large (b/u8 (:large r))
            :medium (b/u8 (:medium r)) :small (b/u8 (:small r))
            :mediumCounter (:mediumCounter r) :smallCounter (:smallCounter r)})
     (defn parse-ratchet [r]
       (ratchet/admit {:salt (bytes (.-salt r) 32) :large (bytes (.-large r) 32)
                       :medium (bytes (.-medium r) 32) :small (bytes (.-small r) 32)
                       :mediumCounter (.-mediumCounter r) :smallCounter (.-smallCounter r)}))
     (defn revision-name [modulus name r]
       (a/accumulate modulus name
                     (:bytes (a/hash-prime h/provider ratchet/revision-context (ratchet/material r) 32))))
     (defn shard-name [modulus base key index]
       (when-not (and (integer? index) (<= 0 index 4294967295)) (m/reject :shard-index))
       (let [le (mapv #(mod (quot index %) 256) [1 256 65536 16777216 4294967296 1099511627776 281474976710656 72057594037927936])]
         (a/accumulate modulus base
                       (:bytes (a/hash-prime h/provider "wnfs/1.0/segment derivation for file block" (into key le) 32)))))
     (defn ref-object [parent-key ref]
       #js {:label (b/u8 (:label ref)) :contentCid (raw-link (:content-cid ref))
            :snapshotKey (b/u8 (:snapshot-key ref))
            :temporalKey (b/u8 (kw/wrap-key parent-key (:temporal-key ref)))})
     (defn parse-ref [parent-ref value]
       (let [snapshot (bytes (.-snapshotKey value) 32)
             ref {:label (bytes (.-label value) 32) :content-cid (raw-link (.-contentCid value))
                  :snapshot-key snapshot}
             wrapped (bytes (.-temporalKey value) 40)]
         (if-let [key (:temporal-key parent-ref)]
           (let [temporal (m/sized (kw/unwrap-key key wrapped) 32)]
             (when-not (= snapshot (m/snapshot-key h/provider temporal)) (m/reject :ref-key-mismatch))
             (assoc ref :temporal-key temporal))
           ref)))
     (defn admit-segment [name]
       (when-not (and (string? name) (seq name) (not (#{"." ".."} name))
                      (not (re-find #"[/\\\u0000]" name))) (m/reject :invalid-path-segment))
       name)
     (defn object-map? [value]
       (and (some? value) (object? value)
            (not (array? value)) (not (instance? js/Uint8Array value))
            (not (.asCID CID value))))
     (defn load-node [store loaded-forest ref]
       (let [group (forest/lookup-hash h/hash-label (:tree loaded-forest) (:label ref))
             cid (raw-link (:content-cid ref))]
         (when-not (some #(= % (vec (.-bytes cid))) (:cids group)) (m/reject :unreachable-node))
         (let [value (b/decode (open-content (:snapshot-key ref) ((:get store) cid)))
               variants (vec (js/Object.keys value))
               kind (first variants)]
           (when-not (and (= 1 (count variants)) (#{"wnfs/priv/file" "wnfs/priv/dir"} kind))
             (m/reject :unknown-private-variant))
           (let [body (aget value kind) header-cid (raw-link (.-headerCid body))]
             (when-not (= "1.0.0" (.-version body)) (m/reject :unsupported-private-version))
             (when-not (object-map? (.-metadata body)) (m/reject :invalid-metadata))
             (when (= kind "wnfs/priv/dir")
               (when-not (and (object-map? (.-entries body)) (<= (count (js/Object.keys (.-entries body))) 1000))
                 (m/reject :invalid-directory-entries))
               (doseq [name (js/Object.keys (.-entries body))] (admit-segment name)))
             (when-not (and (array? (.-previous body)) (<= (.-length (.-previous body)) 1024))
               (m/reject :invalid-backlinks))
             (doseq [back (.-previous body)]
               (when-not (and (array? back) (= 2 (.-length back))
                              (m/bounded-int? (aget back 0)) (pos? (aget back 0)))
                 (m/reject :invalid-backlink))
               (let [wrapped (bytes (aget back 1) nil)]
                 (when-not (and (<= 16 (count wrapped) 128) (zero? (mod (count wrapped) 8)))
                   (m/reject :invalid-backlink))))
             (when-not (some #(= % (vec (.-bytes header-cid))) (:cids group)) (m/reject :unreachable-header))
             (let [header
                   (when-let [temporal (:temporal-key ref)]
                     (let [header (b/decode (kw/unwrap-key temporal ((:get store) header-cid)))
                           r (parse-ratchet (.-ratchet header))
                           name (bytes (.-name header) 256)]
                       (a/admit-element (bytes (.-inumber header) 32))
                       (when-not (and (= temporal (ratchet/temporal-key h/provider r))
                                      (= (:snapshot-key ref) (m/snapshot-key h/provider temporal))
                                      (= (:name group) (revision-name (:modulus loaded-forest) name r)))
                         (m/reject :header-key-name-mismatch))
                       {:ratchet r :name name :inumber (bytes (.-inumber header) 32)}))]
               {:kind kind :body body :header header :ref ref})))))
     (defn resolve-node [store loaded-forest root-ref path]
       (when-not (and (vector? path) (<= (count path) 128)) (m/reject :path-bound))
       (doseq [name path] (admit-segment name))
       (loop [node (load-node store loaded-forest root-ref) path path seen #{}]
         (if (empty? path) node
             (do
               (when-not (= "wnfs/priv/dir" (:kind node)) (m/reject :not-directory))
               (let [cid (.toString (:content-cid (:ref node)))
                     entry (when (js/Object.hasOwn (.-entries (:body node)) (first path))
                             (aget (.-entries (:body node)) (first path)))]
                 (when (contains? seen cid) (m/reject :private-cycle))
                 (when-not entry (m/reject :missing-path))
                 (recur (load-node store loaded-forest (parse-ref (:ref node) entry))
                        (subvec path 1) (conj seen cid)))))))
     (defn stream-file [store loaded-forest node consume]
       (when-not (= "wnfs/priv/file" (:kind node)) (m/reject :not-file))
       (let [content (.-content (:body node)) variants (vec (js/Object.keys content))]
         (when-not (= 1 (count variants)) (m/reject :invalid-file-content))
         (case (first variants)
           "inline" (let [data (.-data (.-inline content))]
                      (when-not (array? data) (m/reject :invalid-inline-data))
                      (consume (m/octets (vec data))))
           "external"
           (let [ext (.-external content) key (bytes (.-key ext) 32)
                 base (bytes (.-baseName ext) 256) block-count (.-blockCount ext) size (.-blockContentSize ext)]
             (when-not (and (m/bounded-int? block-count) (<= block-count 4096)
                            (m/bounded-int? size) (<= 1 size m/max-plaintext-block))
               (m/reject :external-bound))
             (doseq [i (range block-count)]
               (let [label (shard-name (:modulus loaded-forest) base key i)
                     cids (forest/lookup h/hash-label (:tree loaded-forest) label)]
                 (when-not (= 1 (count cids)) (m/reject :missing-or-conflicting-shard))
                 (let [cid (.decode CID (b/u8 (first cids)))
                       plain (m/open-external h/provider key ((or (:get-u8 store) (:get store)) cid)
                                              {:block-size size :block-count block-count} i)]
                   (consume plain)))))
           (m/reject :unknown-content-variant))))
     (defn read-file [store loaded-forest node]
       (let [parts (atom []) total (atom 0)]
         (stream-file store loaded-forest node
                      (fn [part]
                        (when (> (swap! total + (m/blen part)) (* 64 1024 1024)) (m/reject :read-file-bound))
                        ;; read-file is the small-input convenience API: it returns a vector.
                        (swap! parts conj (if (m/u8? part) (vec part) part))))
         (vec (mapcat identity @parts))))))
