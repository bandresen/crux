(ns crux.tx-test
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [crux.bus :as bus]
            [crux.codec :as c]
            [crux.db :as db]
            [crux.index :as idx]
            [crux.fixtures :as fix :refer [*api*]]
            [crux.tx :as tx]
            [crux.kv :as kv]
            [crux.api :as api]
            [crux.rdf :as rdf]
            [crux.query :as q]
            [crux.node :as n]
            [crux.io :as cio]
            [taoensso.nippy :as nippy]
            [crux.tx.event :as txe])
  (:import [java.util Date]
           [java.time Duration]))

(t/use-fixtures :each fix/with-standalone-topology fix/with-kv-dir fix/with-node fix/with-silent-test-check)

(def picasso-id :http://dbpedia.org/resource/Pablo_Picasso)
(def picasso-eid (c/new-id picasso-id))

(def picasso
  (-> "crux/Pablo_Picasso.ntriples"
      (rdf/ntriples)
      (rdf/->default-language)
      (rdf/->maps-by-id)
      (get picasso-id)))

;; TODO: This is a large, useful, test that exercises many parts, but
;; might be better split up.
(t/deftest test-can-index-tx-ops-acceptance-test
  (let [content-hash (c/new-id picasso)
        valid-time #inst "2018-05-21"
        {:crux.tx/keys [tx-time tx-id]}
        (fix/submit+await-tx [[:crux.tx/put picasso valid-time]])]

    (with-open [index-store (db/open-index-store (:indexer *api*))]
      (t/testing "can see entity at transact and valid time"
        (t/is (= (c/map->EntityTx {:eid picasso-eid
                                   :content-hash content-hash
                                   :vt valid-time
                                   :tt tx-time
                                   :tx-id tx-id})
                 (db/entity-as-of index-store :http://dbpedia.org/resource/Pablo_Picasso tx-time tx-time))))

      (t/testing "cannot see entity before valid or transact time"
        (t/is (nil? (db/entity-as-of index-store :http://dbpedia.org/resource/Pablo_Picasso #inst "2018-05-20" tx-time)))
        (t/is (nil? (db/entity-as-of index-store :http://dbpedia.org/resource/Pablo_Picasso tx-time #inst "2018-05-20"))))

      (t/testing "can see entity after valid or transact time"
        (t/is (some? (db/entity-as-of index-store :http://dbpedia.org/resource/Pablo_Picasso #inst "2018-05-22" tx-time)))
        (t/is (some? (db/entity-as-of index-store :http://dbpedia.org/resource/Pablo_Picasso tx-time tx-time))))

      (t/testing "can see entity history"
        (with-open [history (db/open-entity-history index-store :http://dbpedia.org/resource/Pablo_Picasso :desc {})]
          (t/is (= [(c/map->EntityTx {:eid picasso-eid
                                      :content-hash content-hash
                                      :vt valid-time
                                      :tt tx-time
                                      :tx-id tx-id})]
                   (iterator-seq history))))))

    (t/testing "add new version of entity in the past"
      (let [new-picasso (assoc picasso :foo :bar)
            new-content-hash (c/new-id new-picasso)
            new-valid-time #inst "2018-05-20"
            {new-tx-time :crux.tx/tx-time
             new-tx-id   :crux.tx/tx-id}
            (fix/submit+await-tx [[:crux.tx/put new-picasso new-valid-time]])]

        (with-open [index-store (db/open-index-store (:indexer *api*))]
          (t/is (= (c/map->EntityTx {:eid picasso-eid
                                     :content-hash new-content-hash
                                     :vt new-valid-time
                                     :tt new-tx-time
                                     :tx-id new-tx-id})
                   (db/entity-as-of index-store :http://dbpedia.org/resource/Pablo_Picasso new-valid-time new-tx-time)))

          (t/is (nil? (db/entity-as-of index-store :http://dbpedia.org/resource/Pablo_Picasso #inst "2018-05-20" #inst "2018-05-21"))))))

    (t/testing "add new version of entity in the future"
      (let [new-picasso (assoc picasso :baz :boz)
            new-content-hash (c/new-id new-picasso)
            new-valid-time #inst "2018-05-22"
            {new-tx-time :crux.tx/tx-time
             new-tx-id   :crux.tx/tx-id}
            (fix/submit+await-tx [[:crux.tx/put new-picasso new-valid-time]])]

        (with-open [index-store (db/open-index-store (:indexer *api*))]
          (t/is (= (c/map->EntityTx {:eid picasso-eid
                                     :content-hash new-content-hash
                                     :vt new-valid-time
                                     :tt new-tx-time
                                     :tx-id new-tx-id})
                   (db/entity-as-of index-store :http://dbpedia.org/resource/Pablo_Picasso new-valid-time new-tx-time)))
          (t/is (= (c/map->EntityTx {:eid picasso-eid
                                     :content-hash content-hash
                                     :vt valid-time
                                     :tt tx-time
                                     :tx-id tx-id})
                   (db/entity-as-of index-store :http://dbpedia.org/resource/Pablo_Picasso new-valid-time tx-time))))

        (t/testing "can correct entity at earlier valid time"
          (let [new-picasso (assoc picasso :bar :foo)
                new-content-hash (c/new-id new-picasso)
                prev-tx-time new-tx-time
                prev-tx-id new-tx-id
                new-valid-time #inst "2018-05-22"
                {new-tx-time :crux.tx/tx-time
                 new-tx-id   :crux.tx/tx-id}
                (fix/submit+await-tx [[:crux.tx/put new-picasso new-valid-time]])]

            (with-open [index-store (db/open-index-store (:indexer *api*))]
              (t/is (= (c/map->EntityTx {:eid picasso-eid
                                         :content-hash new-content-hash
                                         :vt new-valid-time
                                         :tt new-tx-time
                                         :tx-id new-tx-id})
                       (db/entity-as-of index-store :http://dbpedia.org/resource/Pablo_Picasso new-valid-time new-tx-time)))

              (t/is (= prev-tx-id
                       (:tx-id (db/entity-as-of index-store :http://dbpedia.org/resource/Pablo_Picasso prev-tx-time prev-tx-time)))))))

        (t/testing "can delete entity"
          (let [new-valid-time #inst "2018-05-23"
                {new-tx-time :crux.tx/tx-time
                 new-tx-id   :crux.tx/tx-id}
                (fix/submit+await-tx [[:crux.tx/delete :http://dbpedia.org/resource/Pablo_Picasso new-valid-time]])]
            (with-open [index-store (db/open-index-store (:indexer *api*))]
              (t/is (nil? (db/entity-as-of index-store :http://dbpedia.org/resource/Pablo_Picasso new-valid-time new-tx-time)))
              (t/testing "first version of entity is still visible in the past"
                (t/is (= tx-id (:tx-id (db/entity-as-of index-store :http://dbpedia.org/resource/Pablo_Picasso valid-time new-tx-time))))))))))

    (t/testing "can retrieve history of entity"
      (with-open [index-store (db/open-index-store (:indexer *api*))
                  history (db/open-entity-history index-store :http://dbpedia.org/resource/Pablo_Picasso :desc
                                                  {:with-corrections? true})]
        (t/is (= 5 (count (iterator-seq history))))))))

(t/deftest test-can-cas-entity
  (let [{picasso-tx-time :crux.tx/tx-time, picasso-tx-id :crux.tx/tx-id} (api/submit-tx *api* [[:crux.tx/put picasso]])]

    (t/testing "compare and set does nothing with wrong content hash"
      (let [wrong-picasso (assoc picasso :baz :boz)
            cas-failure-tx (api/submit-tx *api* [[:crux.tx/cas wrong-picasso (assoc picasso :foo :bar)]])]

        (api/await-tx *api* cas-failure-tx (Duration/ofMillis 1000))

        (with-open [index-store (db/open-index-store (:indexer *api*))
                    history (db/open-entity-history index-store picasso-id :desc {})]
          (t/is (= [(c/map->EntityTx {:eid picasso-eid
                                      :content-hash (c/new-id picasso)
                                      :vt picasso-tx-time
                                      :tt picasso-tx-time
                                      :tx-id picasso-tx-id})]
                   (iterator-seq history))))))

    (t/testing "compare and set updates with correct content hash"
      (let [new-picasso (assoc picasso :new? true)
            {new-tx-time :crux.tx/tx-time, new-tx-id :crux.tx/tx-id} (fix/submit+await-tx [[:crux.tx/cas picasso new-picasso]])]

        (with-open [index-store (db/open-index-store (:indexer *api*))
                    history (db/open-entity-history index-store picasso-id :desc {})]
          (t/is (= [(c/map->EntityTx {:eid picasso-eid
                                      :content-hash (c/new-id new-picasso)
                                      :vt new-tx-time
                                      :tt new-tx-time
                                      :tx-id new-tx-id})
                    (c/map->EntityTx {:eid picasso-eid
                                      :content-hash (c/new-id picasso)
                                      :vt picasso-tx-time
                                      :tt picasso-tx-time
                                      :tx-id picasso-tx-id})]
                   (iterator-seq history)))))))

  (t/testing "compare and set can update non existing nil entity"
    (let [ivan {:crux.db/id :ivan, :value 12}
          {ivan-tx-time :crux.tx/tx-time, ivan-tx-id :crux.tx/tx-id} (fix/submit+await-tx [[:crux.tx/cas nil ivan]])]

      (with-open [index-store (db/open-index-store (:indexer *api*))
                  history (db/open-entity-history index-store :ivan :desc {})]
        (t/is (= [(c/map->EntityTx {:eid (c/new-id :ivan)
                                    :content-hash (c/new-id ivan)
                                    :vt ivan-tx-time
                                    :tt ivan-tx-time
                                    :tx-id ivan-tx-id})]
                 (iterator-seq history)))))))

(t/deftest test-match-ops
  (let [{picasso-tx-time :crux.tx/tx-time, picasso-tx-id :crux.tx/tx-id} (api/submit-tx *api* [[:crux.tx/put picasso]])]

    (t/testing "match does nothing with wrong content hash"
      (let [wrong-picasso (assoc picasso :baz :boz)
            match-failure-tx (api/submit-tx *api* [[:crux.tx/match picasso-id wrong-picasso]])]

        (api/await-tx *api* match-failure-tx (Duration/ofMillis 1000))

        (with-open [index-store (db/open-index-store (:indexer *api*))
                    history (db/open-entity-history index-store picasso-id :desc {})]
          (t/is (= [(c/map->EntityTx {:eid picasso-eid
                                      :content-hash (c/new-id picasso)
                                      :vt picasso-tx-time
                                      :tt picasso-tx-time
                                      :tx-id picasso-tx-id})]
                   (iterator-seq history))))))

    (t/testing "match continues with correct content hash"
      (let [new-picasso (assoc picasso :new? true)
            {new-tx-time :crux.tx/tx-time, new-tx-id :crux.tx/tx-id} (fix/submit+await-tx [[:crux.tx/match picasso-id picasso]
                                                                                           [:crux.tx/put new-picasso]])]

        (with-open [index-store (db/open-index-store (:indexer *api*))
                    history (db/open-entity-history index-store picasso-id :desc {})]
          (t/is (= [(c/map->EntityTx {:eid picasso-eid
                                      :content-hash (c/new-id new-picasso)
                                      :vt new-tx-time
                                      :tt new-tx-time
                                      :tx-id new-tx-id})
                    (c/map->EntityTx {:eid picasso-eid
                                      :content-hash (c/new-id picasso)
                                      :vt picasso-tx-time
                                      :tt picasso-tx-time
                                      :tx-id picasso-tx-id})]
                   (iterator-seq history)))))))

  (t/testing "match can check non existing entity"
    (let [ivan {:crux.db/id :ivan, :value 12}
          {ivan-tx-time :crux.tx/tx-time, ivan-tx-id :crux.tx/tx-id} (fix/submit+await-tx [[:crux.tx/match :ivan nil]
                                                                                           [:crux.tx/put ivan]])]

      (with-open [index-store (db/open-index-store (:indexer *api*))
                  history (db/open-entity-history index-store :ivan :desc {})]
        (t/is (= [(c/map->EntityTx {:eid (c/new-id :ivan)
                                    :content-hash (c/new-id ivan)
                                    :vt ivan-tx-time
                                    :tt ivan-tx-time
                                    :tx-id ivan-tx-id})]
                 (iterator-seq history)))))))

(t/deftest test-can-evict-entity
  (let [{put-tx-time :crux.tx/tx-time} (api/submit-tx *api* [[:crux.tx/put picasso #inst "2018-05-21"]])
        {evict-tx-time :crux.tx/tx-time} (fix/submit+await-tx [[:crux.tx/evict picasso-id]])]

    (with-open [index-store (db/open-index-store (:indexer *api*))
                history (db/open-entity-history index-store picasso-id :desc {})]
      (let [picasso-history (iterator-seq history)]
        (t/testing "eviction keeps tx history"
          (t/is (= 1 (count (map :content-hash picasso-history)))))
        (t/testing "eviction removes docs"
          (t/is (empty? (->> (db/fetch-docs (:document-store *api*) (keep :content-hash picasso-history))
                             vals
                             (remove idx/evicted-doc?)))))))))

(t/deftest test-handles-legacy-evict-events
  (let [{put-tx-time ::tx/tx-time, put-tx-id ::tx/tx-id} (fix/submit+await-tx [[:crux.tx/put picasso #inst "2018-05-21"]])

        evict-tx-time #inst "2018-05-22"
        evict-tx-id (inc put-tx-id)

        index-evict! #(tx/index-tx (:tx-consumer *api*)
                                   {:crux.tx/tx-time evict-tx-time
                                    :crux.tx/tx-id evict-tx-id}
                                   [[:crux.tx/evict picasso-id #inst "2018-05-23"]])]

    ;; we have to index these manually because the new evict API won't allow docs
    ;; with the legacy valid-time range
    (t/testing "eviction throws if legacy params and no explicit override"
      (t/is (thrown-with-msg? IllegalArgumentException
                              #"^Evict no longer supports time-range parameters."
                              (index-evict!))))

    (t/testing "no docs evicted yet"
      (with-open [index-store (db/open-index-store (:indexer *api*))
                  history (db/open-entity-history index-store picasso-id :desc {})]
        (t/is (seq (->> (db/fetch-docs (:document-store *api*)
                                        (->> (iterator-seq history)
                                             (keep :content-hash)))
                        vals
                        (remove idx/evicted-doc?))))))

    (binding [tx/*evict-all-on-legacy-time-ranges?* true]
      (let [{:keys [tombstones]} (index-evict!)]
        (db/submit-docs (:document-store *api*) tombstones)

        (t/testing "eviction removes docs"
          (with-open [index-store (db/open-index-store (:indexer *api*))
                      history (db/open-entity-history index-store picasso-id :desc {})]
            (t/is (empty? (->> (db/fetch-docs (:document-store *api*)
                                               (->> (iterator-seq history)
                                                    (keep :content-hash)))
                               vals
                               (remove idx/evicted-doc?))))))))))

(t/deftest test-multiple-txs-in-same-ms-441
  (let [ivan {:crux.db/id :ivan}
        ivan1 (assoc ivan :value 1)
        ivan2 (assoc ivan :value 2)
        t #inst "2019-11-29"]
    (db/index-docs (:indexer *api*) {(c/new-id ivan1) ivan1
                                     (c/new-id ivan2) ivan2})

    (tx/index-tx (:tx-consumer *api*) {:crux.tx/tx-time t, :crux.tx/tx-id 1}
                 [[:crux.tx/put :ivan (c/->id-buffer (c/new-id ivan1))]])
    (tx/index-tx (:tx-consumer *api*) {:crux.tx/tx-time t, :crux.tx/tx-id 2}
                 [[:crux.tx/put :ivan (c/->id-buffer (c/new-id ivan2))]])

    (with-open [index-store (db/open-index-store (:indexer *api*))]
      (with-open [history (db/open-entity-history index-store :ivan :desc {:with-corrections? true})]
        (t/is (= [(c/->EntityTx (c/new-id :ivan) t t 2 (c/new-id ivan2))
                  (c/->EntityTx (c/new-id :ivan) t t 1 (c/new-id ivan1))]
                 (iterator-seq history))))

      (with-open [history (db/open-entity-history index-store :ivan :desc {:start {::tx/tx-time t, ::db/valid-time t}})]
        (t/is (= [(c/->EntityTx (c/new-id :ivan) t t 2 (c/new-id ivan2))]
                 (iterator-seq history))))

      (with-open [history (db/open-entity-history index-store :ivan :asc {:start {::db/valid-time t}})]
        (t/is (= [(c/->EntityTx (c/new-id :ivan) t t 2 (c/new-id ivan2))]
                 (iterator-seq history)))))))

(t/deftest test-entity-history-seq-corner-cases
  (let [ivan {:crux.db/id :ivan}
        ivan1 (assoc ivan :value 1)
        ivan2 (assoc ivan :value 2)
        t1 #inst "2020-05-01"
        t2 #inst "2020-05-02"]
    (db/index-docs (:indexer *api*) {(c/new-id ivan1) ivan1
                                     (c/new-id ivan2) ivan2})

    (tx/index-tx (:tx-consumer *api*) {:crux.tx/tx-time t1, :crux.tx/tx-id 1}
                 [[:crux.tx/put :ivan (c/->id-buffer (c/new-id ivan1)) t1]])
    (tx/index-tx (:tx-consumer *api*) {:crux.tx/tx-time t2, :crux.tx/tx-id 2}
                 [[:crux.tx/put :ivan (c/->id-buffer (c/new-id ivan2)) t1]
                  [:crux.tx/put :ivan (c/->id-buffer (c/new-id ivan2))]])

    (with-open [index-store (db/open-index-store (:indexer *api*))]
      (let [etx-v1-t1 (c/->EntityTx (c/new-id :ivan) t1 t1 1 (c/new-id ivan1))
            etx-v1-t2 (c/->EntityTx (c/new-id :ivan) t1 t2 2 (c/new-id ivan2))
            etx-v2-t2 (c/->EntityTx (c/new-id :ivan) t2 t2 2 (c/new-id ivan2))]
        (letfn [(history-asc [opts]
                  (with-open [history (db/open-entity-history index-store :ivan :asc opts)]
                    (vec (iterator-seq history))))
                (history-desc [opts]
                  (with-open [history (db/open-entity-history index-store :ivan :desc opts)]
                    (vec (iterator-seq history))))]

          (t/testing "start is inclusive"
            (t/is (= [etx-v2-t2
                      etx-v1-t2]
                     (history-desc {:start {::tx/tx-time t2, ::db/valid-time t2}})))

            (t/is (= [etx-v1-t2]
                     (history-desc {:start {::db/valid-time t1}})))

            (t/is (= [etx-v1-t2 etx-v2-t2]
                     (history-asc {:start {::tx/tx-time t2}})))

            (t/is (= [etx-v1-t1 etx-v1-t2 etx-v2-t2]
                     (history-asc {:start {::tx/tx-time t1
                                          ::db/valid-time t1}
                                   :with-corrections? true}))))

          (t/testing "end is exclusive"
            (t/is (= [etx-v2-t2]
                     (history-desc {:start {::tx/tx-time t2, ::db/valid-time t2}
                                    :end {::tx/tx-time t1, ::db/valid-time t1}})))

            (t/is (= []
                     (history-desc {:end {::db/valid-time t2}})))

            (t/is (= [etx-v1-t1]
                     (history-asc {:end {::tx/tx-time t2}})))

            (t/is (= []
                     (history-asc {:start {::db/valid-time t1},
                                   :end {::tx/tx-time t1}})))))))))

(t/deftest test-put-delete-range-semantics
  (t/are [txs history] (let [eid (keyword (gensym "ivan"))
                             ivan {:crux.db/id eid, :name "Ivan"}
                             res (mapv (fn [[value & vts]]
                                         (api/submit-tx *api* [(into (if value
                                                                       [:crux.tx/put (assoc ivan :value value)]
                                                                       [:crux.tx/delete eid])
                                                                     vts)]))
                                       txs)
                             first-vt (ffirst history)
                             last-tx (last res)]

                         (api/await-tx *api* last-tx nil)

                         (with-open [index-store (db/open-index-store (:indexer *api*))
                                     entity-history (db/open-entity-history index-store eid :asc
                                                                            {:start {::db/valid-time first-vt}})]
                           (let [entity-history (iterator-seq entity-history)]
                             (t/is (= (for [[vt tx-idx value] history]
                                        [vt (get-in res [tx-idx :crux.tx/tx-id]) (c/new-id (when value
                                                                                             (assoc ivan :value value)))])

                                      (->> entity-history
                                           (map (juxt :vt :tx-id :content-hash))))))))

    ;; pairs
    ;; [[value vt ?end-vt] ...]
    ;; [[vt tx-idx value] ...]

    [[26 #inst "2019-11-26" #inst "2019-11-29"]]
    [[#inst "2019-11-26" 0 26]
     [#inst "2019-11-29" 0 nil]]

    ;; re-instates the previous value at the end of the range
    [[25 #inst "2019-11-25"]
     [26 #inst "2019-11-26" #inst "2019-11-29"]]
    [[#inst "2019-11-25" 0 25]
     [#inst "2019-11-26" 1 26]
     [#inst "2019-11-29" 0 25]]

    ;; delete a range
    [[25 #inst "2019-11-25"]
     [nil #inst "2019-11-26" #inst "2019-11-29"]]
    [[#inst "2019-11-25" 0 25]
     [#inst "2019-11-26" 1 nil]
     [#inst "2019-11-29" 0 25]]

    ;; override a range
    [[25 #inst "2019-11-25" #inst "2019-11-27"]
     [nil #inst "2019-11-25" #inst "2019-11-27"]
     [26 #inst "2019-11-26" #inst "2019-11-29"]]
    [[#inst "2019-11-25" 1 nil]
     [#inst "2019-11-26" 2 26]
     [#inst "2019-11-27" 2 26]
     [#inst "2019-11-29" 0 nil]]

    ;; merge a range
    [[25 #inst "2019-11-25" #inst "2019-11-27"]
     [26 #inst "2019-11-26" #inst "2019-11-29"]]
    [[#inst "2019-11-25" 0 25]
     [#inst "2019-11-26" 1 26]
     [#inst "2019-11-27" 1 26]
     [#inst "2019-11-29" 0 nil]]

    ;; shouldn't override the value at end-vt if there's one there
    [[25 #inst "2019-11-25"]
     [29 #inst "2019-11-29"]
     [26 #inst "2019-11-26" #inst "2019-11-29"]]
    [[#inst "2019-11-25" 0 25]
     [#inst "2019-11-26" 2 26]
     [#inst "2019-11-29" 1 29]]

    ;; should re-instate 28 at the end of the range
    [[25 #inst "2019-11-25"]
     [28 #inst "2019-11-28"]
     [26 #inst "2019-11-26" #inst "2019-11-29"]]
    [[#inst "2019-11-25" 0 25]
     [#inst "2019-11-26" 2 26]
     [#inst "2019-11-28" 2 26]
     [#inst "2019-11-29" 1 28]]

    ;; 26.1 should overwrite the full range
    [[28 #inst "2019-11-28"]
     [26 #inst "2019-11-26" #inst "2019-11-29"]
     [26.1 #inst "2019-11-26"]]
    [[#inst "2019-11-26" 2 26.1]
     [#inst "2019-11-28" 2 26.1]
     [#inst "2019-11-29" 0 28]]

    ;; 27 should override the latter half of the range
    [[25 #inst "2019-11-25"]
     [26 #inst "2019-11-26" #inst "2019-11-29"]
     [27 #inst "2019-11-27"]]
    [[#inst "2019-11-25" 0 25]
     [#inst "2019-11-26" 1 26]
     [#inst "2019-11-27" 2 27]
     [#inst "2019-11-29" 0 25]]

    ;; 27 should still override the latter half of the range
    [[25 #inst "2019-11-25"]
     [28 #inst "2019-11-28"]
     [26 #inst "2019-11-26" #inst "2019-11-29"]
     [27 #inst "2019-11-27"]]
    [[#inst "2019-11-25" 0 25]
     [#inst "2019-11-26" 2 26]
     [#inst "2019-11-27" 3 27]
     [#inst "2019-11-28" 3 27]
     [#inst "2019-11-29" 1 28]]))

;; TODO: This test just shows that this is an issue, if we fix the
;; underlying issue this test should start failing. We can then change
;; the second assertion if we want to keep it around to ensure it
;; keeps working.
(t/deftest test-corrections-in-the-past-slowes-down-bitemp-144
  (let [ivan {:crux.db/id :ivan :name "Ivan"}
        start-valid-time #inst "2019"
        number-of-versions 1000
        tx (fix/submit+await-tx (for [n (range number-of-versions)]
                                  [:crux.tx/put (assoc ivan :version n) (Date. (+ (.getTime start-valid-time) (inc (long n))))]))]

    (with-open [index-store (db/open-index-store (:indexer *api*))]
      (let [baseline-time (let [start-time (System/nanoTime)
                                valid-time (Date. (+ (.getTime start-valid-time) number-of-versions))]
                            (t/testing "last version of entity is visible at now"
                              (t/is (= valid-time (:vt (db/entity-as-of index-store :ivan valid-time (Date.))))))
                            (- (System/nanoTime) start-time))]

        (let [start-time (System/nanoTime)
              valid-time (Date. (+ (.getTime start-valid-time) number-of-versions))]
          (t/testing "no version is visible before transactions"
            (t/is (nil? (db/entity-as-of index-store :ivan valid-time valid-time)))
            (let [corrections-time (- (System/nanoTime) start-time)]
              ;; TODO: This can be a bit flaky. This assertion was
              ;; mainly there to prove the opposite, but it has been
              ;; fixed. Can be added back to sanity check when
              ;; changing indexes.
              #_(t/is (>= baseline-time corrections-time)))))))))

(t/deftest test-can-read-kv-tx-log
  (let [ivan {:crux.db/id :ivan :name "Ivan"}

        tx1-ivan (assoc ivan :version 1)
        tx1-valid-time #inst "2018-11-26"
        {tx1-id :crux.tx/tx-id
         tx1-tx-time :crux.tx/tx-time}
        (fix/submit+await-tx [[:crux.tx/put tx1-ivan tx1-valid-time]])

        tx2-ivan (assoc ivan :version 2)
        tx2-petr {:crux.db/id :petr :name "Petr"}
        tx2-valid-time #inst "2018-11-27"
        {tx2-id :crux.tx/tx-id
         tx2-tx-time :crux.tx/tx-time}
        (fix/submit+await-tx [[:crux.tx/put tx2-ivan tx2-valid-time]
                              [:crux.tx/put tx2-petr tx2-valid-time]])]

    (with-open [log-iterator (db/open-tx-log (:tx-log *api*) nil)]
      (let [log (iterator-seq log-iterator)]
        (t/is (not (realized? log)))
        (t/is (= [{:crux.tx/tx-id tx1-id
                   :crux.tx/tx-time tx1-tx-time
                   :crux.tx.event/tx-events [[:crux.tx/put (c/new-id :ivan) (c/new-id tx1-ivan) tx1-valid-time]]}
                  {:crux.tx/tx-id tx2-id
                   :crux.tx/tx-time tx2-tx-time
                   :crux.tx.event/tx-events [[:crux.tx/put (c/new-id :ivan) (c/new-id tx2-ivan) tx2-valid-time]
                                             [:crux.tx/put (c/new-id :petr) (c/new-id tx2-petr) tx2-valid-time]]}]
                 log))))))

(t/deftest test-can-apply-transaction-fn
  (let [exception (atom nil)
        latest-exception #(let [e @exception]
                            (reset! exception nil)
                            e)
        rethrow-latest-exception (fn []
                                   (throw (latest-exception)))]
    (with-redefs [tx/tx-fns-enabled? true
                  tx/log-tx-fn-error (fn [t & args]
                                       (reset! exception t))]
      (let [v1-ivan {:crux.db/id :ivan :name "Ivan" :age 40}
            v4-ivan (assoc v1-ivan :name "IVAN")
            update-attribute-fn {:crux.db/id :update-attribute-fn
                                 :crux.db.fn/body
                                 '(fn [db eid k f]
                                    [[:crux.tx/put (update (crux.api/entity db eid) k (eval f))]])}]
        (fix/submit+await-tx [[:crux.tx/put v1-ivan]
                              [:crux.tx/put update-attribute-fn]])
        (t/is (= v1-ivan (api/entity (api/db *api*) :ivan)))
        (t/is (= update-attribute-fn (api/entity (api/db *api*) :update-attribute-fn)))
        (t/is (nil? (latest-exception)))

        (let [v2-ivan (assoc v1-ivan :age 41)
              inc-ivans-age '{:crux.db/id :inc-ivans-age
                              :crux.db.fn/args [:ivan :age inc]}]
          (fix/submit+await-tx [[:crux.tx/fn :update-attribute-fn inc-ivans-age]])
          (t/is (nil? (latest-exception)))
          (t/is (= v2-ivan (api/entity (api/db *api*) :ivan)))
          (t/is (= inc-ivans-age (api/entity (api/db *api*) :inc-ivans-age)))

          (t/testing "resulting documents are indexed"
            (t/is (= #{[41]} (api/q (api/db *api*)
                                    '[:find age :where [e :name "Ivan"] [e :age age]]))))

          (t/testing "exceptions"
            (t/testing "non existing tx fn"
              (fix/submit+await-tx '[[:crux.tx/fn :non-existing-fn]])
              (t/is (= v2-ivan (api/entity (api/db *api*) :ivan)))
              (t/is (thrown?  NullPointerException (rethrow-latest-exception))))

            (t/testing "invalid arguments"
              (fix/submit+await-tx '[[:crux.tx/fn :update-attribute-fn {:crux.db/id :inc-ivans-age
                                                                        :crux.db.fn/args [:ivan
                                                                                          :age
                                                                                          foo]}]])
              (t/is (= inc-ivans-age (api/entity (api/db *api*) :inc-ivans-age)))
              (t/is (thrown? clojure.lang.Compiler$CompilerException (rethrow-latest-exception))))

            (t/testing "invalid results"
              (fix/submit+await-tx [[:crux.tx/put
                                     {:crux.db/id :invalid-fn
                                      :crux.db.fn/body
                                      '(fn [db]
                                         [[:crux.tx/foo]])}]])
              (fix/submit+await-tx '[[:crux.tx/fn :invalid-fn]])
              (t/is (thrown-with-msg? IllegalArgumentException #"Invalid tx op" (rethrow-latest-exception))))

            (t/testing "exception thrown"
              (fix/submit+await-tx [[:crux.tx/put
                                     {:crux.db/id :exception-fn
                                      :crux.db.fn/body
                                      '(fn [db]
                                         (throw (RuntimeException. "foo")))}]])
              (fix/submit+await-tx '[[:crux.tx/fn :exception-fn]])
              (t/is (thrown-with-msg? RuntimeException #"foo" (rethrow-latest-exception))))

            (t/testing "still working after errors"
              (let [v3-ivan (assoc v1-ivan :age 40)]
                (fix/submit+await-tx '[[:crux.tx/fn :update-attribute-fn {:crux.db/id :dec-ivans-age
                                                                          :crux.db.fn/args [:ivan
                                                                                            :age
                                                                                            dec]}]])
                (t/is (nil? (latest-exception)))
                (t/is (= v3-ivan (api/entity (api/db *api*) :ivan))))))

          (t/testing "function ops can return other function ops"
            (let [returns-fn {:crux.db/id :returns-fn
                              :crux.db.fn/body
                              '(fn [db]
                                 '[[:crux.tx/fn :update-attribute-fn {:crux.db/id :upcase-ivans-name
                                                                      :crux.db.fn/args [:ivan
                                                                                        :name
                                                                                        clojure.string/upper-case]}]])}]
              (fix/submit+await-tx [[:crux.tx/put returns-fn]])
              (fix/submit+await-tx [[:crux.tx/fn :returns-fn]])
              (t/is (nil? (latest-exception)))
              (t/is (= v4-ivan (api/entity (api/db *api*) :ivan)))))

          (t/testing "repeated 'merge' operation behaves correctly"
            (let [v5-ivan (merge v4-ivan
                                 {:height 180
                                  :hair-style "short"
                                  :mass 60})
                  merge-fn {:crux.db/id :merge-fn
                            :crux.db.fn/body '(fn [db eid m]
                                                [[:crux.tx/put (merge (crux.api/entity db eid) m)]])}
                  merge-1 '{:crux.db/id :merge-1
                            :crux.db.fn/args [:ivan {:mass 60, :hair-style "short"}]}
                  merge-2 '{:crux.db/id :merge-2
                            :crux.db.fn/args [:ivan {:height 180}]}]
              (fix/submit+await-tx [[:crux.tx/put merge-fn]])
              (fix/submit+await-tx [[:crux.tx/fn :merge-fn merge-1]])
              (fix/submit+await-tx [[:crux.tx/fn :merge-fn merge-2]])
              (t/is (nil? (latest-exception)))
              (t/is (= v5-ivan (api/entity (api/db *api*) :ivan)))))

          (t/testing "can access current transaction as dynamic var"
            (fix/submit+await-tx
             [[:crux.tx/put
               {:crux.db/id :tx-metadata-fn
                :crux.db.fn/body
                '(fn [db]
                   [[:crux.tx/put {:crux.db/id :tx-metadata :crux.tx/current-tx crux.tx/*current-tx*}]])}]])
            (let [submitted-tx (fix/submit+await-tx '[[:crux.tx/fn :tx-metadata-fn]])]
              (t/is (nil? (latest-exception)))
              (t/is (= {:crux.db/id :tx-metadata
                        :crux.tx/current-tx (assoc submitted-tx :crux.tx.event/tx-events [[:crux.tx/fn :tx-metadata-fn]])}
                       (api/entity (api/db *api*) :tx-metadata))))))))))

(t/deftest tx-log-evict-454 []
  (fix/submit+await-tx [[:crux.tx/put {:crux.db/id :to-evict}]])
  (fix/submit+await-tx [[:crux.tx/cas {:crux.db/id :to-evict} {:crux.db/id :to-evict :test "test"}]])
  (fix/submit+await-tx [[:crux.tx/evict :to-evict]])

  (with-open [log-iterator (api/open-tx-log *api* nil true)]
    (t/is (= (->> (iterator-seq log-iterator)
                  (map :crux.api/tx-ops))
             [[[:crux.tx/put
                #:crux.db{:id #crux/id "6abe906510aa2263737167c12c252245bdcf6fb0",
                          :evicted? true}]]
              [[:crux.tx/cas
                #:crux.db{:id #crux/id "6abe906510aa2263737167c12c252245bdcf6fb0",
                          :evicted? true}
                #:crux.db{:id #crux/id "6abe906510aa2263737167c12c252245bdcf6fb0",
                          :evicted? true}]]
              [[:crux.tx/evict
                #crux/id "6abe906510aa2263737167c12c252245bdcf6fb0"]]]))))

(t/deftest nil-transaction-fn-457
  (with-redefs [tx/tx-fns-enabled? true]
    (let [merge-fn {:crux.db/id :my-fn
                    :crux.db.fn/body '(fn [db] nil)}]

      (fix/submit+await-tx [[:crux.tx/put merge-fn]])
      (fix/submit+await-tx [[:crux.tx/fn
                             :my-fn
                             {:crux.db/id (java.util.UUID/randomUUID)
                              :crux.db.fn/args []}]
                            [:crux.tx/put {:crux.db/id :foo
                                           :bar :baz}]])

      (t/is (= {:crux.db/id :foo, :bar :baz}
               (api/entity (api/db *api*) :foo))))))

(t/deftest map-ordering-362
  (t/testing "cas is independent of map ordering"
    (fix/submit+await-tx [[:crux.tx/put {:crux.db/id :foo, :foo :bar}]])
    (fix/submit+await-tx [[:crux.tx/cas {:foo :bar, :crux.db/id :foo} {:crux.db/id :foo, :foo :baz}]])

    (t/is (= {:crux.db/id :foo, :foo :baz}
             (api/entity (api/db *api*) :foo))))

  (t/testing "entities with map keys can be retrieved regardless of ordering"
    (let [doc {:crux.db/id {:foo 1, :bar 2}}]
      (fix/submit+await-tx [[:crux.tx/put doc]])

      (t/is (= doc (api/entity (api/db *api*) {:foo 1, :bar 2})))
      (t/is (= doc (api/entity (api/db *api*) {:bar 2, :foo 1})))))

  (t/testing "entities with map values can be joined regardless of ordering"
    (let [doc {:crux.db/id {:foo 2, :bar 4}}]
      (fix/submit+await-tx [[:crux.tx/put doc]
                            [:crux.tx/put {:crux.db/id :baz, :joins {:bar 4, :foo 2}}]
                            [:crux.tx/put {:crux.db/id :quux, :joins {:foo 2, :bar 4}}]])

      (t/is (= #{[{:foo 2, :bar 4} :baz]
                 [{:foo 2, :bar 4} :quux]}
               (api/q (api/db *api*) '{:find [parent child]
                                       :where [[parent :crux.db/id _]
                                               [child :joins parent]]}))))))

(t/deftest overlapping-valid-time-ranges-434
  (let [_ (fix/submit+await-tx
           [[:crux.tx/put {:crux.db/id :foo, :v 10} #inst "2020-01-10"]
            [:crux.tx/put {:crux.db/id :bar, :v 5} #inst "2020-01-05"]
            [:crux.tx/put {:crux.db/id :bar, :v 10} #inst "2020-01-10"]

            [:crux.tx/put {:crux.db/id :baz, :v 10} #inst "2020-01-10"]])

        last-tx (fix/submit+await-tx [[:crux.tx/put {:crux.db/id :bar, :v 7} #inst "2020-01-07"]
                                      ;; mixing foo and bar shouldn't matter
                                      [:crux.tx/put {:crux.db/id :foo, :v 8} #inst "2020-01-08" #inst "2020-01-12"] ; reverts to 10 afterwards
                                      [:crux.tx/put {:crux.db/id :foo, :v 9} #inst "2020-01-09" #inst "2020-01-11"] ; reverts to 8 afterwards, then 10
                                      [:crux.tx/put {:crux.db/id :bar, :v 8} #inst "2020-01-08" #inst "2020-01-09"] ; reverts to 7 afterwards
                                      [:crux.tx/put {:crux.db/id :bar, :v 11} #inst "2020-01-11" #inst "2020-01-12"] ; reverts to 10 afterwards
                                      ])

        db (api/db *api*)]

    (with-open [index-store (db/open-index-store (:indexer *api*))]
      (let [eid->history (fn [eid]
                           (with-open [history (db/open-entity-history index-store
                                                                       (c/new-id eid) :asc
                                                                       {:start {::db/valid-time #inst "2020-01-01"}})]
                             (->> (iterator-seq history)
                                  (mapv (fn [{:keys [content-hash vt]}]
                                          [vt (:v (db/get-document index-store content-hash))])))))]
        ;; transaction functions, asserts both still apply at the start of the transaction
        (t/is (= [[#inst "2020-01-08" 8]
                  [#inst "2020-01-09" 9]
                  [#inst "2020-01-10" 9]
                  [#inst "2020-01-11" 8]
                  [#inst "2020-01-12" 10]]
                 (eid->history :foo)))

        (t/is (= [[#inst "2020-01-05" 5]
                  [#inst "2020-01-07" 7]
                  [#inst "2020-01-08" 8]
                  [#inst "2020-01-09" 7]
                  [#inst "2020-01-10" 10]
                  [#inst "2020-01-11" 11]
                  [#inst "2020-01-12" 10]]
                 (eid->history :bar)))))))

(t/deftest cas-docs-not-evicting-371
  (fix/submit+await-tx [[:crux.tx/put {:crux.db/id :foo, :foo :bar}]
                        [:crux.tx/put {:crux.db/id :frob :foo :bar}]])

  (fix/submit+await-tx [[:crux.tx/cas {:crux.db/id :foo, :foo :baz} {:crux.db/id :foo, :foo :quux}]
                        [:crux.tx/put {:crux.db/id :frob :foo :baz}]])
  (fix/submit+await-tx [[:crux.tx/evict :foo]])

  (t/is (nil? (api/document *api* (c/new-id {:crux.db/id :foo, :foo :bar}))))
  (t/is (nil? (api/document *api* (c/new-id {:crux.db/id :foo, :foo :baz}))))
  (t/is (nil? (api/document *api* (c/new-id {:crux.db/id :foo, :foo :quux}))))

  (t/testing "even though the CaS was unrelated, the whole transaction fails - we should still evict those docs"
    (fix/submit+await-tx [[:crux.tx/evict :frob]])
    (t/is (nil? (api/document *api* (c/new-id {:crux.db/id :frob, :foo :bar}))))
    (t/is (nil? (api/document *api* (c/new-id {:crux.db/id :frob, :foo :baz}))))))

(t/deftest raises-tx-events-422
  (let [!events (atom [])
        !latch (promise)
        bus (get-in (meta *api*) [::n/topology ::n/bus])]
    (doseq [event-type #{::tx/indexing-docs ::tx/indexed-docs
                         ::tx/indexing-tx ::tx/indexed-tx}]
      (bus/listen bus {:crux/event-type event-type}
                  (fn [evt]
                    (swap! !events conj evt)
                    (when (= ::tx/indexed-tx (:crux/event-type evt))
                      (deliver !latch @!events)))))

    (let [doc-1 {:crux.db/id :foo, :value 1}
          doc-2 {:crux.db/id :bar, :value 2}
          submitted-tx (fix/submit+await-tx [[:crux.tx/put doc-1] [:crux.tx/put doc-2]])]

      (when (= ::timeout (deref !latch 500 ::timeout))
        (t/is false))

      (t/is (= [{:crux/event-type ::tx/indexing-docs, :doc-ids #{(c/new-id doc-1) (c/new-id doc-2)}}
                {:crux/event-type ::tx/indexed-docs
                 :doc-ids #{(c/new-id doc-1) (c/new-id doc-2)}
                 :av-count 4}
                {:crux/event-type ::tx/indexing-tx, ::tx/submitted-tx submitted-tx}
                {:crux/event-type ::tx/indexed-tx,
                 ::tx/submitted-tx submitted-tx,
                 :committed? true
                 ::txe/tx-events [[:crux.tx/put
                                   #crux/id "0beec7b5ea3f0fdbc95d0dd47f3c5bc275da8a33"
                                   #crux/id "974e28e6484fb6c66e5ca6444ec616207800d815"]
                                  [:crux.tx/put
                                   #crux/id "62cdb7020ff920e5aa642c3d4066950dd1f01f4d"
                                   #crux/id "f2cb628efd5123743c30137b08282b9dee82104a"]]}]
               (-> (vec @!events)
                   (update 1 dissoc :bytes-indexed)))))))

(t/deftest test-wait-while
  (let [twice-no (let [!atom (atom 3)]
                   #(pos? (swap! !atom dec)))]
    (t/is (false? (cio/wait-while twice-no (Duration/ofMillis 100))))
    (t/is (true? (cio/wait-while twice-no (Duration/ofMillis 400))))
    (t/is (true? (cio/wait-while twice-no nil)))))

(t/deftest await-fails-quickly-738
  (with-redefs [tx/index-tx-event (fn [_ _ _] (throw (ex-info "test error for await-fails-quickly-738" {})))]
    (let [!fut (future (fix/submit+await-tx [[:crux.tx/put {:crux.db/id :foo}]]))]
      (t/is (thrown-with-msg? Exception #"Transaction consumer aborted"
                              (deref !fut 1000 ::timeout)))
      (future-cancel !fut))))
