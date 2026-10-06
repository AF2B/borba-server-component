(ns borba.server.component
  "The Integrant component that runs the HTTP server of a service: Pedestal on
   Jetty.

     :server/http
     {:host            \"0.0.0.0\"
      :port            #long #or [#env PORT 8080]
      :routes          #ig/ref :http/routes
      :stop-timeout-ms 10000}

   It starts when the system starts, and on halt it stops listening at once and
   waits, up to :stop-timeout-ms, for the requests in flight to finish. Stopped
   any sooner, Jetty cuts them off; and when a request outlasts the timeout the
   server closes it, logs that it did, and goes on, so that the rest of the
   system still halts.

   A request that nothing answers is a 404 with Pedestal's plain text body.
   `:http/routes` sends every request that no route matches to a handler with
   a typed body, so this is only the net under it.

   It speaks HTTP/1.1. Pedestal's Jetty also accepts a request that upgrades to
   cleartext HTTP/2 unless told not to, which is how a proxy and a server come
   to read a request in two ways, so that is off here; turn it on with
   `:container-options {:h2c? true}` when the service is behind something that
   speaks it.

   The value is a map of :connector, :server (the Jetty server), :host and
   :port, which is the port the server listens on: with :port 0 the system
   chooses a free one, which is what a test wants."
  (:require
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [io.pedestal.connector :as conn]
   [io.pedestal.http.jetty :as jetty]
   [io.pedestal.http.route :as route]
   [io.pedestal.service.interceptors :as pedestal-interceptors])
  (:import
   (org.eclipse.jetty.server Server ServerConnector)))

(set! *warn-on-reflection* true)

(def default-host
  "The address the server listens on unless told otherwise: all of them."
  "0.0.0.0")

(def default-port
  "The port the server listens on unless told otherwise."
  8080)

(def default-stop-timeout-ms
  "How long a stop waits for the requests in flight, unless told otherwise."
  10000)

(def ^:private max-port 65535)
(def ^:private nanos-per-millisecond 1000000)

(defn- invalid-option
  [option
   value
   expected]
  (ex-info (str ":" (name option) " is " (pr-str value) ", and must be "
                expected)
           {:error  ::invalid-option
            :option option
            :value  value}))

(defn- check-options
  "Fails naming the first option that is not valid."
  [{:keys [host port routes interceptors stop-timeout-ms max-threads
           container-options]}]
  (when-not (and (string? host) (seq host))
    (throw (invalid-option :host host "a non-empty string")))
  (when-not (and (int? port) (<= 0 port max-port))
    (throw (invalid-option
            :port port
            (str "an integer from 0 to " max-port
                 "; from the environment it is #long #or [#env PORT 8080]"))))
  (when-not (satisfies? route/ExpandableRoutes routes)
    (throw (invalid-option :routes routes
                           "the routes that :http/routes builds")))
  (when-not (sequential? interceptors)
    (throw (invalid-option :interceptors interceptors "a vector")))
  (when-not (and (int? stop-timeout-ms) (<= 0 stop-timeout-ms))
    (throw (invalid-option :stop-timeout-ms stop-timeout-ms
                           "a number of milliseconds, zero or more")))
  (when-not (or (nil? max-threads) (and (int? max-threads) (pos? max-threads)))
    (throw (invalid-option :max-threads max-threads
                           "a positive integer, or not given")))
  (when-not (or (nil? container-options) (map? container-options))
    (throw (invalid-option :container-options container-options "a map"))))

(defn- listening-port
  "Returns the port a started Jetty server listens on."
  [^Server server]
  (.getLocalPort ^ServerConnector (first (.getConnectors server))))

(defn- configurator
  "Returns the function Pedestal calls with the Jetty server before it starts.
   It keeps the server, so that its port can be asked once it listens, and sets
   how long a stop waits for the requests in flight."
  [captured
   stop-timeout-ms
   user-configurator]
  (fn [^Server server]
    (reset! captured server)
    (.setStopTimeout server (long stop-timeout-ms))
    (user-configurator server)))

(defn build
  "Builds a server, which is not listening yet, and returns it as a map of
   :connector and :server. Fails naming the option that is not valid.
   - host: the address to listen on (default \"0.0.0.0\")
   - port: the port to listen on, 0 for any free one (default 8080)
   - routes: the routes that `:http/routes` builds
   - interceptors: Pedestal interceptors to run before the router, for every
     request (default none)
   - stop-timeout-ms: how long a stop waits for the requests in flight
     (default 10000)
   - max-threads: the size of the pool of Jetty (default: Pedestal's)
   - container-options: more options for Pedestal's Jetty adapter, such as
     :h2c? or :configurator, which is called with the Jetty server after this
     one is"
  [{:keys [host port routes interceptors stop-timeout-ms max-threads
           container-options]
    :or   {host            default-host
           port            default-port
           interceptors    []
           stop-timeout-ms default-stop-timeout-ms}}]
  (check-options {:host               host
                  :port               port
                  :routes             routes
                  :interceptors       interceptors
                  :stop-timeout-ms    stop-timeout-ms
                  :max-threads        max-threads
                  :container-options  container-options})
  (let [captured  (atom nil)
        user-conf (get container-options :configurator identity)
        options   (cond-> (assoc container-options
                                 :h2c? (get container-options :h2c? false)
                                 :configurator
                                 (configurator captured
                                               stop-timeout-ms
                                               user-conf))
                    max-threads (assoc :max-threads max-threads))
        connector (-> (conn/default-connector-map host port)
                      (conn/with-interceptors
                        (into [pedestal-interceptors/not-found] interceptors))
                      (conn/with-routes routes)
                      (jetty/create-connector {:join?             false
                                               :container-options options}))]
    {:connector connector
     :server    @captured
     :host      host
     :port      port}))

(defn- start!
  "Starts a built server and returns it with the port it listens on. When it
   cannot listen, it releases what it took and fails naming the address."
  [{:keys [connector ^Server server host port] :as built}]
  (try
    (conn/start! connector)
    (assoc built :port (listening-port server))
    (catch Throwable cause
      (try (.stop server) (catch Throwable _ nil))
      (throw (ex-info (str "the server cannot listen on " host ":" port)
                      {:error ::cannot-listen
                       :host  host
                       :port  port}
                      cause)))))

(defmethod ig/init-key :server/http
  [_ options]
  (let [started (start! (build options))]
    (log/infof "server listening on %s:%d" (:host started) (:port started))
    started))

(defmethod ig/halt-key! :server/http
  [_ {:keys [connector ^Server server host port]}]
  (let [timeout-ms (.getStopTimeout server)
        started-at (System/nanoTime)]
    (log/infof "server on %s:%d is stopping, waiting up to %d ms for requests"
               host
               port
               timeout-ms)
    (try
      (conn/stop! connector)
      (catch Throwable cause
        (log/warn cause
                  (format "server on %s:%d did not stop within %d ms; closing"
                          host
                          port
                          timeout-ms))
        (.setStopTimeout server 0)
        (.stop server)))
    (log/infof "server stopped after %d ms"
               (quot (- (System/nanoTime) started-at) nanos-per-millisecond))))
