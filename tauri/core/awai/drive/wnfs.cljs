(ns awai.drive.wnfs
  (:require [awai.drive.domain :as domain]
            [awai.drive.encrypted :as encrypted]
            [awai.wnfs.blocks :as blocks]
            [awai.wnfs.private-node :as node]
            [awai.wnfs.private-tree :as tree]
            [awai.wnfs.wire :as wire]
            [clojure.string :as str]
            ["multiformats/dist/src/cid.js" :refer [CID]]
            ["awai-wnfs-ports/pack.js" :as car]))

(def profile "awai.drive.wnfs.v1")

(defn- path-parts [path]
  (when-not (and (string? path) (seq path) (domain/valid-path? path))
    (encrypted/refuse))
  (str/split path #"/"))

(defn- block-store []
  (let [objects (atom {})]
    {:objects objects
     :put (fn [codec bytes]
            (let [cid (blocks/cid-of codec bytes)]
              (swap! objects assoc (.toString cid) #js {:cid cid :bytes (blocks/u8 bytes)})
              cid))}))

(defn- setup []
  ;; WebCrypto creates a fresh 2048-bit modulus. The private key is
  ;; non-extractable and discarded; the accumulator uses the public modulus.
  (-> (.generateKey js/crypto.subtle
                    #js {:name "RSA-OAEP" :modulusLength 2048
                         :publicExponent (js/Uint8Array.from #js [1 0 1]) :hash "SHA-256"}
                    false #js ["encrypt" "decrypt"])
      (.then #(.exportKey js/crypto.subtle "jwk" (.-publicKey %)))
      (.then (fn [key]
               (let [n (.replaceAll (.replaceAll (.-n key) "-" "+") "_" "/")
                     modulus (vec (map #(.charCodeAt % 0) (js/atob n)))]
                 (when-not (= 256 (count modulus)) (encrypted/refuse))
                 {:modulus modulus :generator (conj (vec (repeat 255 0)) 4)})))))

(defn seal-snapshot [record secret rows]
  (when (> (.-length rows) 1000) (encrypted/refuse))
  (let [files (into {} (map (fn [row] [(path-parts (.-path row)) (tree/u8-source (.-bytes row))]) (array-seq rows)))
        dirs (vec (or (.-directories rows) #js []))]
    (when-not (= (count files) (.-length rows)) (encrypted/refuse))
    (-> (setup)
        (.then (fn [params]
                 (let [store (block-store)
                       {:keys [forest access]} (tree/seal-tree store params files (js/Math.floor (/ (js/Date.now) 1000)))
                       pack (car/write forest (into-array (vals @(:objects store))))
                       manifest #js {:schema profile :forest (.toString forest) :access (tree/access-json access)
                                     :paths (clj->js (sort (map #(.-path %) (array-seq rows))))
                                     :directories (clj->js dirs)}
                       payload #js [#js {:path "private.car" :bytes pack}
                                    #js {:path "root.json" :bytes (encrypted/utf8 (js/JSON.stringify manifest))}]]
                   (aset payload "profile" profile)
                   (-> (encrypted/seal-snapshot record secret payload)
                       (.then (fn [sealed] (aset sealed "basis" (.toString forest)) sealed)))))))))

(defn- open-wnfs [rows]
  (let [by-path (into {} (map (fn [row] [(.-path row) (.-bytes row)]) (array-seq rows)))]
    (when-not (= #{"private.car" "root.json"} (set (keys by-path))) (encrypted/refuse))
    (let [manifest (js/JSON.parse (encrypted/text (get by-path "root.json")))
          paths (.-paths manifest)
          dirs (.-directories manifest)]
      (when-not (and (= profile (.-schema manifest)) (array? paths) (array? dirs)
                     (<= (+ (.-length paths) (.-length dirs)) 1000)
                     (= (.-length paths) (count (set paths)))) (encrypted/refuse))
      (doseq [dir dirs] (path-parts dir))
      (let [root (.parse CID (.-forest manifest))
            access (tree/load-access (.-access manifest))
            reader (car/read (get by-path "private.car"))
            roots (.getRoots reader)
            verified (reduce (fn [objects block]
                               (let [cid (.-cid block) id (.toString cid) bytes (.-bytes block)]
                                 (when (or (contains? objects id) (>= (count objects) 10000)) (encrypted/refuse))
                                 (blocks/verify-block cid bytes)
                                 (assoc objects id bytes))) {} (.blocks reader))
            get-block (fn [cid] (or (get verified (.toString cid)) (encrypted/refuse)))
            store {:get #(vec (get-block %)) :get-u8 get-block}]
        (when-not (and (= 1 (.-length roots)) (.equals root (aget roots 0))) (encrypted/refuse))
        (let [forest (wire/load-forest store root)
              restored (into-array
                         (for [path paths]
                           (let [file (node/resolve-node store forest access (path-parts path))]
                             #js {:path path :bytes (blocks/u8 (node/read-file store forest file))})))]
          (aset restored "directories" dirs)
          (aset restored "basis" (.toString root))
          restored)))))

(defn open-snapshot [record secret root pack]
  (-> (encrypted/open-snapshot record secret root pack)
      (.then (fn [rows]
               (case (.-profile rows)
                 "owner-store-v1" rows
                 "awai.drive.wnfs.v1" (open-wnfs rows)
                 (encrypted/refuse))))))
