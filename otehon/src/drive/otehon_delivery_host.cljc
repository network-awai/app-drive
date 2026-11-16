(ns drive.otehon-delivery-host
  "Trusted local host for encrypted file delivery."
  (:require [awai.otehon-delivery-host :as delivery]
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
