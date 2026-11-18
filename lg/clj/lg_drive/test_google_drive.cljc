(ns lg-drive.test-google-drive
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [lg-drive.handlers :as handlers]
            [lg-drive.server :as server]
            [lg-drive.store :as store]))

(def endpoint "/drive/v3/files")
(defn- get-metadata [st path query]
  (server/route st {:method :get :path path :query query
                    :expected-api-key "fixture" :x-api-key "fixture"}))

(deftest drive-v3-metadata-contract
  (let [st (store/fake-store)
        folder-id (get (handlers/files-create st {"name" "Pictures" "isFolder" true}) "fileId")
        file-id (get (handlers/files-create st {"name" "IMG-a.jpg" "mimeType" "image/jpeg"
                                               "sizeBytes" 9134567 "parentId" folder-id}) "fileId")
        discarded (get (handlers/files-create st {"name" "IMG-trash.jpg" "parentId" folder-id}) "fileId")
        _ (handlers/files-update st {"fileId" discarded "trashed" true})
        _ (handlers/files-create st {"name" "IMG-b.jpg" "parentId" folder-id})
        query {"q" (str "'" folder-id "' in parents and trashed = false and name contains 'IMG-'")
               "fields" "nextPageToken,files(id,name,parents)" "pageSize" "1" "orderBy" "name"}
        page (:json (get-metadata st endpoint query))
        token (get page "nextPageToken")
        next-page (:json (get-metadata st endpoint (assoc query "pageToken" token)))]
    (is (= [{"id" file-id "name" "IMG-a.jpg" "parents" [folder-id]}] (get page "files")))
    (is (string? token))
    (is (= ["IMG-b.jpg"] (mapv #(get % "name") (get next-page "files"))))
    (is (not (contains? next-page "nextPageToken")))
    (is (= 400 (:status (get-metadata st endpoint (assoc query "pageToken" token "orderBy" "name desc")))))
    (is (= {"id" file-id "size" "9134567" "mimeType" "image/jpeg"}
           (:json (get-metadata st (str endpoint "/" file-id) {"fields" "id,size,mimeType"}))))
    (is (= "application/vnd.google-apps.folder"
           (get-in (get-metadata st (str endpoint "/" folder-id) {}) [:json "mimeType"])))
    (is (= 404 (get-in (get-metadata st (str endpoint "/missing") {}) [:json "error" "code"])))
    (is (= 400 (:status (get-metadata st endpoint {"q" "fullText contains 'x'"}))))
    (is (= 400 (:status (get-metadata st endpoint {"fields" "files(owners)"}))))
    (is (= 400 (:status (get-metadata st (str endpoint "/" file-id) {"alt" "media"}))))))

(deftest drive-v3-host-boundary
  (let [st (store/fake-store)
        request {:request-method :get :uri endpoint :query-string "fields=files%28id%29"}
        open-call ((server/->ring-handler st nil) request)
        authenticated #((server/->ring-handler st "fixture") (assoc % :headers {"x-api-key" "fixture"}))]
    (is (= 401 (:status open-call)))
    (is (= "authError" (get-in (json/parse-string (:body open-call)) ["error" "errors" 0 "reason"])))
    (is (= 401 (:status ((server/->ring-handler st "fixture") request))))
    (is (= {"files" []} (json/parse-string (:body (authenticated request)))))
    (let [write (authenticated (assoc request :request-method :post))]
      (is (= 405 (:status write)))
      (is (= "GET" (get-in write [:headers "Allow"]))))))
