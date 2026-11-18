(ns awai.drive.wnfs-bridge
  (:require [awai.drive.encrypted :as encrypted]
            [awai.drive.wnfs :as wnfs]
            [awai.drive.snapshot-query :as query]))

(aset js/window "AwaiDriveEncrypted"
      #js {"newRecord" encrypted/new-record "sealSnapshot" encrypted/seal-snapshot
           "openSnapshot" encrypted/open-snapshot "sha256" encrypted/sha})
(aset js/window "AwaiDriveWnfs"
      #js {"seal" wnfs/seal "open" wnfs/open "catalog" wnfs/catalog "openSnapshot" wnfs/open-snapshot "project" query/project "snapshotLimit" wnfs/snapshot-limit})
