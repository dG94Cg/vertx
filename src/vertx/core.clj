;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.core
  (:require [clojure.spec.alpha :as s]
            [promesa.core :as p]
            [vertx.eventbus :as vxe]
            [vertx.util :as vu])
  (:import
   io.vertx.core.Context
   io.vertx.core.DeploymentOptions
   io.vertx.core.Promise
   io.vertx.core.Handler
   io.vertx.core.Verticle
   io.vertx.core.Vertx
   io.vertx.core.VertxOptions
   io.vertx.core.json.JsonObject
   java.util.function.Supplier))

(declare opts->deployment-options)
(declare opts->vertx-options)
(declare build-verticle)
(declare build-actor)
(declare build-disposable)

;; --- Protocols

(definterface IVerticleFactory)

;; --- Public Api

(s/def :vertx.core$system/threads pos?)
(s/def :vertx.core$system/on-error fn?)
(s/def ::system-options
  (s/keys :opt-un [:vertx.core$system/threads
                   :vertx.core$system/on-error]))

(defn system
  "Creates a new vertx actor system instance."
  ([] (system {}))
  ([options]
   (s/assert ::system-options options)
   (let [^VertxOptions opts (opts->vertx-options options)
         ^Vertx vsm (Vertx/vertx opts)]
     (vxe/configure! vsm opts)
     vsm)))

(defn get-or-create-context
  [vsm]
  (.getOrCreateContext ^Vertx (vu/resolve-system vsm)))

(defn current-context
  []
  (Vertx/currentContext))

(defn execute-blocking
  "execute blocking task (current if not explicitly provided), return the promise"
  ([task] (execute-blocking task (current-context) true))
  ([task ctx ordered]
   (let [d (p/deferred)
         h (vu/deferred->handler d)
         wrap-task (reify Handler
                     (handle [_ promise]
                       (.complete promise (task))
                       ))
         ]
     (.executeBlocking ctx wrap-task  ordered h)
     ;; return the handle promise
     d)))

