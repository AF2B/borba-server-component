(ns borba.server.component-test
  (:require
   [borba.server.component :as component]
   [borba.server.logging :as logging]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [integrant.core :as ig]
   [io.pedestal.http.route.definition.table :as table])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpClient$Version HttpRequest HttpResponse
                  HttpResponse$BodyHandlers)
   (java.time Duration)
   (java.util.concurrent CountDownLatch TimeUnit)
   (org.eclipse.jetty.server Server)
   (org.eclipse.jetty.util.thread QueuedThreadPool)))

(set! *warn-on-reflection* true)

(def ^:private localhost "127.0.0.1")
(def ^:private request-timeout (Duration/ofSeconds 10))
(def ^:private latch-wait-seconds 10)

(def ^:private started
  "Counted down by the slow handler when a request has reached it."
  (atom (CountDownLatch. 1)))

(defn- sleep-handler
  "A handler that says it has a request, and answers after a time."
  [milliseconds]
  {:name  :test/sleep
   :enter (fn [ctx]
            (.countDown ^CountDownLatch @started)
            (Thread/sleep (long milliseconds))
            (assoc ctx :response {:status 200 :body "done"}))})

(defn- routes
  "The routes of the tests: /hello, and /slow, which takes a time to answer."
  [slow-milliseconds]
  (table/table-routes
   #{["/hello" :get
      [{:name  :test/hello
        :enter (fn [ctx]
                 (assoc ctx :response {:status 200 :body "hello"}))}]
      :route-name :hello]
     ["/slow" :get [(sleep-handler slow-milliseconds)] :route-name :slow]}))

(defn- config
  "The configuration of a server on a free port, with more options."
  ([]
   (config {}))
  ([more]
   {:server/http (merge {:host   localhost
                         :port   0
                         :routes (routes 0)}
                        more)}))

(defn- get*
  "Sends a GET and returns its status, version, headers and body, or the class
   of what went wrong, as data."
  ([port path]
   (get* port path HttpClient$Version/HTTP_1_1))
  ([port path version]
   (try
     (let [client   (-> (HttpClient/newBuilder)
                        (.version version)
                        (.build))
           request  (-> (HttpRequest/newBuilder
                         (URI. (str "http://" localhost ":" port path)))
                        (.timeout request-timeout)
                        (.build))
           response ^HttpResponse (.send client
                                         request
                                         (HttpResponse$BodyHandlers/ofString))]
       {:status  (.statusCode response)
        :version (.version response)
        :headers (.map (.headers response))
        :body    (.body response)})
     (catch Exception e
       {:failed (class e)}))))

(defn- thrown-data
  "Returns the data of the exception a function throws, or of its cause when
   Integrant wrapped it in the one that names the key."
  [f]
  (try (f)
       nil
       (catch clojure.lang.ExceptionInfo e
         (if (= "integrant.core" (some-> (:reason (ex-data e)) namespace))
           (ex-data (ex-cause e))
           (ex-data e)))))

(defn- port-of
  [system]
  (:port (:server/http system)))

(deftest serving-test
  (let [system (ig/init (config))
        port   (port-of system)]
    (try
      (testing "listens on a free port when told port 0, and says which"
        (is (pos? port)))

      (testing "serves the routes"
        (let [response (get* port "/hello")]
          (is (= 200 (:status response)))
          (is (= "hello" (:body response)))))

      (testing "has no route for what is not one"
        (is (= 404 (:status (get* port "/nowhere")))))
      (finally
        (ig/halt! system)))

    (testing "stops listening when it halts"
      (is (= java.net.ConnectException (:failed (get* port "/hello")))))))

(deftest value-test
  (let [system (ig/init (config))]
    (try
      (let [value (:server/http system)]
        (is (= localhost (:host value)))
        (is (instance? Server (:server value)))
        (is (some? (:connector value))))
      (finally
        (ig/halt! system)))))

(deftest http-version-test
  (testing "does not accept an upgrade to cleartext HTTP/2 unless told to"
    (let [system (ig/init (config))]
      (try
        (is (= HttpClient$Version/HTTP_1_1
               (:version (get* (port-of system)
                               "/hello"
                               HttpClient$Version/HTTP_2))))
        (finally
          (ig/halt! system)))))

  (testing "accepts it when told to"
    (let [system (ig/init (config {:container-options {:h2c? true}}))]
      (try
        (is (= HttpClient$Version/HTTP_2
               (:version (get* (port-of system)
                               "/hello"
                               HttpClient$Version/HTTP_2))))
        (finally
          (ig/halt! system))))))

(deftest interceptors-test
  (testing "runs the interceptors before the router, for every request"
    (let [stamp  {:name  :test/stamp
                  :leave (fn [ctx]
                           (assoc-in ctx
                                     [:response :headers "X-Stamp"]
                                     "yes"))}
          system (ig/init (config {:interceptors [stamp]}))]
      (try
        (is (= ["yes"]
               (get (:headers (get* (port-of system) "/hello")) "x-stamp")))
        (finally
          (ig/halt! system))))))

