# borba-server-component

[![CI](https://github.com/AF2B/borba-server-component/actions/workflows/ci.yml/badge.svg)](https://github.com/AF2B/borba-server-component/actions/workflows/ci.yml)

The HTTP server of a Borba service: [Pedestal](https://pedestal.io) on Jetty, run by an [Integrant](https://github.com/weavejester/integrant)
component. It speaks HTTP/1.1, takes a port from the environment or a free one from the system, and on a stop it stops listening at
once and lets the requests in flight finish.

## Install

```clojure
io.github.af2b/borba-server-component
{:git/url "https://github.com/AF2B/borba-server-component"
 :git/tag "v2.0.0"
 :git/sha "<the commit of the tag, printed in the release notes>"}
```

It depends on Clojure, Integrant, `tools.logging`, and Pedestal's service and Jetty modules. It does not choose a logging backend.

## Use

```clojure
{:service/namespaces [borba.server.component]

 :ig/system
 {:server/http
  {:host            "0.0.0.0"
   :port            #long #or [#env PORT 8080]
   :routes          #ig/ref :http/routes
   :stop-timeout-ms 10000}}}
```

`:routes` is what `borba-routes-component` builds. The component is registered by the namespace `borba.server.component`, which goes
under `:service/namespaces` so that `borba-core-component` loads it before the system starts.

| Option | What it is | Default |
|---|---|---|
| `:host` | The address to listen on | `"0.0.0.0"` |
| `:port` | The port to listen on; `0` is any free one | `8080` |
| `:routes` | The routes that `:http/routes` builds | required |
| `:interceptors` | Pedestal interceptors to run before the router, for every request | none |
| `:stop-timeout-ms` | How long a stop waits for the requests in flight | `10000` |
| `:max-threads` | The size of the thread pool of Jetty | Pedestal's |
| `:container-options` | More options for Pedestal's Jetty adapter, such as `:h2c?` and `:configurator` | none |

From the environment the port is a string: `#long #or [#env PORT 8080]` makes it the integer the server needs, and a port that is
not one fails the start saying what it got.

The value of the component is a map of `:connector`, `:server` (the Jetty `Server`), `:host` and `:port`. The `:port` is the one the
server really listens on, so a test starts it on port `0` and reads where it is:

```clojure
(def system (ig/init {:server/http {:host "127.0.0.1" :port 0 :routes routes}}))

(:port (:server/http system))
;; => 41829
```

## Stopping

On a halt the server stops listening at once, so the load balancer is refused and tries another instance, and it waits, up to
`:stop-timeout-ms`, for the requests that are in flight. Jetty's own stop cuts them off. Run with `borba-core-component`, the whole
sequence on a `SIGTERM` is, from a service whose `/readyz` reports the lifecycle and which has a request that takes 2.5 seconds
under way:

```
GET /readyz                                       -> 200 {"status":"ready"}

SIGTERM
borba.core    demo is draining for 1500 ms
GET /readyz                                       -> 503 {"status":"draining"}        (the server still answers)
borba.core    demo stopped after 2 s
borba.server  server on 127.0.0.1:18080 is stopping, waiting up to 5000 ms for requests
GET /slow 200 2501 ms                             (the request in flight finishes)
borba.server  server stopped after 508 ms
borba.core    system stopped
```

The readiness goes false first and the service waits for the load balancer to notice, and only then does the server stop: no
request is refused for a service that is still reported as ready, and none that was accepted is cut off.

A request that outlasts `:stop-timeout-ms` is closed, a warning says so, and the stop goes on, so the components after the server
still halt. A server that cannot listen, because the port is taken, fails the start with the address, and releases what it took:

```clojure
(ig/init {:server/http {:host "127.0.0.1" :port 41829 :routes routes}})
;; throws the ExceptionInfo of Integrant, "Error on key :server/http when building system",
;; whose cause (ex-cause) is
;;   "the server cannot listen on 127.0.0.1:41829"
;;   {:error :borba.server.component/cannot-listen, :host "127.0.0.1", :port 41829}
```

## HTTP/1.1, and no cleartext HTTP/2 upgrade

Pedestal's Jetty accepts a request that upgrades a cleartext connection to HTTP/2 (`Upgrade: h2c`) unless it is told not to. A
proxy that does not look at that header can be walked around by a client that asks for the upgrade, and a request that is read in
two ways by two parties is how smuggling starts. The server turns it off. When the service is behind something that speaks HTTP/2
cleartext on purpose, turn it on:

```clojure
:server/http {:container-options {:h2c? true} ...}
```

## What no route matches

A request that nothing answers is a 404 with Pedestal's plain text body. `borba-routes-component` sends everything that no route
matches to a handler of `borba-handlers-component`, which answers it with the typed 404 and a request id, so this is only the net
under it.

## What is checked

The options are checked when the system starts, and a failure names the option, what it got and what it expects:

| `:error` | When |
|---|---|
| `::invalid-option` | The host, the port (an integer from 0 to 65535), the routes, the interceptors (a vector), the stop timeout (zero or more), the number of threads (positive) or the container options (a map) are not valid |
| `::cannot-listen` | The server could not bind the address (`:host` and `:port` in the data, the cause attached) |

## API

| Name | What it does |
|---|---|
| `borba.server.component/build` | Builds a server that is not listening yet, as a map of `:connector` and `:server` |
| `:server/http` | The Integrant key that builds, starts and stops it |

## Design notes

- **A stop is a drain.** The stop timeout is set on the Jetty server, so a stop that comes through Integrant, through the shutdown hook
  of `borba-core-component` or through `stop-connector!` all wait for the requests that are in flight.
- **The port is data.** It is an integer, and `0` is a valid one, so the tests of a service start a real server on a free port and
  never collide with another.
- **The server does not know the routes.** It takes a routing fragment and some interceptors, which is why a different set of routes
  or a stack of interceptors needs no change here.

## Development

```bash
make check      # lint, format, conventions, reflection, tests, coverage
make ci         # everything the pipelines enforce
```

See [CONTRIBUTING.md](CONTRIBUTING.md). The repository follows the [Borba standard](https://github.com/AF2B/borba-tooling/blob/main/docs/standard.md).

## License

[MIT](LICENSE)
