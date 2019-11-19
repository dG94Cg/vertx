;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.web
  "High level api for http servers."
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [sieppari.core :as sp]
   [reitit.core :as rt]
   [vertx.http :as vh]
   [vertx.util :as vu])
  (:import
   clojure.lang.IPersistentMap
   clojure.lang.Keyword
   io.vertx.core.Future
   io.vertx.core.Handler
   io.vertx.core.Vertx
   io.vertx.core.buffer.Buffer
   io.vertx.core.http.Cookie
   io.vertx.core.http.HttpServer
   io.vertx.core.http.HttpServerOptions
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse
   io.vertx.ext.web.Route
   io.vertx.ext.web.Router
   io.vertx.ext.web.RoutingContext
   io.vertx.ext.web.handler.BodyHandler
   io.vertx.ext.web.handler.LoggerHandler
   io.vertx.ext.web.handler.ResponseTimeHandler
   io.vertx.ext.web.handler.StaticHandler))

;; --- Public Api

(s/def ::wrap-handler
  (s/or :fn fn?
        :vec (s/every fn? :kind vector?)))

(defn- ->request
  [^RoutingContext routing-context]
  (let [^HttpServerRequest request (.request ^RoutingContext routing-context)
        ^HttpServerResponse response (.response ^RoutingContext routing-context)
        ^Vertx system (.vertx routing-context)]
    {:body (.getBody routing-context)
     :path (.path request)
     :headers (vh/->headers request)
     :method (-> request .rawMethod .toLowerCase keyword)
     ::request request
     ::response response
     ::execution-context (.getContext system)
     ::routing-context routing-context}))

(defn handler
  "Wraps a user defined funcion based handler into a vertx-web aware
  handler (with support for multipart uploads.

  If the handler is a vector, the sieppari intercerptos engine will be used
  to resolve the execution of the interceptors + handler."
  [vsm & handlers]
  (let [^Vertx vsm (vu/resolve-system vsm)
        ^Router router (Router/router vsm)]
    (reduce #(%2 %1) router handlers)))

(defn assets
  ([path] (assets path {}))
  ([path {:keys [root] :or {root "public"} :as options}]
   (fn [^Router router]
     (let [^Route route (.route router path)
           ^Handler handler (doto (StaticHandler/create)
                              (.setWebRoot root)
                              (.setDirectoryListing true))]
       (.handler route handler)
       router))))

(defn- default-handler
  [ctx]
  (if (::match ctx)
    {:status 405}
    {:status 404}))

(defn- run-chain
  [ctx chain handler]
  (let [d (p/deferred)]
    (sp/execute (conj chain handler) ctx #(p/resolve! d %) #(p/reject! d %))
    d))

(defn- router-handler
  [router {:keys [path method] :as ctx}]
  (let [{:keys [data path-params] :as match} (rt/match-by-path router path)
        handler-fn (or (get data method)
                       (get data :all)
                       default-handler)
        interceptors (get data :interceptors)
        ctx (assoc ctx ::match match :path-params path-params)]
    (if (empty? interceptors)
      (handler-fn ctx)
      (run-chain ctx interceptors handler-fn))))

(defn router
  ([routes] (router routes {}))
  ([routes {:keys [delete-uploads?
                   upload-dir
                   log-requests?
                   time-response?]
            :or {delete-uploads? true
                 upload-dir "/tmp/vertx.uploads"
                 log-requests? false
                 time-response? true}
            :as options}]
   (let [rtr (rt/router routes options)
         f #(router-handler rtr %)]
     (fn [^Router router]
       (let [^Route route (.route router)]
         (when time-response? (.handler route (ResponseTimeHandler/create)))
         (when log-requests? (.handler route (LoggerHandler/create)))
         (.handler route (doto (BodyHandler/create true)
                           (.setDeleteUploadedFilesOnEnd delete-uploads?)
                           (.setUploadsDirectory upload-dir)))
         (.handler route (reify Handler
                           (handle [_ context]
                             (let [req (->request context)]
                               (try
                                 (-> (vh/-handle-response (f req) req)
                                     (p/catch' #(.fail ^RoutingContext (::routing-context req) %)))
                                 (catch Exception cause
                                   (.fail ^RoutingContext (::routing-context req)))))))))
       router))))
