(ns drive.otehon-delivery-host
  "Trusted local host for encrypted file delivery."
  (:require [awai.otehon-delivery-host :as delivery]
            [drive.private-file :as file]))

(defn bind!
  "Bind local Passkey and dango checks. The caller authenticates the policy,
   signed basis, recipient grants and complete encrypted-frame byte count."
  [host db credential-id holder]
  (delivery/bind! host db credential-id holder file/max-frame))
