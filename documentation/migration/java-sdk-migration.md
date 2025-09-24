# Producer-side (client) — keep/move into this repo

### 1) Annotations (producer/emit side)

**Live in:** `obsinity-collection-api`
**Packages:** `com.obsinity.collection.api.annotations`
**Classes:**

* `@Flow`, `@Step`, `@Kind`
* `@PushAttribute`, `@PushContextValue`
* (Optional) `@OrphanAlert` if you still expose that control to emitters

> Notes
>
> * These are pure API (no Spring/AOP types inside).
> * Keep JavaDoc here and avoid runtime dependencies.

---

### 2) Minimal runtime for emitting events

**Move to:** `obsinity-client-core`
**Packages:** `com.obsinity.client.core`
**Classes:**

* `TelemetryContext` (producer thread-local for attributes/context)
* `ObsinityClient` (the tiny emitter that serializes and calls the transport)
* Any **lightweight DTOs** strictly needed to build the on‑wire JSON (if you keep DTOs; you can also inline maps as you do now)

> Notes
>
> * Avoid Spring dependencies here.
> * If you previously had `TelemetryProcessor`/`TelemetryProcessorSupport` doing step/flow lifecycle and batching, **shrink** that to the minimum needed for a synchronous emit path (your current `ObsinityClient` + `TelemetryContext` pattern).
> * Anything AOP/Spring-specific should *not* live in `core` (see Spring section below).

---

### 3) Transport Service Provider Interface

**Move to:** `obsinity-client-transport-spi`
**Packages:** `com.obsinity.client.transport`
**Classes:**

* `EventSender` (already present)
* Any minimal shared helpers/constants for endpoint discovery, headers, etc.

> Notes
>
> * SPI must not depend on HTTP clients—keep it clean.
> * Shared constants like `OBSINITY_INGEST_URL` already exist here.

---

### 4) Concrete HTTP transports

**Move to the corresponding transport modules:**

* Apache HttpClient 5 → `obsinity-client-transport-apache`
* OkHttp → `obsinity-client-transport-okhttp`
* JDK `HttpClient` → `obsinity-client-transport-jdkhttp`
* Spring `RestTemplate` → `obsinity-client-transport-resttemplate`
* Spring `WebClient` → `obsinity-client-transport-webclient`

**Packages:** `com.obsinity.client.transport.<apache|okhttp|jdkhttp|resttemplate|webclient>`
**Classes:** `*EventSender` implementations (already stubbed in your zip)

> Notes
>
> * Each module depends on **SPI** and its specific HTTP lib.
> * Keep all retry/backoff/auth logic in each transport; do not leak HTTP client types into API/Core.

---

### 5) Logging sink / dev sink

**Move to:** `obsinity-client-logging-sink`
**Packages:** `com.obsinity.client.sink`
**Classes:** `StdoutEventSender` (already present)

> Notes
>
> * Useful for local dev; depends on SPI only.

---

### 6) Test utilities

**Move to:** `obsinity-client-testkit`
**Packages:** `com.obsinity.client.testkit`
**Classes:** `InMemoryEventSender`, fake clocks, fixtures

> Notes
>
> * Keep this zero‑dependency besides SPI and JUnit/assert libs.

---

### 7) Reference apps

**Move to:** `obsinity-reference-client`, `obsinity-reference-client-spring`
**Classes:** `RefClientMain`, `RefSpringClientMain`, small demos showing annotation usage + a chosen transport.

> Notes
>
> * Keep these small and runnable; they are your smoke tests and docs.

---

# Do **not** bring into the client repo (consumer/server side)

These belong in a **separate processing/receiver** repo (e.g., `obsinity-server` or `obsinity-processor`), not in this client SDK:

* **Handler-side annotations and dispatching**
  `@OnEvent`, `@OnEveryEvent`, `@OnUnMatchedEvent`, `@TelemetryEventHandler`, `@OnFlow*`, `@OnFlowNotMatched`, wildcard/prefix resolution, etc.

* **Event routing/dispatch/validation**
  `TelemetryEventHandlerScanner`, `TelemetryDispatchBus`, `HandlerScopeValidator`, handler grouping/registries, dot‑chop prefix matchers, lifecycle mode matrices, etc.

* **Consumer parameter binding**
  `@PullAttribute`, `@PullContextValue` and their binding logic (reflection/adapters), throwable filters, error mode routing (`SUCCESS/FAILURE/COMBINED`), etc.

