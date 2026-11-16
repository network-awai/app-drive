(ns drive.passkey-seal-test
  (:require ["node:crypto" :as crypto] ["node:fs" :as fs] ["node:path" :as path] ["node:http" :as http]
            [drive.private-file :as drive] [drive.otehon-delivery-host :as delivery]
            [awai.browser-chunk-blocks :as blocks]
            [awai.browser-block-token :as block-token] [biscuit.wire :as wire]
            ["@noble/curves/ed25519.js" :refer [ed25519]] ["@noble/hashes/sha2.js" :refer [sha256]]
            [awai.passkey-store :as store] [awai.recipient-proof-store :as persisted] [awai.otehon-passkey :as otehon]
            [awai.dango-local-revocations :as local-revocations]
            [dango.chain :as dango] [ed25519.sign :as ed] [envelope.keystore :as keys] [envelope.seal :as seal]
            [webauthn.adapters.edge :as edge] [webauthn.virtual-authenticator :as virtual]))
(defn check [x] (when-not x (throw (js/Error. "browser recipient envelope test failed"))))
(defn denied [f] (-> (js/Promise.resolve nil) (.then f)
                     (.then (fn [_] (throw (js/Error. "unexpected acceptance"))) (fn [_] true))))
(defn ab [s] (.-buffer (js/Uint8Array.from (edge/b64url->bytes s))))
(def dir (.resolve path "../../.agent-output" (str "browser-recipient-envelope-" (.randomUUID crypto))))
(.mkdirSync fs dir #js {:mode 448})
(def db (store/open-owned! (.join path dir "credentials.sqlite")))
(persisted/initialize! db)
(local-revocations/initialize-storage! db)
(check (true? (local-revocations/initialize-scope! db "drive-test")))
(def resource "kotoba://storage/browser-recipient")
(def seed (vec (repeat 32 3)))
(def auth-now 1800000000000) (def seconds (quot auth-now 1000))
(def auth-seed (js/Uint8Array.from (range 32))) (def next-seed (vec (range 32 64)))
(def root-pub (vec (.getPublicKey ed25519 auth-seed))) (def tenant "did:key:zTenant")
(def req-id (apply str (repeat 43 "a"))) (def req-secret (apply str (repeat 43 "b")))
(defn json-bytes [x] (.encode (js/TextEncoder.) (js/JSON.stringify (clj->js x))))
(def snapshot {:version 1 :tenant tenant :revision 0 :issuedAt seconds :expiresAt (+ seconds 30) :signatures []
  :signature (.toString (js/Buffer.from (.sign ed25519 (json-bytes ["awai/block-biscuit-revocations/v1" tenant 0 seconds (+ seconds 30) []]) auth-seed)) "base64url")})
(def snapshot-digest (vec (sha256 (json-bytes [tenant 0 []]))))
(defn peer-admits? [cid audience token]
  ((block-token/admitter (fn [] {:tenant-id "t_abcdefghijkl" :tenant-did tenant :holder "did:key:zHolder" :role "editor"
                                 :roots #{root-pub} :revoked #{} :fresh-until (+ auth-now 30000)
                                 :now! (fn [] auth-now) :current! (fn [_] true)}))
   {:tenant-id "t_abcdefghijkl" :cid cid :audience audience}
   {:blockCid cid :blockAudience audience :tokenType "Biscuit" :token token :authorization (str "Biscuit " token)}))
(defn token! [cid]
  (.toString (js/Buffer.from (clj->js (wire/encode-authority-token
    {:root-key-id 1 :root-private-key (vec auth-seed) :next-secret next-seed
     :next-public-key (vec (.getPublicKey ed25519 (js/Uint8Array.from (clj->js next-seed))))
     :facts [['scope (str "kotoba://graph/ipld/" cid)] ['scope "kotoba://tenant/did:key:zTenant"]
             ['scope "kotoba://can/block:write"] ['block_audience "https://peer.example"]
             ['before (.toISOString (js/Date. (+ auth-now 60000)))] ['holder "did:key:zHolder"]
             ['role "editor"] ['tenant_id "t_abcdefghijkl"] ['principal_type "human"]]
     :sign-fn (fn [key msg] (vec (.sign ed25519 (js/Uint8Array.from (clj->js msg)) (js/Uint8Array.from (clj->js key)))))}))) "base64url"))
(def requested-cid (atom nil)) (def issued-token (atom nil)) (def choice (atom true)) (def displays (atom [])) (def bad-snapshot (atom false))
(def stored (atom {})) (def writes (atom 0)) (def operations (atom []))
(def server (http/createServer
  (fn [req res]
    (.setHeader res "connection" "close")
    (if (and (= "POST" (.-method req)) (#{"/v1/block-approval/start" "/v1/block-approval/redeem"} (.-url req)))
      (let [parts (atom [])]
        (.on req "data" #(swap! parts conj %))
        (.on req "end" (fn []
          (let [body (js/JSON.parse (.toString (js/Buffer.concat (clj->js @parts))))
                start? (= "/v1/block-approval/start" (.-url req))]
            (if start? (do (check (= "t_abcdefghijkl" (.-tenantId body))) (reset! requested-cid (.-blockCid body)))
              (do (check (= req-id (.-id body))) (check (= req-secret (.-secret body))) (reset! issued-token (token! @requested-cid))))
            (.setHeader res "content-type" "application/json") (.writeHead res 201)
            (.end res (js/JSON.stringify (if start?
              #js {:id req-id :secret req-secret :expires-at (+ auth-now 300000)}
              #js {:blockCid @requested-cid :blockAudience "https://peer.example" :tokenType "Biscuit"
                   :token @issued-token :authorization (str "Biscuit " @issued-token)
                   :revocationSnapshot (clj->js (if @bad-snapshot (assoc snapshot :signature (apply str (repeat 86 "A"))) snapshot))})))))))
      (if (and (= "PUT" (.-method req)) (re-matches #"/private-ipld/b[a-z2-7]+" (.-url req)))
      (let [parts (atom [])]
        (.on req "data" #(swap! parts conj %))
        (.on req "end" (fn []
          (check (= (str "Biscuit " @issued-token) (aget (.-headers req) "authorization")))
          (check (peer-admits? (subs (.-url req) (count "/private-ipld/")) "https://peer.example" @issued-token))
          (check (false? (peer-admits? (subs (.-url req) (count "/private-ipld/")) "https://other.example" @issued-token)))
          (swap! stored assoc (.-url req) (js/Buffer.concat (clj->js @parts))) (swap! writes inc)
          (.writeHead res 201) (.end res))))
      (if-let [bytes (and (= "GET" (.-method req)) (get @stored (.-url req)))] (.end res bytes)
        (do (.writeHead res 404) (.end res))))))))
(def peer (js/Promise. (fn [resolve reject] (.once server "error" reject)
  (.listen server 0 "127.0.0.1" #(resolve (str "http://127.0.0.1:" (.-port (.address server))))))))
(-> (js/Promise.all #js [(virtual/create! "awai.network") (keys/generate-identity) (keys/generate-identity) peer])
    (.then (fn [[auth identity sender-key origin]]
      (let [pub (edge/bytes->b64 (:public-key auth)) holder (otehon/account-did pub)
            _ (store/enroll-trusted! db {:credential-id (:credential-id auth) :public-key-b64 pub
                                         :holder holder :fingerprint (:recipient-key identity) :sign-count 0})
            clock (fn [] 100) issued (persisted/issue! db (:credential-id auth) clock)
            recipient-host (persisted/host db (:credential-id auth) (:nonce issued) "awai.network" "https://awai.network"
                                           clock (fn [h] (when (= h holder) (:recipient-key identity))))
            calls (atom 0)
            request! (fn [options]
              (swap! calls inc)
              (check (= "required" (.. options -publicKey -userVerification)))
              (check (= 1 (.-length (.. options -publicKey -allowCredentials))))
              (let [challenge (seal/b64url (.. options -publicKey -challenge))]
                (-> ((:assert! auth) {:origin "https://awai.network" :challenge challenge :sign-count 1})
                    (.then (fn [assertion]
                      #js {:type "public-key" :id (:credential-id auth) :rawId (ab (:credential-id auth))
                           :response #js {:clientDataJSON (ab (:client-data-json-b64url assertion))
                                          :authenticatorData (ab (:authenticator-data-b64url assertion))
                                          :signature (ab (:signature-b64url assertion))}})))))
            presentation (dango/present (dango/root {:issuer-seed seed :next-seed (vec (repeat 32 4))
                           :grant {:caps #{[:data/read resource]} :holder holder :before 200} :caveats []}))
            sender "did:key:sender"
            sender-presentation (dango/present (dango/root {:issuer-seed seed :next-seed (vec (repeat 32 5))
                                 :grant {:caps #{[:data/read resource]} :holder sender :before 200} :caveats []}))
            others [{:holder sender :presentation sender-presentation :pub (:pub sender-key) :pq-pub (:pq-pub sender-key)}]
            reads (atom 0)
            file (js/File. #js [(js/Uint8Array. 4194304) (.encode (js/TextEncoder.) "private")] "private.txt")
            original-read (.-arrayBuffer file)
            _ (aset file "arrayBuffer" (fn [] (check (= 1 @calls)) (swap! reads inc) (.call original-read file)))
            revoked (atom #{}) allowed (atom nil) before-denial (atom nil)
            expected {:resource resource :recipients #{holder sender} :owner sender :id "file-1"}
            metadata {:schema "awai.drive/private-file-v1" :id "file-1" :owner sender :name (.-name file)
                      :mime (.-type file) :modified (.-lastModified file) :size (.-size file)}
            byte-count (+ 4 (.-size file) (.-length (.encode (js/TextEncoder.) (js/JSON.stringify (clj->js metadata)))))
            controller (js/AbortController.)
            broker #js {:invoke (fn [selector request options]
                      (swap! operations conj [(.-operation selector) (.. request -init -method)])
                      (let [requested (.-input request) url (js/URL. requested)
                            peer? (= "https://peer.example" (.-origin url))
                            actual (if peer? (str origin (.-pathname url)) requested)]
                        (-> (js/fetch actual (js/Object.assign #js {:redirect "error" :signal (.-signal options)} (.-init request)))
                            (.then (fn [response] (when peer? (js/Object.defineProperty response "url" #js {:value requested})) response)))))}
            block-policy {:tenant-id "t_abcdefghijkl" :tenant-did tenant :holder "did:key:zHolder" :role "editor"
                          :roots #{root-pub} :now! (fn [] auth-now) :current! (fn [_] true)
                          :accept! (fn [t r d v] (js/Promise.resolve (and (= t tenant) (= r 0) (= d snapshot-digest) (empty? v))))
                          :watermark-current! (fn [t r d] (js/Promise.resolve (and (= t tenant) (= r 0) (= d snapshot-digest))))}
            _ (check (try (delivery/bind! {:policy! (fn [] {}) :local-revocation-scope "missing"}
                                          db (:credential-id auth) holder) false
                          (catch :default _ true)))
            pair (delivery/approved-delivery!
                   {:recipient-host recipient-host :request! request! :resource resource :now! clock :owner sender
                    :policy! (fn [] {:roots #{(ed/public-key seed)} :revoked @revoked :fresh-until 150
                                     :bindings {holder (:recipient-key identity) sender (:recipient-key sender-key)}})
                    :delivery-grants! (fn [] {:byte-count byte-count :recipients
                      [{:holder holder :presentation presentation :fingerprint (:recipient-key identity)}
                       {:holder sender :presentation sender-presentation :fingerprint (:recipient-key sender-key)}]})
                    :local-revocation-scope "drive-test" :delivery-current! (fn [] true)
                    :admit-cid! (fn [cid basis] (and (= cid @allowed) (= expected basis)))
                    :admit! (fn [basis _] (= expected basis))}
                   db (:credential-id auth) holder
                   {:policy! (fn [] block-policy) :now! (fn [] auth-now)
                    :interact! (fn [display] (swap! displays conj display) (js/Promise.resolve @choice))
                    :identity-origin origin :tenant-id "t_abcdefghijkl" :audience "https://peer.example"
                    :broker broker :start-selector #js {:capability "identity" :service "authn" :operation "start"}
                    :redeem-selector #js {:capability "identity" :service "authn" :operation "redeem"}
                    :get-selector #js {:capability "storage" :service "peer" :operation "get"}
                    :put-selector #js {:capability "storage" :service "peer" :operation "put"}
                    :signal (.-signal controller) :allow-loopback-for-tests? true})
            host (:host pair) client (:client pair)
            nonce (edge/b64url->bytes (:nonce issued))]
        (-> (denied #(drive/prepare-passkey-seal! (assoc host :owner "wrong") "file-1" file nonce (:pub identity) (:pq-pub identity) presentation others))
            (.then (fn [_] (check (zero? @reads)) (check (zero? @calls))
                     (drive/prepare-passkey-seal! host "file-1" file nonce (:pub identity) (:pq-pub identity) presentation others)))
            (.then (fn [prepared]
              (check (= [holder sender] (:recipients prepared))) (check (zero? @reads))
              (let [pending ((:confirm! prepared))]
                (check (= 1 @calls))
                (-> pending
                    (.then (fn [sealed] (reset! allowed (:cid (blocks/pack sealed))) (reset! choice false)
                      (-> (denied #(drive/publish! host client sealed expected))
                          (.then (fn [_] (check (zero? @writes)) (reset! choice true) (reset! bad-snapshot true)
                                   (denied #(drive/publish! host client sealed expected))))
                          (.then (fn [_] (check (zero? @writes)) (reset! bad-snapshot false)
                                   (drive/publish! host client sealed expected))))))
                    (.then (fn [cid] (check (> @writes 1))
                      (check (every? #(not (.includes % (js/Buffer.from "private"))) (vals @stored)))
                      (drive/receive! host client cid expected holder identity)))
                    (.then (fn [result] (check (= "private.txt" (get-in result [:metadata :name])))
                      (check (= "private" (.decode (js/TextDecoder.) (.slice (:bytes result) 4194304))))
                      (.fill (:bytes result) 0) (check (= 1 @reads))
                      (check (and (some #(= ["put" "PUT"] %) @operations) (some #(= ["get" "GET"] %) @operations)))
                      (check (= (+ 2 @writes) (count @displays)))
                      (check (= (+ 2 @writes) (count (filter #(= ["start" "POST"] %) @operations))))
                      (check (= (+ 1 @writes) (count (filter #(= ["redeem" "POST"] %) @operations))))
                      (reset! before-denial (count @operations))
                      (let [decision (dango/verify presentation {:roots #{(ed/public-key seed)} :revoked #{}
                           :request {:req/kind :data/read :req/resource resource :req/holder holder :req/now 100 :req/bytes byte-count}})]
                        (reset! revoked #{(first (:dango/cids decision))}))
                      (denied #(drive/receive! host client @allowed expected holder identity))))
                    (.then (fn [_] (reset! revoked #{})
                      (let [decision (dango/verify presentation {:roots #{(ed/public-key seed)} :revoked #{}
                            :request {:req/kind :data/read :req/resource resource :req/holder holder :req/now 100 :req/bytes byte-count}})]
                        (local-revocations/revoke! db "drive-test" (first (:dango/cids decision))))
                      (denied #(drive/receive! host client @allowed expected holder identity))))
                    (.then (fn [_] (check (store/revoke! db (:credential-id auth)))
                      (denied #(drive/receive! host client @allowed expected holder identity))))
                    (.then (fn [_] (check (= @before-denial (count @operations))) (.abort controller)
                      (denied #((:confirm! prepared)))))))))))))
    (.then (fn [_] (.close server) (.close db) (println (js/JSON.stringify #js {:passed true :scope "Drive virtual Passkey and loopback Kotobase delivery"}))))
    (.catch (fn [e] (.close server) (.close db) (js/console.error e) (set! (.-exitCode js/process) 1))))
