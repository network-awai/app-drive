(ns drive.async-delivery-test
  (:require [drive.private-file :as drive] [awai.browser-chunk-blocks :as blocks]))
(defn check [v] (when-not v (throw (js/Error. "Drive async delivery test failed"))))
(let [calls (atom 0) holder "did:key:holder" fp (str "kv1:" (apply str (repeat 43 "a")))
      host {:delivery-current! (fn [] true) :delivery-fingerprints! (fn [] {holder fp})
            :delivery-current-async! (fn [] (js/Promise.resolve false))
            :admit-cid! (fn [& _] true) :admit! (fn [& _] true)}
      client {:get-block (fn [_] (swap! calls inc) (js/Promise.resolve #js []))
              :put-block! (fn [& _] (swap! calls inc) (js/Promise.resolve "cid"))}
      expected {:resource "r" :recipients #{holder} :owner holder :id "i"}
      result (with-redefs [blocks/receive! (fn [_ guarded _ _] ((:get-block guarded) "cid"))]
               (drive/receive! host client "cid" expected holder {}))]
  (-> result
      (.then (fn [_] (throw (js/Error. "stale authority accepted")))
             (fn [_] (check (zero? @calls))
               (println (js/JSON.stringify #js {:passed true :scope "Drive async authority before delivery"}))))
      (.catch (fn [e] (js/console.error e) (set! (.-exitCode js/process) 1)))))
