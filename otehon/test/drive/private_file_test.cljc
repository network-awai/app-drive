(ns drive.private-file-test
  (:require [drive.private-file :as drive] [awai.browser-chunk-envelope :as chunks]
            [dango.chain :as dango] [ed25519.sign :as ed] [envelope.keystore :as keys]
            [envelope.seal-sync :as crypto]))
(defn check [v] (when-not v (throw (js/Error. "private file test failed"))))
(defn denied [f] (-> (js/Promise.resolve nil) (.then f)
                     (.then (fn [_] (throw (js/Error. "unexpected file approval"))) (fn [_] true))))
(def seed (vec (repeat 32 9)))
(def owner "did:key:synthetic-owner")
(def resource "kotoba://storage/opaque-synthetic")
(def keypair (crypto/generate-hybrid-recipient))
(def grant (dango/present (dango/root {:issuer-seed seed :next-seed (vec (repeat 32 10))
             :grant {:caps #{[:data/read resource]} :holder owner :before 200} :caveats []})))
(def recipient {:holder owner :presentation grant :pub (:pub keypair) :pq-pub (:pq-pub keypair)})
(def data (js/Uint8Array. (+ chunks/chunk-bytes 3)))
(.fill data 42)
(def file (js/File. #js [data] "秘密の報告.txt" #js {:type "text/plain" :lastModified 12345}))
(def empty-file (js/File. #js [] "empty.txt" #js {:lastModified -12345}))
(-> (keys/fingerprint keypair)
    (.then (fn [fp]
      (let [p {:roots #{(ed/public-key seed)} :revoked #{} :fresh-until 150 :bindings {owner fp}}
            host {:owner owner :resource resource :now! (fn [] 100) :policy! (fn [] p)}
            expected {:resource resource :recipients #{owner} :owner owner :id "opaque-file-id"}
            admit {:admit! (fn [e _] (= expected e))} identity (select-keys keypair [:priv :pq-priv])]
        (-> (drive/seal-file! host "opaque-file-id" file [recipient])
            (.then (fn [sealed]
              (check (= 2 (count (:chunks sealed))))
              (check (not (.includes (pr-str (:envelope sealed)) (.-name file))))
              (check (not (.includes (js/Buffer.concat (clj->js (:chunks sealed))) (js/Buffer.from (.-name file)))))
              (-> (drive/open-file! admit sealed expected owner identity)
                  (.then (fn [{:keys [metadata bytes]}]
                    (check (= (.-name file) (:name metadata)))
                    (check (= (.-size file) (:size metadata) (.-length bytes)))
                    (check (and (= 42 (aget bytes 0)) (= 42 (aget bytes (dec (.-length bytes))))))
                    (.fill bytes 0)
                    (js/Promise.all
                      #js [(-> (drive/seal-file! host "opaque-file-id" empty-file [recipient])
                               (.then (fn [o] (drive/open-file! admit o expected owner identity)))
                               (.then (fn [x] (check (= 0 (.-length (:bytes x))))
                                                (check (= -12345 (get-in x [:metadata :modified]))))))
                           (denied #(drive/seal-file! host "opaque-file-id" file []))
                           (denied #(drive/seal-file! (assoc host :policy! (fn [] (assoc p :fresh-until 100))) "opaque-file-id" file [recipient]))
                           (denied #(drive/open-file! {:admit! (fn [_ _] true)} sealed (assoc expected :id "wrong") owner identity))
                           (denied #(drive/open-file! {:admit! (fn [_ _] true)} sealed (assoc expected :owner "wrong") owner identity))
                           (denied #(let [v (js/Uint8Array.from (second (:chunks sealed)))]
                                      (aset v 0 (bit-xor 1 (aget v 0)))
                                      (drive/open-file! admit (assoc sealed :chunks [(first (:chunks sealed)) v]) expected owner identity)))
                           (denied #(drive/open-file! {:admit! (fn [_ _] false)} sealed expected owner identity))]))))))))))
    (.then (fn [_] (println (js/JSON.stringify #js {:passed true :scope "synthetic private file metadata and chunks"}))))
    (.catch (fn [e] (js/console.error e) (set! (.-exitCode js/process) 1))))
