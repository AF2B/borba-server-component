(ns borba.server.component
  (:require [integrant.core :as ig]
             [io.pedestal.http :as http]
             [io.pedestal.http.route :as route]))
  
  (defmethod ig/init-key :server/http
    [_ {:keys [port host routes]}]
    (let [service {:env          :prod
                   ::http/routes (route/expand-routes routes)
                   ::http/type   :jetty
                   ::http/port   port
                   ::http/host   host
                   ::http/join?  false}
  
          server (-> service
                     http/create-server
                     http/start)]
      server))
  
  (defmethod ig/halt-key! :server/http
    [_ server]
    (http/stop server))

