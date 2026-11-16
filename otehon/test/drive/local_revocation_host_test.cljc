(ns drive.local-revocation-host-test
  (:require ["node:crypto" :as crypto] ["node:fs" :as fs] ["node:path" :as path]
            [drive.otehon-delivery-host :as delivery] [awai.passkey-store :as store]
            [awai.dango-local-revocations :as local] [awai.otehon-passkey :as otehon]
            [dango.chain :as dango] [ed25519.sign :as ed] [envelope.keystore :as keys]
            [webauthn.adapters.edge :as edge] [webauthn.virtual-authenticator :as virtual]))
(defn check [v] (when-not v (throw (js/Error. "Drive local revocation failed"))))
(def dir (.resolve path "../../.agent-output" (str "drive-local-revocation-" (.randomUUID crypto))))
(.mkdirSync fs dir #js {:recursive true :mode 448})
(def db (store/open-owned! (.join path dir "credentials.sqlite")))
(local/initialize-storage! db)
(-> (js/Promise.all #js [(virtual/create! "awai.network") (keys/generate-identity)])
    (.then (fn [[auth identity]]
      (let [holder (otehon/account-did (edge/bytes->b64 (:public-key auth)))
            seed (vec (repeat 32 3)) root (ed/public-key seed) resource "kotoba://storage/drive-test"
            presentation (dango/present (dango/root {:issuer-seed seed :next-seed (vec (repeat 32 4))
                          :grant {:caps #{[:data/read resource]} :holder holder :before 200} :caveats []}))
            config {:resource resource :local-revocation-scope "drive-test" :now! (fn [] 100)
                    :policy! (fn [] {:roots #{root} :revoked #{} :fresh-until 150 :bindings {holder (:recipient-key identity)}})
                    :delivery-grants! (fn [] {:byte-count 8 :recipients [{:holder holder :presentation presentation :fingerprint (:recipient-key identity)}]})
                    :delivery-current! (fn [] true) :admit-cid! (fn [_ _] true)}]
        (store/enroll-trusted! db {:credential-id (:credential-id auth) :public-key-b64 (edge/bytes->b64 (:public-key auth))
                                   :holder holder :fingerprint (:recipient-key identity) :sign-count 0})
        (check (try (delivery/bind! config db (:credential-id auth) holder) false (catch :default _ true)))
        (check (true? (local/initialize-scope! db "drive-test")))
        (let [host (delivery/bind! config db (:credential-id auth) holder)
              decision (dango/verify presentation {:roots #{root} :revoked #{} :request
                        {:req/kind :data/read :req/resource resource :req/holder holder :req/now 100 :req/bytes 8}})]
          (check (true? ((:delivery-current! host))))
          (check (true? (:dango/allowed? decision)))
          (local/revoke! db "drive-test" (first (:dango/cids decision)))
          (check (false? ((:delivery-current! host))))))))
    (.then (fn [_] (.close db) (println (js/JSON.stringify #js {:passed true :scope "Drive local dango revocation host"}))))
    (.catch (fn [e] (.close db) (js/console.error e) (set! (.-exitCode js/process) 1))))
