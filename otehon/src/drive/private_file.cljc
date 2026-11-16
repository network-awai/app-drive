(ns drive.private-file
  "Bounded user-side file and metadata envelope. The trusted host owns
   identity, dango policy, signed basis admission, consent and delivery."
  (:require [awai.browser-chunk-envelope :as envelope]
            [awai.browser-recipient-chunks :as recipient-chunks]
            [awai.delivery-key-binding :as key-binding]
            [awai.browser-async-authority :as async-authority]
            [awai.browser-chunk-blocks :as blocks]))

(def max-header 16384)
(def max-frame (* envelope/chunk-bytes envelope/max-chunks))
(defn refuse [] (throw (ex-info "private file refused" {})))
(defn text? [x n] (and (string? x) (<= 1 (count x) n)))
(defn- expected? [expected]
  (and (map? expected) (= #{:resource :recipients :owner :id} (set (keys expected)))
       (text? (:resource expected) 256) (set? (:recipients expected))
       (<= 1 (count (:recipients expected)) 32) (every? #(text? % 256) (:recipients expected))
       (text? (:owner expected) 256) (contains? (:recipients expected) (:owner expected))
       (text? (:id expected) 128)))
(defn metadata? [m]
  (and (map? m) (= #{:schema :id :owner :name :mime :modified :size} (set (keys m)))
       (= "awai.drive/private-file-v1" (:schema m))
       (text? (:id m) 128) (text? (:owner m) 256) (text? (:name m) 1024)
       (string? (:mime m)) (<= (count (:mime m)) 256)
       (js/Number.isSafeInteger (:modified m))
       (js/Number.isSafeInteger (:size m)) (<= 0 (:size m) max-frame)))

(defn prepare-passkey-seal!
  "Prepare a selected File and recipient proof. The trusted host displays
   owner, file and recipients, then invokes :confirm! from a direct gesture.
   The File is read locally only after the Passkey proof succeeds."
  [host file-id file nonce pub pq-pub presentation others]
  (when-not (and (map? host) (text? (:owner host) 256) (text? (:resource host) 256)
                 (map? (:recipient-host host)) (text? file-id 128) (instance? js/File file)
                 (vector? others) (every? map? others)) (refuse))
  (let [ids (conj (set (map :holder others)) (get-in host [:recipient-host :holder]))
        metadata {:schema "awai.drive/private-file-v1" :id file-id :owner (:owner host)
                  :name (.-name file) :mime (.-type file) :modified (.-lastModified file) :size (.-size file)}
        header (.encode (js/TextEncoder.) (js/JSON.stringify (clj->js metadata)))
        header-size (.-length header) total (+ 4 header-size (:size metadata))]
    (.fill header 0)
    (when-not (and (= (count ids) (inc (count others))) (contains? ids (:owner host))
                   (metadata? metadata) (<= 1 header-size max-header) (<= total max-frame)) (refuse))
    (let [load! (fn []
                  (-> (.arrayBuffer file)
                      (.then (fn [buffer]
                        (let [bytes (js/Uint8Array. buffer) frame (js/Uint8Array. total)
                              h (.encode (js/TextEncoder.) (js/JSON.stringify (clj->js metadata)))]
                          (try
                            (when-not (and (= (:size metadata) (.-length bytes)) (= header-size (.-length h))) (refuse))
                            (.setUint32 (js/DataView. (.-buffer frame)) 0 header-size false)
                            (.set frame h 4) (.set frame bytes (+ 4 header-size))
                            (mapv (fn [start] (.slice frame start (min total (+ start envelope/chunk-bytes))))
                                  (range 0 total envelope/chunk-bytes))
                            (finally (.fill h 0) (.fill frame 0) (.fill bytes 0))))))))]
      (-> (recipient-chunks/prepare! (assoc host :load! load!) nonce pub pq-pub presentation others)
          (.then (fn [prepared] (when-not (= ids (set (:recipients prepared))) (refuse)) prepared))))))

(defn seal-file!
  "Accept a user-selected File, encrypt content and metadata together. host's
   owner and opaque resource must come from trusted user-side identity/state."
  [host file-id file recipients]
  (when-not (and (map? host) (text? (:owner host) 256) (text? (:resource host) 256)
                 (text? file-id 128) (instance? js/File file) (vector? recipients)
                 (some #(= (:owner host) (:holder %)) recipients)) (refuse))
  (let [metadata {:schema "awai.drive/private-file-v1" :id file-id :owner (:owner host)
                  :name (.-name file) :mime (.-type file) :modified (.-lastModified file) :size (.-size file)}
        header (.encode (js/TextEncoder.) (js/JSON.stringify (clj->js metadata)))
        total (+ 4 (.-length header) (:size metadata))]
    (when-not (and (metadata? metadata) (<= 1 (.-length header) max-header) (<= total max-frame))
      (.fill header 0) (refuse))
    (-> (.arrayBuffer file)
        (.then (fn [buffer]
          (let [bytes (js/Uint8Array. buffer) frame (js/Uint8Array. total)]
            (try
              (when-not (= (:size metadata) (.-length bytes)) (refuse))
              (.setUint32 (js/DataView. (.-buffer frame)) 0 (.-length header) false)
              (.set frame header 4) (.set frame bytes (+ 4 (.-length header)))
              (let [parts (mapv (fn [start] (.slice frame start (min total (+ start envelope/chunk-bytes))))
                                (range 0 total envelope/chunk-bytes))]
                (-> (js/Promise.resolve nil)
                    (.then (fn [] (envelope/seal host (:resource host) parts recipients)))
                    (.finally (fn [] (doseq [part parts] (.fill part 0)) (.fill frame 0) (.fill bytes 0)))))
              (catch :default e (.fill frame 0) (.fill bytes 0) (throw e))))))
        (.finally (fn [] (.fill header 0))))))

(defn open-file!
  "Only a trusted host may supply :admit! and the independently authenticated
   expected resource, recipient set, owner and file id. Returns metadata and
   bytes; the caller owns and must clear the returned byte buffer."
  [host object expected recipient identity]
  (when-not (and (map? host) (fn? (:admit! host)) (expected? expected)) (refuse))
  (let [admit! (:admit! host)
        scoped (assoc host :admit! (fn [_ candidate] (admit! expected candidate)))]
    (-> (envelope/open scoped object (select-keys expected [:resource :recipients]) recipient identity)
        (.then (fn [parts]
          (let [total (reduce + (map #(.-length %) parts)) frame (js/Uint8Array. total)]
            (try
              (loop [offset 0 [part & more] parts]
                (when part (.set frame part offset) (recur (+ offset (.-length part)) more)))
              (when (< total 4) (refuse))
              (let [header-size (.getUint32 (js/DataView. (.-buffer frame)) 0 false)
                    end (+ 4 header-size)]
                (when-not (and (<= 1 header-size max-header) (<= end total)) (refuse))
                (let [metadata (js->clj (js/JSON.parse (.decode (js/TextDecoder. "utf-8" #js {:fatal true})
                                                                 (.subarray frame 4 end))) :keywordize-keys true)]
                  (when-not (and (metadata? metadata) (= (:id expected) (:id metadata))
                                 (= (:owner expected) (:owner metadata)) (= (:size metadata) (- total end))) (refuse))
                  {:metadata metadata :bytes (.slice frame end)}))
              (finally (.fill frame 0) (doseq [part parts] (.fill part 0))))))))))

(defn- current-host [host cid expected]
  (when-not (and (map? host) (fn? (:delivery-current! host)) (fn? (:admit-cid! host))) (refuse))
  {:current! (fn [] (and (true? ((:delivery-current! host)))
                         (true? ((:admit-cid! host) cid expected))))
   :authorization! (:authorization! host)
   :admit-packed! (fn [actual] (= cid actual))})

(defn publish!
  "Publish only a sealed file whose manifest CID and complete basis the trusted
   host admits. The client, service consent and revocation signal remain owned
   by the runtime; already delivered blocks cannot be recalled."
  [host client object expected]
  (when-not (and (expected? expected) (= (:owner host) (:owner expected))
                 (= (:resource host) (:resource expected))
                 (= (:resource expected) (get-in object [:envelope :envelope/id]))
                 (= (:recipients expected) (set (map :recipient/id (get-in object [:envelope :envelope/recipients]))))) (refuse))
  (-> (key-binding/verify! host object expected)
      (.then (fn [bindings]
               (let [guarded (key-binding/guarded-host host expected bindings)
                     cid (:cid (blocks/pack object))]
                 (blocks/publish! (current-host guarded cid expected) (async-authority/client-for-host guarded client) object))))))

(defn receive!
  "Fetch a host-authenticated manifest and its CID-verified blocks, then open
   while rechecking the host's file basis and current delivery authority."
  [host client cid expected recipient identity]
  (when-not (and (expected? expected) (map? host) (fn? (:admit! host))) (refuse))
  (let [bindings (key-binding/capture! host expected)
        guarded (key-binding/guarded-host host expected bindings)
        scoped (current-host guarded cid expected)]
    (-> (blocks/receive! scoped (async-authority/client-for-host guarded client) cid (select-keys expected [:resource :recipients]))
        (.then (fn [object]
                 (-> (key-binding/verify! guarded object expected)
                     (.then (fn [actual]
                       (when-not (= bindings actual) (refuse))
                       (open-file! (assoc host :admit! (fn [basis candidate]
                                     (and (true? ((:current! scoped)))
                                          (true? ((:admit! host) basis candidate)))))
                                   object expected recipient identity)))))))))
