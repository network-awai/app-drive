(ns drive.private-file-delivery-test
  (:require [drive.private-file :as drive] [awai.browser-chunk-blocks :as blocks]
            [awai.browser-chunk-envelope :as chunks] [dango.chain :as dango]
            [ed25519.sign :as ed] [envelope.keystore :as keys] [envelope.seal :as seal] [envelope.seal-sync :as crypto]))

(defn check [x] (when-not x (throw (js/Error. "private file delivery test failed"))))
(defn denied [f] (-> (js/Promise.resolve nil) (.then f)
                     (.then (fn [_] (throw (js/Error. "unexpected file delivery"))) (fn [_] true))))
(def seed (vec (repeat 32 9)))
(def owner "did:key:owner")
(def resource "kotoba://storage/synthetic-file")
(def pair (crypto/generate-hybrid-recipient))
(def presentation (dango/present (dango/root {:issuer-seed seed :next-seed (vec (repeat 32 10))
                   :grant {:caps #{[:data/read resource]} :holder owner :before 200} :caveats []})))
(def recipient {:holder owner :presentation presentation :pub (:pub pair) :pq-pub (:pq-pub pair)})
(def data (js/Uint8Array. (+ chunks/chunk-bytes 3)))
(.fill data 42)
(def file (js/File. #js [data] "private-filename.txt" #js {:type "text/plain" :lastModified 123}))
(def empty-file (js/File. #js [] "empty-file.txt" #js {:lastModified 123}))
(def expected {:resource resource :recipients #{owner} :owner owner :id "file-1"})
(def identity (select-keys pair [:priv :pq-priv]))
(defn clone [bytes] (js/Uint8Array.from bytes))

(-> (keys/fingerprint pair)
    (.then (fn [fp]
      (let [policy {:roots #{(ed/public-key seed)} :revoked #{} :fresh-until 150 :bindings {owner fp}}
            sealing {:owner owner :resource resource :now! (fn [] 100) :policy! (fn [] policy)}
            saved (atom {}) writes (atom 0) current (atom true) admitted (atom nil) fingerprints (atom {owner fp})
            client {:put-block! (fn [cid bytes auth] (check (= auth "Biscuit synthetic"))
                                  (swap! writes inc) (swap! saved assoc cid (clone bytes)) (js/Promise.resolve cid))
                    :get-block (fn [cid] (js/Promise.resolve (some-> (get @saved cid) clone)))}
            host {:owner owner :resource resource :delivery-current! #(deref current) :delivery-current-async! (fn [] (js/Promise.resolve @current))
                  :delivery-fingerprints! #(deref fingerprints)
                  :admit-cid! (fn [cid basis] (and (= @admitted cid) (= expected basis)))
                  :authorization! (fn [_] (js/Promise.resolve "Biscuit synthetic"))
                  :admit! (fn [basis _] (= expected basis))}]
        (-> (drive/seal-file! sealing "file-1" file [recipient])
            (.then (fn [sealed]
              (let [{:keys [cid bytes blocks]} (blocks/pack sealed)]
                (reset! admitted cid)
                (check (> (count blocks) 1))
                (check (not (.includes (js/Buffer.from bytes) (.-name file))))
                (-> (denied #(drive/publish! host client sealed (assoc expected :id "other")))
                    (.then (fn [_] (denied #(drive/publish! host client
                                   (assoc-in sealed [:envelope :envelope/recipients 0 :recipient/pub] (seal/b64url (js/Uint8Array. 32))) expected))))
                    (.then (fn [_] (check (zero? @writes)) (drive/publish! host client sealed expected)))
                    (.then (fn [published] (check (= cid published))
                      (drive/receive! host client cid expected owner identity)))
                    (.then (fn [{:keys [metadata bytes]}]
                      (check (= (.-name file) (:name metadata))) (check (= (.-length data) (.-length bytes)))
                      (check (and (= 42 (aget bytes 0)) (= 42 (aget bytes (dec (.-length bytes)))))) (.fill bytes 0)
                      (denied #(drive/receive! host client cid (assoc expected :id "other") owner identity))))
                    (.then (fn [_] (let [before @writes
                                           revoking (assoc host :authorization! (fn [_] (reset! current false) (js/Promise.resolve "Biscuit synthetic")))]
                                      (-> (denied #(drive/publish! revoking client sealed expected))
                                          (.then (fn [_] (check (= before @writes)) (reset! current true)))))))
                    (.then (fn [_] (let [before @writes
                                           rotating (assoc host :authorization! (fn [_] (swap! fingerprints assoc owner "kv1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") (js/Promise.resolve "Biscuit synthetic")))]
                                      (-> (denied #(drive/publish! rotating client sealed expected))
                                          (.then (fn [_] (check (= before @writes)) (reset! fingerprints {owner fp})))))))
                    (.then (fn [_] (reset! current false)
                      (denied #(drive/receive! host client cid expected owner identity))))
                    (.then (fn [_] (reset! current true)
                      (let [calls (atom 0) changing (assoc host :admit! (fn [_ _] (= 1 (swap! calls inc))))]
                        (denied #(drive/receive! changing client cid expected owner identity)))))
                    (.then (fn [_] (let [part (:cid (first blocks)) bytes (clone (get @saved part))]
                                      (aset bytes 0 (bit-xor 1 (aget bytes 0))) (swap! saved assoc part bytes)
                                      (denied #(drive/receive! host client cid expected owner identity)))))
                    (.then (fn [_] (drive/seal-file! sealing "file-1" empty-file [recipient])))
                    (.then (fn [empty]
                      (let [empty-cid (:cid (blocks/pack empty))]
                        (reset! admitted empty-cid)
                        (-> (drive/publish! host client empty expected)
                            (.then #(drive/receive! host client % expected owner identity))
                            (.then (fn [opened] (check (= 0 (.-length (:bytes opened)))) (.fill (:bytes opened) 0)))))))))))))))
    (.then (fn [_] (println (js/JSON.stringify #js {:passed true :scope "synthetic Drive encrypted block delivery"}))))
    (.catch (fn [error] (js/console.error error) (set! (.-exitCode js/process) 1))))
