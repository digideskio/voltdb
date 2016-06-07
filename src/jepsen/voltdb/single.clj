(ns jepsen.voltdb.single
  "Implements a table of single registers identified by id. Verifies
  linearizability over independent registers."
  (:require [jepsen [core         :as jepsen]
                    [control      :as c :refer [|]]
                    [checker      :as checker]
                    [client       :as client]
                    [generator    :as gen]
                    [independent  :as independent]
                    [nemesis      :as nemesis]
                    [tests        :as tests]]
            [jepsen.os.debian     :as debian]
            [jepsen.checker.timeline :as timeline]
            [jepsen.voltdb        :as voltdb]
            [knossos.model        :as model]
            [knossos.op           :as op]
            [clojure.string       :as str]
            [clojure.core.reducers :as r]
            [clojure.tools.logging :refer [info warn]]))

(defn client
  "A single-register client. Options:

      :strong-reads?                 Whether to perform normal or strong selects
      :procedure-call-timeout       How long in ms to wait for proc calls
      :connection-response-timeout  How long in ms to wait for connections"
  ([opts] (client nil opts))
  ([conn opts]
   (let [initialized? (promise)]
     (reify client/Client
       (setup! [_ test node]
         (let [conn (voltdb/connect
                      node (select-keys opts
                                        [:procedure-call-timeout
                                         :connection-response-timeout]))]
           (when (deliver initialized? true)
             (try
               (c/on node
                     ; Create table
                     (voltdb/sql-cmd! "CREATE TABLE registers (
                                      id          INTEGER UNIQUE NOT NULL,
                                      value       INTEGER NOT NULL,
                                      PRIMARY KEY (id)
                                      );
                                      PARTITION TABLE registers ON COLUMN id;")
                     (voltdb/sql-cmd! "CREATE PROCEDURE registers_cas
                                      PARTITION ON TABLE registers COLUMN id
                                      AS
                                      UPDATE registers SET value = ?
                                      WHERE id = ? AND value = ?;")
                     (voltdb/sql-cmd! "CREATE PROCEDURE FROM CLASS
                                      jepsen.procedures.SRegisterStrongRead;")
                     (voltdb/sql-cmd! "PARTITION PROCEDURE SRegisterStrongRead
                                      ON TABLE registers COLUMN id;")
                     (info node "table created"))
               (catch RuntimeException e
                 (voltdb/close! conn)
                 (throw e))))
           (client conn opts)))

       (invoke! [this test op]
         (try
           (let [id     (key (:value op))
                 value  (val (:value op))]
             (case (:f op)
               :read   (let [proc (if (:strong-reads? opts)
                                    "SRegisterStrongRead"
                                    "REGISTERS.select")
                             v (-> conn
                                   (voltdb/call! proc id)
                                   first
                                   :rows
                                   first
                                   :VALUE)]
                         (assoc op
                                :type :ok
                                :value (independent/tuple id v)))
               :write (do (voltdb/call! conn "REGISTERS.upsert" id value)
                          (assoc op :type :ok))
               :cas   (let [[v v'] value
                            res (-> conn
                                    (voltdb/call! "registers_cas" v' id v)
                                    first
                                    :rows
                                    first
                                    :modified_tuples)]
                        (assert (#{0 1} res))
                        (assoc op :type (if (zero? res) :fail :ok)))))
           (catch org.voltdb.client.NoConnectionsException e
             (assoc op :type :fail, :error :no-conns))
           (catch org.voltdb.client.ProcCallException e
             (let [type (if (= :read (:f op)) :fail :info)]
               (condp re-find (.getMessage e)
                 #"^No response received in the allotted time"
                 (assoc op :type type, :error :timeout)

                 #"^Connection to database host .+ was lost before a response"
                 (assoc op :type type, :error :conn-lost)

                 #"^Transaction dropped due to change in mastership"
                 (assoc op :type type, :error :mastership-change)

                 (throw e))))))

       (teardown! [_ test]
         (voltdb/close! conn))))))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn single-test
  "Options:

      :time-limit                   How long should we run the test for?
      :tarball                      URL to an enterprise voltdb tarball.
      :strong-reads?                Whether to perform normal or strong selects
      :no-reads?                    Don't bother with reads at all
      :procedure-call-timeout       How long in ms to wait for proc calls
      :connection-response-timeout  How long in ms to wait for connections"
  [opts]
  (merge tests/noop-test
         opts
         {:name    "voltdb single"
          :os      debian/os
          :client  (client (select-keys opts [:strong-reads?
                                              :procedure-call-timeout
                                              :connection-response-timeout]))
          :db      (voltdb/db (:tarball opts))
          :model   (model/cas-register nil)
          :checker (checker/compose
                     {:linear (independent/checker checker/linearizable)
                      :timeline (independent/checker (timeline/html))
                      :perf   (checker/perf)})
          :nemesis (voltdb/with-recover-nemesis
                     (voltdb/isolated-killer-nemesis))
          :concurrency 100
          :generator (->> (independent/concurrent-generator
                            10
                            (range)
                            (fn [id]
                              (->> (gen/mix [w cas])
                                   (gen/reserve 5 (if (:no-reads? opts)
                                                    cas
                                                    r))
                                   (gen/delay 1)
                                   (gen/time-limit 30))))
                          (voltdb/start-stop-recover-gen)
                          (gen/time-limit (:time-limit opts)))}))