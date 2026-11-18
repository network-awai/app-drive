(ns awai.wnfs.private-tree
  (:require [awai.wnfs.model :as m]
            [awai.wnfs.otehon :as h]
            [awai.wnfs.blocks :as b]
            [awai.wnfs.wire :as wire]
            [awai.wnfs.ratchet :as ratchet]
            [awai.wnfs.accumulator :as a]
            [awai.wnfs.keywrap :as kw]
            [awai.wnfs.private-node :as pn]
            #?(:cljs ["multiformats/cid" :refer [CID]])
            #?(:cljs ["node:fs" :as fs])))

#?(:cljs
   (do
     (defn byte-source [bytes]
       (m/octets bytes)
       {:size (count bytes)
        :read-block (fn [index] (let [at (* index m/max-plaintext-block)]
                                 (subvec bytes at (min (count bytes) (+ at m/max-plaintext-block)))))})
     (defn u8-source
       "Source over a Uint8Array: blocks are subarray views (no copy, no vector conversion)."
       [bytes]
       (when-not (m/u8? bytes) (m/reject :invalid-bytes))
       {:size (.-length bytes)
        :read-block (fn [index] (let [at (* index m/max-plaintext-block)]
                                  (.subarray bytes at (min (.-length bytes) (+ at m/max-plaintext-block)))))})
     (defn file-source
       "Streaming source over a regular file: each block is read on demand into its own Uint8Array,
  so memory does not grow with file size. The caller closes it with (:close source)."
       [file]
       (let [fd (fs/openSync file (bit-or (.-O_RDONLY fs/constants) (.-O_NOFOLLOW fs/constants)))
             stat (fs/fstatSync fd)]
         (when-not (.isFile stat) (fs/closeSync fd) (m/reject :invalid-source-file))
         (let [size (.-size stat)]
           {:size size
            :close (fn [] (fs/closeSync fd))
            :read-block (fn [index]
                          (let [at (* index m/max-plaintext-block)
                                length (min m/max-plaintext-block (- size at))
                                buffer (js/Uint8Array. length)]
                            (loop [n 0]
                              (when (< n length)
                                (let [got (fs/readSync fd buffer n (- length n) (+ at n))]
                                  (when (zero? got) (m/reject :source-changed))
                                  (recur (+ n got)))))
                            buffer))})))
     (defn- new-header [ctx parent-name]
       (let [inumber (a/random-element)]
         {:inumber inumber :name (a/accumulate (:modulus ctx) parent-name inumber)
          :ratchet (ratchet/zero-state h/provider (h/random-key) (h/random-key))}))
     (defn- add-record! [ctx name cids]
       (swap! (:records ctx) conj [name (mapv #(vec (.-bytes %)) cids)]))
     (defn- store-private! [ctx header kind content]
       (let [r (:ratchet header) temporal (ratchet/temporal-key h/provider r)
             snapshot (m/snapshot-key h/provider temporal)
             name (pn/revision-name (:modulus ctx) (:name header) r)
             header-bytes (b/encode #js {:inumber (b/u8 (:inumber header))
                                        :name (b/u8 (:name header)) :ratchet (pn/ratchet-object r)})
             header-cid ((:put (:store ctx)) 85 (kw/wrap-key temporal header-bytes))
             body (js/Object.assign #js {:version "1.0.0" :headerCid header-cid :previous #js []
                                         :metadata #js {:created (:time ctx) :modified (:time ctx)}} content)
             wrapper (js/Object.create nil)
             _ (aset wrapper kind body)
             cid ((:put (:store ctx)) 85 (pn/wrap-content snapshot (b/encode wrapper)))]
         (add-record! ctx name [header-cid cid])
         {:label (h/hash-label name) :temporal-key temporal :snapshot-key snapshot :content-cid cid}))
     (defn- store-file! [ctx parent-name source]
       (let [header (new-header ctx parent-name) size (:size source)]
         (when-not (and (m/bounded-int? size) (<= size (* 4096 m/max-plaintext-block))
                        (fn? (:read-block source))) (m/reject :file-source-bound))
         (if (< size 10000)
           (let [data ((:read-block source) 0)]
             (m/sized data size)
             (store-private! ctx header "wnfs/priv/file" #js {:content #js {:inline #js {:data (if (m/u8? data) (js/Array.from data) (clj->js data))}}}))
           (let [key (h/random-key)
                 base (a/accumulate (:modulus ctx) (:name header)
                                    (:bytes (a/hash-prime h/provider "wnfs/1.0/hiding segment derivation from content key" key 32)))
                 block-count (int (js/Math.ceil (/ size m/max-plaintext-block)))]
             (doseq [i (range block-count)]
               (let [plain ((:read-block source) i)
                     expected (min m/max-plaintext-block (- size (* i m/max-plaintext-block)))
                     _ (m/sized plain expected)
                     cipher (h/seal-block key plain {:block-size m/max-plaintext-block :block-count block-count} i)
                     cid ((:put (:store ctx)) 85 cipher)]
                 (add-record! ctx (pn/shard-name (:modulus ctx) base key i) [cid])))
             (store-private! ctx header "wnfs/priv/file"
                             #js {:content #js {:external #js {:key (b/u8 key) :baseName (b/u8 base)
                                                              :blockCount block-count :blockContentSize m/max-plaintext-block}}})))))
     (defn- store-dir! [ctx parent-name tree]
       (let [header (new-header ctx parent-name)
             temporal (ratchet/temporal-key h/provider (:ratchet header))
             entries (js/Object.create nil)]
         (doseq [[name value] (sort-by key tree)]
           (pn/admit-segment name)
           (let [ref (if (:read-block value) (store-file! ctx (:name header) value)
                         (store-dir! ctx (:name header) value))]
             (aset entries name (pn/ref-object temporal ref))))
         (store-private! ctx header "wnfs/priv/dir" #js {:entries entries})))
     (defn seal-tree [store setup files time]
       (when-not (and (map? files) (<= (count files) 1000) (m/bounded-int? time)) (m/reject :tree-bound))
       (let [tree (reduce (fn [tree [path source]]
                            (when-not (and (vector? path) (<= 1 (count path) 64)) (m/reject :invalid-path))
                            (doseq [segment path] (pn/admit-segment segment))
                            (when (or (get-in tree path)
                                      (some #(get-in tree (conj (subvec path 0 %) :read-block)) (range 1 (count path))))
                              (m/reject :path-conflict))
                            (assoc-in tree path source)) {} files)
             ctx {:store store :modulus (:modulus setup) :records (atom []) :time time}
             ref (store-dir! ctx (:generator setup) tree)
             root (wire/store-forest store (:modulus setup) (:generator setup) @(:records ctx))]
         {:forest root :access ref}))
     (defn access-json [ref]
       #js {"wnfs/share/temporal" #js {:label (clj->js (:label ref))
                                        :temporalKey (clj->js (:temporal-key ref))
                                        :contentCid (clj->js (vec (.-bytes (:content-cid ref))))}})
     (defn load-access [value]
       (when-not (= ["wnfs/share/temporal"] (vec (js/Object.keys value))) (m/reject :unsupported-access))
       (let [ref (aget value "wnfs/share/temporal") key (m/sized (vec (.-temporalKey ref)) 32)]
         {:label (m/sized (vec (.-label ref)) 32) :temporal-key key
          :snapshot-key (m/snapshot-key h/provider key)
          :content-cid (pn/raw-link (.decode CID (b/u8 (vec (.-contentCid ref)))))}))))