(deftest container-options-test
  (testing "calls the configurator of the caller with the Jetty server"
    (let [seen   (atom nil)
          system (ig/init
                  (config {:container-options
                           {:configurator (fn [server]
                                            (reset! seen server)
                                            server)}}))]
      (try
        (is (identical? @seen (:server (:server/http system))))
        (finally
          (ig/halt! system)))))

  (testing "sets the size of the pool"
    (let [system (ig/init (config {:max-threads 12}))]
      (try
        (is (= 12 (.getMaxThreads
                   ^QueuedThreadPool
                   (.getThreadPool ^Server (:server (:server/http system))))))
        (finally
          (ig/halt! system))))))

(deftest graceful-stop-test
  (testing "lets a request in flight finish, and stops listening at once"
    (reset! started (CountDownLatch. 1))
    (let [system    (ig/init (config {:routes          (routes 800)
                                      :stop-timeout-ms 10000}))
          port      (port-of system)
          in-flight (future (get* port "/slow"))]
      (is (.await ^CountDownLatch @started
                  latch-wait-seconds
                  TimeUnit/SECONDS))
      (ig/halt! system)
      (is (= {:status 200 :body "done"}
             (select-keys @in-flight [:status :body])))
      (is (= java.net.ConnectException (:failed (get* port "/hello"))))))

  (testing "does not wait longer than the stop timeout"
    (reset! started (CountDownLatch. 1))
    (let [system    (ig/init (config {:routes          (routes 4000)
                                      :stop-timeout-ms 100}))
          port      (port-of system)
          in-flight (future (get* port "/slow"))]
      (is (.await ^CountDownLatch @started
                  latch-wait-seconds
                  TimeUnit/SECONDS))
      (let [stopping-at (System/nanoTime)]
        (ig/halt! system)
        (is (< (/ (- (System/nanoTime) stopping-at) 1e6) 3000)))
      (is (not= 200 (:status @in-flight)))
      (is (= java.net.ConnectException (:failed (get* port "/hello")))))))

(deftest startup-failure-test
  (testing "fails naming the address when it cannot listen, and releases what
            it took"
    (let [first-system (ig/init (config))
          port         (port-of first-system)]
      (try
        (let [data (thrown-data #(ig/init (config {:port port})))]
          (is (= {:error ::component/cannot-listen}
                 (select-keys data [:error])))
          (is (= localhost (:host data)))
          (is (= port (:port data))))
        (finally
          (ig/halt! first-system))))))

(deftest invalid-options-test
  (let [invalid (fn [more]
                  (let [data (thrown-data
                              #(component/build
                                (merge {:routes (routes 0)} more)))]
                    [(:error data) (:option data)]))]
    (testing "the host is a string that is not empty"
      (is (= [:borba.server.component/invalid-option :host]
             (invalid {:host ""})))
      (is (= [:borba.server.component/invalid-option :host]
             (invalid {:host 8080}))))

    (testing "the port is an integer, from 0 to 65535"
      (doseq [port [-1 65536 "8080" 80.5 :http]]
        (is (= [:borba.server.component/invalid-option :port]
               (invalid {:port port})))))

    (testing "the routes are the ones the routes component builds"
      (is (= [:borba.server.component/invalid-option :routes]
             (invalid {:routes nil})))
      (is (= [:borba.server.component/invalid-option :routes]
             (invalid {:routes "/hello"}))))

    (testing "the interceptors are a vector"
      (is (= [:borba.server.component/invalid-option :interceptors]
             (invalid {:interceptors :stamp}))))

    (testing "the stop timeout is zero or more milliseconds"
      (doseq [stop-timeout [-1 1.5 "10s"]]
        (is (= [:borba.server.component/invalid-option :stop-timeout-ms]
               (invalid {:stop-timeout-ms stop-timeout})))))

    (testing "the number of threads is positive, or not given"
      (doseq [threads [0 -3 "many"]]
        (is (= [:borba.server.component/invalid-option :max-threads]
               (invalid {:max-threads threads})))))

    (testing "the container options are a map"
      (is (= [:borba.server.component/invalid-option :container-options]
             (invalid {:container-options [:h2c? true]}))))

    (testing "says what was given and what is expected"
      (let [message (try (component/build {:routes (routes 0) :port "8080"})
                         (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (str/includes? message "\"8080\""))
        (is (str/includes? message "#long #or [#env PORT 8080]"))))))

(deftest logging-test
  (testing "says where it listens, and how long it took to stop"
    (let [system  (atom nil)
          entries (logging/call-capturing
                   (fn []
                     (reset! system (ig/init (config)))
                     (ig/halt! @system)))
          messages (logging/messages entries)]
      (is (re-matches #"server listening on 127\.0\.0\.1:\d+"
                      (first messages)))
      (is (re-matches (re-pattern (str "server on 127\\.0\\.0\\.1:\\d+ is "
                                       "stopping, waiting up to 10000 ms "
                                       "for requests"))
                      (second messages)))
      (is (re-matches #"server stopped after \d+ ms" (last messages))))))
