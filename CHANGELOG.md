# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.0.0] - 2026-10-06

### Added

- Graceful stop: the server stops listening at once and waits, up to `:stop-timeout-ms` (10 seconds by default), for the requests
  in flight, which Jetty used to cut off. A request that outlasts the timeout is closed with a warning, and the stop goes on.
- `:interceptors`, Pedestal interceptors to run before the router, and `:max-threads` and `:container-options`, which reach
  Pedestal's Jetty adapter, `:configurator` of the caller included.
- `:port 0` for a free port, and the port the server really listens on in the value of the component.
- The options are checked when the system starts, and a failure names the option, what it got and what it expects. A server that
  cannot listen fails the start with the address, and releases what it took.
- A net for a request that nothing answers: a 404, where Pedestal's connector gives a 500.
- `borba.server.component/build`, which builds a server that is not listening yet.
- A test suite with 100% of the lines covered, over real HTTP on free ports.

### Changed

- **Breaking:** the value of `:server/http` is a map of `:connector`, `:server`, `:host` and `:port`, where it was the Pedestal
  server map.
- **Breaking:** the cleartext HTTP/2 upgrade (`h2c`), which Pedestal's Jetty accepts by default, is off. Turn it on with
  `:container-options {:h2c? true}`.
- **Breaking:** the `:routes` are a Pedestal routing fragment, the value of `:http/routes` 2, and the server is built with the
  connector API of Pedestal 0.8.2, which replaces `io.pedestal.http/create-server`.
- **Breaking:** moves to Integrant 1.0, where a reference must be a qualified keyword.
- The component logs through `tools.logging` instead of printing, and no longer depends on a logging backend.
- The published library is named `io.github.af2b/borba-server-component`.

### Security

- Pins `jackson-core` to 2.22.3. The 2.21.1 that Pedestal 0.8.2 brings through transit-java has three high advisories
  (GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89 and GHSA-r7wm-3cxj-wff9), which the dependency scan of the pipeline reported.

## [1.0.0] - 2026-03-30

First release: the `:server/http` Integrant component, which starts and stops a Pedestal server on Jetty.

[Unreleased]: https://github.com/AF2B/borba-server-component/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/AF2B/borba-server-component/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/AF2B/borba-server-component/releases/tag/v1.0.0