(defn handle-on-context
  "Attaches the context (current if not explicitly provided) to the
  promise execution chain."
  ([prm] (handle-on-context prm (current-context)))
  ([prm ctx]
   (let [d (p/deferred)]
     (p/finally prm (fn [v e]
                      (.runOnContext
                       ^Context ctx
                       ^Handler (reify Handler
                                  (handle [_ v']
                                    (if e
                                      (p/reject! d e)
                                      (p/resolve! d v)))))))
     d)))

(s/def :vertx.core$verticle/on-start fn?)
(s/def :vertx.core$verticle/on-stop fn?)
(s/def :vertx.core$verticle/on-error fn?)
(s/def ::verticle-options
  (s/keys :req-un [:vertx.core$verticle/on-start]
          :opt-un [:vertx.core$verticle/on-stop
                   :vertx.core$verticle/on-error]))

(defn verticle
  "Creates a verticle instance (factory)."
  [options]
  (s/assert ::verticle-options options)
  (reify
    IVerticleFactory
    Supplier
    (get [_] (build-verticle options))))

(defn verticle?
  "Return `true` if `v` is instance of `IVerticleFactory`."
  [v]
  (instance? IVerticleFactory v))

(s/def :vertx.core$actor/on-message fn?)
(s/def ::actor-options
  (s/keys ;;:req-un [:vertx.core$actor/on-message] ;; no need for on-message now
          :opt-un [:vertx.core$verticle/on-start
                   :vertx.core$verticle/on-error
                   :vertx.core$verticle/on-stop]))
;; actor -> {"addr" (fn [event ctx])} -> {:on-start fn :on-stop fn :on-error fn} -> verticle

(defn actor
  "A shortcut for create a verticle instance (factory) that consumes a
  specific topic.
  actor -> topics : {\"addr\" (fn [event actor-ctx])} -> options : {:on-start fn :on-stop fn :on-error fn} -> verticle.
  :on-start should return actor-state, or it should be {}"
  [topics options]
  ;; require a list or vector
  (assert (or (vector? topics) (list? topics) (map? topics)))
  (s/assert ::actor-options options)
  (reify
    IVerticleFactory
    Supplier
    (get [_] (build-actor topics options))))

(s/def :vertx.core$deploy/instances pos?)
(s/def :vertx.core$deploy/worker boolean?)
(s/def ::deploy-options
  (s/keys :opt-un [:vertx.core$deploy/worker
                   :vertx.core$deploy/instances]))

(defn deploy!
  "Deploy a verticle."
  ([vsm supplier] (deploy! vsm supplier nil))
  ([vsm supplier options]
   (s/assert verticle? supplier)
   (s/assert ::deploy-options options)
   (let [d (p/deferred)
         o (opts->deployment-options options)]
     (.deployVerticle ^Vertx vsm
                      ^Supplier supplier
                      ^DeploymentOptions o
                      ^Handler (vu/deferred->handler d))
     (p/then' d (fn [id] (build-disposable vsm id))))))

(defn undeploy!
  "Undeploy the verticle, this function should be rarelly used because
  the easiest way to undeplo is executin the callable returned by
  `deploy!` function."
  [vsm id]
  (s/assert string? id)
  (let [d (p/deferred)]
    (.undeploy ^Vertx (vu/resolve-system vsm)
               ^String id
               ^Handler (vu/deferred->handler d))
    d))

;; --- Impl

(defn- build-verticle
  [{:keys [on-start on-stop on-error]
    :or {on-error (constantly nil)
         on-stop (constantly nil)}}]
  (let [vsm (volatile! nil)
        ctx (volatile! nil)
        lst (volatile! nil)]
    (reify Verticle
      (init [_ instance context]
        (vreset! vsm instance)
        (vreset! ctx context))
      (getVertx [_] @vsm)
      (^void start [_ ^Promise o]
       (-> (p/do! (on-start @ctx))
           (p/handle (fn [state error]
                       (if error
                         (do
                           (.fail o error)
                           (on-error @ctx error))
                         (do
                           (when (map? state)
                             (vswap! lst merge state))
                           (.complete o)))))))
      (^void stop [_ ^Promise o]
       (p/handle (p/do! (on-stop @ctx @lst))
                 (fn [_ err]
                   (if err
                     (do (on-error err)
                         (.fail o err))
                     (.complete o))))))))

(defn- merge-and-reply [ctx event data]
  ;; sync the context first, because the actor may send event to self
  (let [state (.get ctx "state")]
    (cond
      (:merge data) (.put ctx "state" (merge state (:merge data)))
      (:rm data) (let [rm (:rm data)
                       next_state (reduce dissoc state
                                          (if (or (list? rm) (vector? rm))
                                            rm
                                            [rm]))]
                   (.put ctx "state" next_state))))

;; send the response
  (when (:reply data)
    (.reply event
            (io.vertx.core.json.Json/encode data))))

(defn- build-actor
  [topics {:keys [on-error on-stop on-start]
           :or {on-error (constantly nil)
                on-start (constantly {})
                on-stop (constantly nil)}}]
  (letfn [(-on-start [ctx]
            (let [state (on-start ctx)
                  state (if (nil? state) {} state)
                  ;; TODO extract into defn as (listen-on-topic ctx topics)
                  vertx (vu/resolve-system ctx)
                  bus   (.eventBus vertx)
                  ctx   (.getContext vertx)
                  ;; FIXME use a better code style
                  listen (fn [state [addr handler]]
                           ;; listen the event instead of the eventbus because the origin one will auto response
                           (.consumer bus (str addr)
                                      (vu/fn->handler
                                       (fn [event]
                                         ;; TODO use promise to complete this so that actor can pass this event into other situation
                                         ;; handle the response
                                         (merge-and-reply ctx event
                                                          ;; the handler should receive two argument
                                                          ;; TODO extract msg as (build-msg event)
                                                          (handler {:headers (.headers event)
                                                                    :body (.body event)
                                                                    :address (.address event)
                                                                    :reply (fn
                                                                             ([data] (.reply event data))
                                                                             ([data opt] (.reply event data opt)))
                                                                    :self event
                                                                    }
                                                                   (.get ctx "state")
                                                                   ))
                                         )))
                           ;; register the handler at ctx
                           (assoc state addr handler)
                           )
                  ;; register the handler
                  state                     (reduce listen state topics)
                  ]
              (.put ctx "state" state)
            state))]
              ;; set the state into context for further use
    (build-verticle {:on-error on-error
                     :on-stop on-stop
                     :on-start -on-start})))

(defn- build-disposable
  [vsm id]
  (reify
    clojure.lang.IDeref
    (deref [_] id)

    clojure.lang.IFn
    (invoke [_] (undeploy! vsm id))

    java.io.Closeable
    (close [_]
      @(undeploy! vsm id))))

(defn- opts->deployment-options
  [{:keys [instances worker config]}]

  (letfn [(put [config [index value]]
            (.put config (str index) value)
            config)
          ;; convert the pmap into jsonObject
          (toConfig [pmap]
            (reduce put (JsonObject.) pmap))
            ]
    (let [opts (DeploymentOptions.)]
      (when instances (.setInstances opts (int instances)))
      (when worker (.setWorker opts worker))
      (when config (.setConfig opts (toConfig config)))
      opts)))

(defn- opts->vertx-options
  [{:keys [threads on-error]}]
  (let [opts (VertxOptions.)]
    (when threads (.setEventLoopPoolSize opts (int threads)))
    (when on-error (.exceptionHandler (vu/fn->handler on-error)))
    opts))