* **Receiver sinks & persistence**
  Anything that reads events and pushes into time-series, OTEL bridging on the **consumer** side, fallback handlers, wildcard coverage tests.

> Rationale
>
> * The client SDK should have a tiny surface to **publish** events.
> * All “receive/handle/route/process” logic is server/consumer territory and shouldn’t leak into client artifacts or dependencies.

---

# Optional (nice-to-have) splits

* **Spring Boot autoconfiguration for client**
  If you had Spring AOP that auto-starts flows from `@Flow` methods, put it in a **new** module (not in core):
  `obsinity-client-spring-boot-starter`

    * Auto-configure: `ObsinityClient`, pick a default `EventSender` from classpath, bind properties (endpoint, apiKey).
    * (Only if you keep it) lightweight AOP that around‑advises `@Flow`/`@Step`.
    * Keep zero logic in `core`.

* **Wire/protocol module (shared DTOs)**
  If you later standardize a formal event schema, create `obsinity-wire` (no HTTP deps) with the JSON schema/DTOs used by both client and server. Today your `ObsinityClient` writes minimal maps, which is fine to start.

---

# Concrete move list (typical original packages → new destinations)

| Original package/class                                         | New module                                   | New package suggestion                       |
| -------------------------------------------------------------- | -------------------------------------------- | -------------------------------------------- |
| `com.obsinity.telemetry.annotations.Flow/Step/Kind`            | `obsinity-collection-api`                    | `com.obsinity.collection.api.annotations`    |
| `com.obsinity.telemetry.annotations.PushAttribute/PushContext` | `obsinity-collection-api`                    | `com.obsinity.collection.api.annotations`    |
| `com.obsinity.telemetry.processor.TelemetryContext`            | `obsinity-client-core`                       | `com.obsinity.client.core`                   |
| `com.obsinity.telemetry.processor.TelemetryProcessor*` (emit)  | **Trimmed** into `obsinity-client-core`      | `com.obsinity.client.core`                   |
| `com.obsinity.telemetry.transport.EventSender`                 | `obsinity-client-transport-spi`              | `com.obsinity.client.transport`              |
| `*Apache*EventSender`                                          | `obsinity-client-transport-apache`           | `com.obsinity.client.transport.apache`       |
| `*OkHttp*EventSender`                                          | `obsinity-client-transport-okhttp`           | `com.obsinity.client.transport.okhttp`       |
| `*JdkHttp*EventSender`                                         | `obsinity-client-transport-jdkhttp`          | `com.obsinity.client.transport.jdkhttp`      |
| `*RestTemplate*EventSender`                                    | `obsinity-client-transport-resttemplate`     | `com.obsinity.client.transport.resttemplate` |
| `*WebClient*EventSender`                                       | `obsinity-client-transport-webclient`        | `com.obsinity.client.transport.webclient`    |
| `StdoutEventSender`                                            | `obsinity-client-logging-sink`               | `com.obsinity.client.sink`                   |
| `InMemoryEventSender`, fakes                                   | `obsinity-client-testkit`                    | `com.obsinity.client.testkit`                |
| **Receiver/handler side** (`@OnEvent`, scanners, dispatch, …)  | **Not in this repo** (server/processor repo) | —                                            |

---

# Package renames & API polish

* Rename packages from `com.obsinity.telemetry.*` → `com.obsinity.client.*` on the **producer** side.
* Keep **annotations** dependency‑free (no Spring types).
* Keep **core** free of HTTP libs and Spring.
* All HTTP client code lives only in transport modules.
* `obsinity-bom` should manage versions of: Jackson, OTEL API (if referenced), and each HTTP client used by transports.

---

# Quick migration checklist

1. Move producer annotations → `obsinity-collection-api`.
2. Extract the minimal emit runtime (`TelemetryContext`, `ObsinityClient`) → `obsinity-client-core`.
3. Keep/expand `EventSender` SPI; move each HTTP implementation into its transport module.
4. Put `StdoutEventSender` into logging-sink; `InMemoryEventSender` into testkit.
5. Exclude all receiver/handler/dispatch classes from this repo; create/keep a separate **server** repo for them.
6. (Optional) Add `obsinity-client-spring-boot-starter` if you want autoconfig/AOP for @Flow/@Step.
