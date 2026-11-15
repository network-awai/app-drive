(ns drive.private-file
  "Bounded user-side file and metadata envelope. The trusted host owns
   identity, dango policy, signed basis admission, consent and delivery."
  (:require [awai.browser-chunk-envelope :as envelope]))

(def max-header 16384)
(def max-frame (* envelope/chunk-bytes envelope/max-chunks))
(defn refuse [] (throw (ex-info "private file refused" {})))
(defn text? [x n] (and (string? x) (<= 1 (count x) n)))
(defn metadata? [m]
  (and (map? m) (= #{:schema :id :owner :name :mime :modified :size} (set (keys m)))
       (= "awai.drive/private-file-v1" (:schema m))
       (text? (:id m) 128) (text? (:owner m) 256) (text? (:name m) 1024)
       (string? (:mime m)) (<= (count (:mime m)) 256)
       (js/Number.isSafeInteger (:modified m)) (<= 0 (:modified m))
       (js/Number.isSafeInteger (:size m)) (<= 0 (:size m) max-frame)))

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
  (when-not (and (map? host) (fn? (:admit! host)) (map? expected)
                 (= #{:resource :recipients :owner :id} (set (keys expected)))
                 (text? (:id expected) 128) (text? (:owner expected) 256)) (refuse))
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
