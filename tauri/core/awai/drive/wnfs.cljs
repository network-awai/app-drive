(ns awai.drive.wnfs
  (:require [cljs.reader :as edn]
            [clojure.string :as str]
            [awai.drive.domain :as d]
            [awai.drive.encrypted :as encrypted]
            [awai.fs.car :as car]
            [awai.wnfs.accumulator :as accumulator]
            [awai.wnfs.blocks :as blocks]
            [awai.wnfs.model :as model]
            [awai.wnfs.private-node :as node]
            [awai.wnfs.private-tree :as tree]
            [awai.wnfs.wire :as wire]
            [envelope.keystore :as keys]
            ["@noble/curves/abstract/modular" :as modular]
            ["@awai/drive-wnfs-platform/cid" :refer [CID]]))

(def snapshot-limit (* 32 1024 1024))
(def pack-limit (* 12 1024 1024))
(def capsule-schema "awai.wnfs.owner-root.v1")

;; Published WNFS RSA-2048 challenge modulus (rs-wnfs 0.3.0 accumulator
;; setup). Drive uses it for labels, with a freshly sampled generator per
;; snapshot. There is no accumulator-based write authorization in this profile.
(def modulus
  (js/BigInt "0xc7970ceedcc3b0754490201a7aa613cd73911081c790f5f1a8726f463550bb5b7ff0db8e1ea1189ec72f93d1650011bd721aeeacc2acde32a04107f0648c2813a31f5b0b7765ff8b44b4b6ffc93384b646eb09c7cf5e8592d40ea33c80039f35b4f14a04b51f7bfd781be4d1673164ba8eb991c2c4d730bbbe35f592bdef524af7e8daefd26c66fc02c479af89d64d373f442709439de66ceb955f3ea37d5159f6135809f85334b5cb1813addc80cd05609f10ac6a95ad65872c909525bdad32bc729592642920f24c61dc5b3c3b7923e56b16a4d9d373d8721f24a3fc0f1b3131f55615172866bccc30f95054c824e733a5eb6817f7bc16399d48c6361cc7e5"))

(defn refuse [] (throw (js/Error. "invalid-wnfs-snapshot")))

(defn- random-generator []
  (let [one (js/BigInt 1)]
    (loop [attempt 0]
      (when (= attempt 64) (refuse))
      (let [raw (.getRandomValues js/crypto (js/Uint8Array. 256))
            x (accumulator/integer-from raw)
            g (modular/pow x (js/BigInt 2) modulus)]
        (if (and (> x one) (< x modulus) (> g one)
                 (try (modular/invert g modulus) true (catch :default _ false)))
          (accumulator/integer-bytes g 256)
          (recur (inc attempt)))))))

(defn- store []
  (let [entries (atom {})
        get-block (fn [cid]
                    (or (get-in @entries [(.toString cid) :bytes]) (refuse)))]
    {:entries entries
     :get (fn [cid] (vec (get-block cid)))
     :get-u8 get-block
     :put (fn [codec bytes]
            (let [bytes (blocks/u8 bytes)
                  cid (blocks/cid-of codec bytes)]
              (swap! entries assoc (.toString cid) {:cid cid :bytes bytes})
              cid))}))

