(ns lg-drive.google-drive
  "Drive v3 metadata routes over the existing, host-scoped DriveStore."
  (:require [clojure.string :as str]
            [lg-drive.handlers :as handlers]
            [lg-drive.mapping :as mapping]
            [lg-drive.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Instant]))

(def files-path "/drive/v3/files")
(def folder-type "application/vnd.google-apps.folder")
(def default-fields ["kind" "id" "name" "mimeType" "resourceKey"])
(def file-fields (into (set default-fields)
                       ["size" "parents" "createdTime" "modifiedTime" "trashed"
                        "starred" "sha256Checksum" "webViewLink" "version"]))
(def list-fields #{"kind" "files" "nextPageToken" "incompleteSearch"})

(defn- invalid [message]
  (throw (ex-info message {:status 400 :reason "invalidParameter"})))

(defn- error-response [status reason message]
  {:status status
   :json {"error" {"code" status "message" message
                   "errors" [{"domain" "global" "reason" reason "message" message}]}}})

(defn- select-fields [known value]
  (let [fields (mapv str/trim (str/split value #"," -1))]
    (when-not (every? known fields) (invalid "Unsupported field selection"))
    fields))

(defn- list-selection [value]
  (if-not value
    [["kind" "files" "nextPageToken"] default-fields]
    (let [groups (str/split value #",\s*(?![^()]*\))" -1)
          nested (keep #(second (re-matches #"\s*files\(([^()]+)\)\s*" %)) groups)
          outer (str/join "," (map #(if (re-matches #"\s*files\([^()]+\)\s*" %) "files" %) groups))]
      (when (> (count nested) 1) (invalid "Repeated files field selection"))
      [(select-fields list-fields outer)
       (if-let [fields (first nested)] (select-fields file-fields fields) file-fields)])))

(defn- metadata [file]
  (let [time #(some-> % long Instant/ofEpochMilli str)
        values {"kind" "drive#file"
                "id" (get file "fileId")
                "name" (get file "name")
                "mimeType" (if (get file "isFolder") folder-type
                               (get file "mimeType" "application/octet-stream"))
                "size" (when-not (get file "isFolder") (some-> (get file "sizeBytes") str))
                "parents" [(get file "parentId" "root")]
                "createdTime" (time (get file "createdAtMs"))
                "modifiedTime" (time (get file "updatedAtMs"))
                "trashed" (boolean (get file "trashed"))
                "starred" (boolean (get file "starred"))
                "sha256Checksum" (get file "sha256")
                "webViewLink" (get file "webUrl")
                "version" (some-> (get file "version") str)}]
    (into {} (remove (comp nil? val)) values)))

(def ^:private query-term
  #"\G(?:'([A-Za-z0-9_-]+)'\s+in\s+parents|(trashed|starred)\s*=\s*(true|false)|(name|mimeType)\b\s*(contains|!=|=)\s*'((?:[^'\\]++|\\['\\])++)')(?:\s+and\s+(?=\S)|$)")

(defn- text-filter [field operator literal]
  (let [value (str/replace literal #"\\(['\\])" second)
        matches (case operator
                  "contains" (if (= field "name")
                               #(str/starts-with? % value)
                               #(str/includes? % value))
                  "=" #(= % value)
                  "!=" #(not= % value))]
    (fn [file] (matches (get file field "")))))

(defn- query-filter [value]
  (if (nil? value)
    (constantly true)
    (let [query (str/trim value)
          terms (re-seq query-term query)]
      (when-not (and (seq terms) (= (count query) (reduce + (map (comp count first) terms))))
        (invalid "Unsupported Drive query term"))
      (let [filters (mapv (fn [[_ id field bool text-field operator literal]]
                            (cond
                              id (fn [file] (some #{id} (get file "parents")))
                              field (fn [file] (= (= bool "true") (get file field)))
                              :else (text-filter text-field operator literal)))
                          terms)]
        (fn [file] (every? #(% file) filters))))))

(defn- sort-files [files value]
  (let [[_ field direction] (re-matches #"(name|modifiedTime)(?:\s+(asc|desc))?" (or value "name"))
        _ (when-not field (invalid "Unsupported orderBy"))
        sort-value (fn [file]
                     (let [value (get file field)]
                       (if (= field "modifiedTime") (some-> value Instant/parse) value)))
        comparator (fn [left right]
                     (let [order (compare (sort-value left) (sort-value right))]
                       (if (zero? order) (compare (get left "id") (get right "id"))
                           (if (= direction "desc") (- order) order))))]
    (sort comparator files)))

(defn- query-id [query]
  (let [material (pr-str (select-keys query ["q" "orderBy" "pageSize"]))
        bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes material StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 255)) bytes))))

(defn- page-offset [query signature]
  (if-let [token (get query "pageToken")]
    (let [[_ offset fingerprint] (re-matches #"v1\.([0-9]{1,9})\.([a-f0-9]{64})" token)]
      (when-not (= signature fingerprint) (invalid "Invalid pageToken"))
      (parse-long offset))
    0))

(defn- assert-query [query allowed]
  (when-not (and (every? allowed (keys query))
                 (every? #(and (string? %) (<= (count %) 2048)) (vals query)))
    (invalid "Unsupported query parameter")))

(defn- files-list [st query]
  (assert-query query #{"fields" "q" "orderBy" "pageSize" "pageToken"})
  (let [[outer inner] (list-selection (get query "fields"))
        requested (parse-long (get query "pageSize" "100"))
        _ (when-not (and requested (pos? requested)) (invalid "Invalid pageSize"))
        size (min requested 1000)
        signature (query-id query)
        offset (page-offset query signature)
        matches (query-filter (get query "q"))
        files (->> (store/all-file-attrs st) (map (comp metadata mapping/attrs-to-file))
                   (filter matches))
        window (->> (sort-files files (get query "orderBy"))
                    (drop offset) (take (inc size)) vec)
        result (cond-> {"kind" "drive#fileList" "incompleteSearch" false
                        "files" (mapv #(select-keys % inner) (take size window))}
                 (> (count window) size)
                 (assoc "nextPageToken" (str "v1." (+ offset size) "." signature)))]
    {:status 200 :json (select-keys result outer)}))

(defn- files-get [st id query]
  (assert-query query #{"fields" "alt"})
  (when-not (= "json" (get query "alt" "json")) (invalid "Only metadata is available"))
  (let [fields (if-let [value (get query "fields")] (select-fields file-fields value) default-fields)
        result (handlers/files-get st {"fileId" id})]
    (if (get result "found")
      {:status 200 :json (select-keys (metadata (get result "file")) fields)}
      (error-response 404 "notFound" "File not found"))))

(defn route [st method path query authorized?]
  (cond
    (not authorized?) (error-response 401 "authError" "Drive service authorization required")
    (not= :get method) (assoc (error-response 405 "methodNotAllowed" "Only GET is supported")
                              :headers {"Allow" "GET"})
    :else
    (try
      (if (= files-path path)
        (files-list st query)
        (if-let [[_ id] (re-matches #"/drive/v3/files/([A-Za-z0-9_-]{1,160})" path)]
          (files-get st id query)
          (error-response 404 "notFound" "File not found")))
      (catch clojure.lang.ExceptionInfo error
        (if (= 400 (:status (ex-data error)))
          (error-response 400 (:reason (ex-data error)) (.getMessage error))
          (throw error))))))
