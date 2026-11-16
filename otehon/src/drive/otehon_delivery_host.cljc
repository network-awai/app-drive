(ns drive.otehon-delivery-host
  "Trusted local host for encrypted file delivery."
  (:require [awai.otehon-delivery-host :as delivery]
            [awai.browser-block-authorization :as authorization]
            [awai.kotobase-block-adapter :as blocks]
            [drive.private-file :as file]))

(defn bind!
  "Bind local Passkey and dango checks. The caller authenticates the policy,
   signed basis, recipient grants and complete encrypted-frame byte count."
  [host db credential-id holder]
  (delivery/bind! host db credential-id holder file/max-frame))

(defn client!
  "Construct the ciphertext client with the host's broker and abort signal.
   Loopback HTTP is for explicit tests; production requires false and the
   reviewed canonical operation broker."
  [origin broker get-selector put-selector signal allow-loopback-for-tests?]
  (blocks/client origin broker get-selector put-selector signal allow-loopback-for-tests?))

(defn approved-delivery!
  "Construct the trusted local host, per-block approval and peer client using
   the same selected peer origin, broker and lifetime signal."
  [host db credential-id holder {:keys [policy! now! interact! identity-origin tenant-id audience
                                         broker start-selector redeem-selector get-selector put-selector
                                         signal allow-loopback-for-tests?]}]
  (let [bound (bind! host db credential-id holder)
        authorize! (authorization/authorizer
                     {:current! (:delivery-current! bound) :now! now! :interact! interact!}
                     policy! identity-origin tenant-id audience broker start-selector redeem-selector signal
                     allow-loopback-for-tests?)]
    {:host (assoc bound :authorization! authorize!)
     :client (client! audience broker get-selector put-selector signal allow-loopback-for-tests?)}))
