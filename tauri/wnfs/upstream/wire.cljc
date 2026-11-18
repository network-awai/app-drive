(ns awai.wnfs.wire
  (:require [awai.wnfs.model :as m]
            [awai.wnfs.forest :as f]
            [awai.wnfs.blocks :as b]
            [awai.wnfs.otehon :as host]
            #?(:cljs ["multiformats/cid" :refer [CID]])))

#?(:cljs
   (do
     (defn- cid-object [bytes] (.decode CID (b/u8 bytes)))

     (declare store-node)

     (defn- node-object [store [mask entries]]
         #js [(b/u8 mask)
              (into-array
               (map (fn [entry]
                      (if (map? entry)
                        (store-node store (:node entry))
                        (into-array
                         (map (fn [[label cids]]
                                #js [(b/u8 label) (into-array (map cid-object cids))]) entry))))
                    entries))])

     (defn store-node [store tree]
       ((:put store) 113 (b/encode (node-object store tree))))

     (defn- accumulator [modulus generator]
       (m/sized modulus 256)
       ;; Width/range admission is not proof that an RSA setup is secure.
       (when-not (and (>= (first modulus) 128) (odd? (last modulus)))
         (m/reject :invalid-modulus))
       (f/label modulus generator))

     (defn store-forest [store modulus generator records]
       (accumulator modulus generator)
       (let [tree (f/build host/hash-label modulus records)
             ;; Root's sparse node is inline; only child nodes become links.
             root (node-object store tree)]
         ((:put store) 113
          (b/encode #js {:structure "hamt" :version "0.1.0" :root root
                        :accumulator #js {:modulus (b/u8 modulus) :generator (b/u8 generator)}}))))

     (defn load-forest [store trusted-cid]
       (when-not (= 113 (.-code trusted-cid)) (m/reject :invalid-root-codec))
       (let [root (b/decode ((:get store) trusted-cid))
             _ (when-not (and (= ["accumulator" "root" "structure" "version"]
                                (vec (sort (js/Object.keys root))))
                              (= "hamt" (.-structure root)) (= "0.1.0" (.-version root)))
                 (m/reject :unknown-forest-format))
             setup (.-accumulator root)
             _ (when-not (= ["generator" "modulus"] (vec (sort (js/Object.keys setup))))
                 (m/reject :unknown-accumulator-format))
             _ (when-not (and (instance? js/Uint8Array (.-modulus setup))
                              (instance? js/Uint8Array (.-generator setup)))
                 (m/reject :invalid-accumulator-bytes))
             modulus (vec (.-modulus setup)) generator (vec (.-generator setup))
             _ (accumulator modulus generator)
             records (atom []) visited (atom #{}) nodes (atom 0)]
         (letfn [(read-node [node depth]
                   (when (or (> depth 63) (> (swap! nodes inc) 10000)) (m/reject :forest-bounds))
                   (when-not (and (array? node) (= 2 (.-length node))
                                  (instance? js/Uint8Array (aget node 0)) (array? (aget node 1)))
                     (m/reject :invalid-sparse-node))
                   (let [mask (m/sized (vec (aget node 0)) 2)
                         entries (vec (aget node 1))
                         slots (filter #(some? (f/sparse-index mask %)) (range 16))]
                     (when-not (= (count entries) (count slots)) (m/reject :invalid-sparse-count))
                     [mask
                      (mapv
                       (fn [entry]
                         (if-let [cid (.asCID CID entry)]
                           (do
                             (when-not (= 113 (.-code cid)) (m/reject :invalid-child-codec))
                             (when (contains? @visited (.toString cid)) (m/reject :duplicate-child))
                             (swap! visited conj (.toString cid))
                             {:node (read-node (b/decode ((:get store) cid)) (inc depth))})
                           (do
                             (when-not (and (array? entry) (<= 1 (.-length entry) 3))
                               (m/reject :invalid-bucket))
                             (mapv
                              (fn [pair]
                                (when-not (and (array? pair) (= 2 (.-length pair))
                                               (instance? js/Uint8Array (aget pair 0))
                                               (array? (aget pair 1)))
                                  (m/reject :invalid-bucket-entry))
                                (let [label (f/label modulus (vec (aget pair 0)))
                                      cids (mapv (fn [cid]
                                                   (when-not (.asCID CID cid) (m/reject :invalid-cid-link))
                                                   (vec (.-bytes cid))) (aget pair 1))
                                      record [label (f/admit-cid-set cids)]]
                                  (when (>= (count @records) 10000) (m/reject :forest-bounds))
                                  (swap! records conj record)
                                  record)) entry)))) entries)]))]
           (let [tree (read-node (.-root root) 0)
                 rebuilt (f/build host/hash-label modulus @records)]
             ;; Checks bucket hash order, duplicate names, path prefixes and
             ;; canonical collision splitting, independently of map iteration.
             (when-not (= tree rebuilt) (m/reject :noncanonical-hamt))
             {:tree tree :modulus modulus :generator generator}))))))
