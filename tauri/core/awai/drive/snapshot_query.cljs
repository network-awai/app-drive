(ns awai.drive.snapshot-query
  (:require [awai.drive.encrypted :as encrypted]
            [clojure.string :as str]
            [datalog.core :as datalog]
            [datalog.index :as index]))

(defn- extension [path]
  (let [name (last (str/split path #"/"))
        dot (str/last-index-of name ".")]
    (if (and dot (pos? dot)) (str/lower-case (subs name (inc dot))) "")))

(defn project [snapshot]
  (let [basis (.-basis snapshot) rows (.-rows snapshot)]
  (when-not (and (string? basis) (seq basis)) (encrypted/refuse))
  (let [quads (mapcat (fn [row]
                        (let [path (.-path row)]
                          [{:s path :p :drive/path :o path}
                           {:s path :p :drive/extension :o (extension path)}
                           {:s path :p :drive/bytes :o (reduce + (map #(.-length %) (array-seq (.-chunks row))))}]))
                      (remove #(.-directory %) (array-seq rows)))
        db (index/assert-quads (index/empty-db) quads (constantly false))]
    (fn [expected ext]
      (when-not (and (= expected basis) (string? ext) (<= (count ext) 48)) (encrypted/refuse))
      (clj->js
        (sort
          (datalog/q db '{:find [?file ?bytes] :in [?ext]
                         :where [[?id :drive/extension ?ext]
                                 [?id :drive/path ?file]
                                 [?id :drive/bytes ?bytes]]}
                     (constantly true) [(str/lower-case ext)])))))))
