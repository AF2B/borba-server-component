(ns borba.server.component
  "Integrant component for :server/http.

   Starts and stops a Pedestal/Jetty HTTP server.

   ── EDN configuration ────────────────────────────────────────────────────────

     :server/http
     {:port   #or [#env PORT 8080]
      :host   \"0.0.0.0\"
      :routes #ig/ref :http/routes}"
  (:require [integrant.core :as ig]
            [io.pedestal.http :as http]))

(defmethod ig/init-key :server/http
  [_ {:keys [port host routes]}]
  (let [port   (or port 8080)
        host   (or host "0.0.0.0")
        server (-> {::http/routes routes
                    ::http/type   :jetty
                    ::http/port   port
                    ::http/host   host
                    ::http/join?  false}
                   http/create-server
                   http/start)]
    (println (str "🚀 [server] Started on " host ":" port))
    server))

(defmethod ig/halt-key! :server/http
  [_ server]
  (when server
    (http/stop server)
    (println "🚀 [server] Stopped")))