(defn- path-parts [path]
  (when-not (and (d/valid-path? path) (seq path)) (refuse))
  (str/split path #"/"))

(defn- tree-sources [rows]
  (let [rows (vec (array-seq rows))
        paths (mapv #(path-parts (.-path %)) rows)
        parents (into #{} (mapcat (fn [p] (map #(subvec p 0 %) (range 1 (count p)))) paths))
        total (reduce + (map #(if (.-directory %) 0 (.-length (.-bytes %))) rows))]
    (when-not (and (<= 1 (count rows) 1000) (= (count paths) (count (set paths)))
                   (<= total snapshot-limit))
      (refuse))
    (into {}
          (keep (fn [[p row]]
                  (if (.-directory row)
                    (when-not (contains? parents p) [p {}])
                    [p (tree/u8-source (.-bytes row))]))
                (map vector paths rows)))))

(defn- encode-pack [header sections size]
  (let [out (js/Uint8Array. size)]
    (.set out header 0)
    (reduce (fn [at [length cid bytes]]
              (.set out length at)
              (.set out cid (+ at (.-length length)))
              (.set out bytes (+ at (.-length length) (.-length cid)))
              (+ at (.-length length) (.-length cid) (.-length bytes)))
            (.-length header) sections)
    out))

(defn- make-packs [store forest]
  (let [header (blocks/u8 (car/carv1-encode {:roots [(vec (.-bytes forest))] :blocks []}))
        forest-id (.toString forest)
        entries @(:entries store)
        section (fn [{:keys [cid bytes]}]
                  (let [id (.-bytes cid)]
                    [(blocks/u8 (car/encode-uvarint (+ (.-length id) (.-length bytes)))) id bytes]))
        root-section (section (get entries forest-id))
        section-size (fn [parts] (reduce + (map #(.-length %) parts)))
        initial (+ (.-length header) (section-size root-section))
        packs (array)]
    (loop [rows (seq (sort-by key (dissoc entries forest-id))) sections [root-section] size initial]
      (if-let [[_ row] (first rows)]
        (let [part (section row)
              next-size (+ size (section-size part))]
          (if (> next-size pack-limit)
            (do (.push packs (encode-pack header sections size))
                (recur rows [root-section] initial))
            (recur (next rows) (conj sections part) next-size)))
        (do (.push packs (encode-pack header sections size)) packs)))))

(defn- read-packs [packs forest]
  (let [out (store)
        forest-id (.toString forest)
        header (blocks/u8 (car/carv1-encode {:roots [(vec (.-bytes forest))] :blocks []}))]
    (when-not (<= 1 (.-length packs) 8) (refuse))
    (doseq [pack (array-seq packs)]
      (let [size (.-length pack) start (.-length header)]
        (when-not (and (> size start) (<= size pack-limit)
                       (blocks/bytes= header (.subarray pack 0 start)))
          (refuse))
        (loop [at start found-root false]
          (if (= at size)
            (when-not found-root (refuse))
            (let [prefix (vec (.subarray pack at (min (+ at 5) size)))
                  [length used] (car/decode-uvarint prefix 0)
                  begin (+ at used)
                  end (+ begin length)
                  _ (when-not (and (<= 1 length 262240) (<= end size)
                                   (= (subvec prefix 0 used) (car/encode-uvarint length)))
                      (refuse))
                  [cid _] (array-seq (.decodeFirst CID (.subarray pack begin (min end (+ begin 96)))))
                  id (.toString (blocks/admit-cid cid))
                  bytes (blocks/verify-block cid (.subarray pack (+ begin (.-length (.-bytes cid))) end))
                  root? (= id forest-id)]
              (when (or (and root? found-root)
                        (and (not root?) (contains? @(:entries out) id)))
                (refuse))
              (swap! (:entries out) assoc id {:cid cid :bytes bytes})
              (recur end (or found-root root?)))))))
    out))

(defn- capsule-pack [record secret forest access]
  (let [record (edn/read-string record)
        objects (atom {})
        plaintext (blocks/u8
                   (blocks/encode
                    #js {:schema capsule-schema :forest forest
                         :access #js {"wnfs/share/temporal"
                                      #js {:label (blocks/u8 (:label access))
                                           :temporalKey (blocks/u8 (:temporal-key access))
                                           :contentCid (:content-cid access)}}}))]
    (-> (keys/open-record record secret)
        (.then (fn [owner]
                 (-> (encrypted/object! objects (assoc owner :pq-pub (:pq-pub record)) plaintext)
                     (.finally #(encrypted/wipe! owner)))))
        (.then (fn [root]
                 (let [cid #(car/cid-bytes car/codec-raw (encrypted/unhex %))]
                   #js {:root root
                        :bytes (blocks/u8
                                (car/carv1-encode
                                 {:roots [(cid root)]
                                  :blocks (mapv (fn [[id bytes]] {:cid (cid id) :bytes (vec bytes)})
                                                (sort-by key @objects))}))})))
        (.finally #(.fill plaintext 0)))))

(defn- open-capsule [record secret root pack]
  (when (> (.-length pack) 262144) (refuse))
  (let [{:keys [roots blocks]} (car/carv1-decode (vec pack))
        cid #(car/cid-bytes car/codec-raw (encrypted/unhex %))
        objects (atom {})]
    (when-not (and (= [(cid root)] roots) (<= 2 (count blocks) 16)) (refuse))
    (-> (encrypted/sequence! blocks
         (fn [{:keys [cid bytes]}]
           (let [data (blocks/u8 bytes)]
             (-> (encrypted/sha data)
                 (.then (fn [id]
                          (when (or (contains? @objects id)
                                    (not= cid (car/cid-bytes car/codec-raw (encrypted/unhex id))))
                            (refuse))
                          (swap! objects assoc id data)))))))
        (.then #(keys/open-record (edn/read-string record) secret))
        (.then (fn [owner]
                 (-> (encrypted/open-object @objects root owner)
                     (.finally #(encrypted/wipe! owner)))))
        (.then (fn [plain]
                 (try
                   (let [capsule (blocks/decode plain)
                         forest (blocks/admit-cid (.-forest capsule))
                         access (.-access capsule)
                         ref (aget access "wnfs/share/temporal")]
                     (when-not (and (= capsule-schema (.-schema capsule)) (= 113 (.-code forest))
                                    (= ["wnfs/share/temporal"] (vec (js/Object.keys access))))
                       (refuse))
                     {:forest forest
                      :access (tree/load-access
                               #js {"wnfs/share/temporal"
                                    #js {:label (.-label ref) :temporalKey (.-temporalKey ref)
                                         :contentCid (.-bytes (node/raw-link (.-contentCid ref)))}})})
                   (finally (.fill plain 0))))))))

(defn seal [record secret rows]
  (let [store (store)
        sealed (tree/seal-tree store {:modulus (accumulator/integer-bytes modulus 256)
                                       :generator (random-generator)}
                               (tree-sources rows) (js/Math.floor (/ (js/Date.now) 1000)))]
    (-> (capsule-pack record secret (:forest sealed) (:access sealed))
        (.then (fn [capsule]
                 #js {:capsule capsule :packs (make-packs store (:forest sealed))})))))

(defn open [record secret root capsule packs]
  (-> (open-capsule record secret root capsule)
      (.then (fn [{:keys [forest access]}]
               (let [store (read-packs packs forest)
                     loaded (wire/load-forest store forest)
                     rows (array)
                     total (atom 0)]
                 (letfn [(visit [ref path depth]
                           (when (or (> depth 16) (>= (.-length rows) 1000)) (refuse))
                           (let [{:keys [kind body] :as entry} (node/load-node store loaded ref)]
                             (case kind
                               "wnfs/priv/dir"
                               (do
                                 (when (seq path) (.push rows #js {:path path :directory true}))
                                 (doseq [name (sort (js/Object.keys (.-entries body)))]
                                   (let [child (if (seq path) (str path "/" name) name)]
                                     (path-parts child)
                                     (visit (node/parse-ref ref (aget (.-entries body) name)) child (inc depth)))))
                               "wnfs/priv/file"
                               (let [chunks (array)]
                                 (node/stream-file store loaded entry
                                   (fn [part]
                                     (let [data (blocks/u8 part)]
                                       (when (> (swap! total + (.-length data)) snapshot-limit) (refuse))
                                       (.push chunks data))))
                                 (.push rows #js {:path path :chunks chunks}))
                               (refuse))))]
                   (visit access "" 0)
                   rows))))))
