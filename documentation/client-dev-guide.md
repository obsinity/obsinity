# Obsinity Client SDK — Developer Guide

Scope
- Practical, end-to-end guide for integrating the client-side event collection SDK in JVM apps.
- Covers modules, dependencies, configuration, Spring and non-Spring usage, transports, testing, and troubleshooting.

---

## Modules Overview

Core client-side pieces (add what you need):
- `obsinity-collection-api` — public annotations (`@Flow`, `@Step`, `@PushAttribute`, `@PushContextValue`, …).
- `obsinity-collection-core` — event model and processor (`OEvent`, `FlowEvent`, `FlowProcessor`).
- `obsinity-collection-spring` — Spring Boot autoconfig + AOP aspect that emits lifecycle events from annotations.
- `obsinity-collection-sink-logging` — logs events via SLF4J; enabled by default (toggleable).
- `obsinity-collection-sink-obsinity` — adapts events to Obsinity’s REST ingest and posts via an `EventSender`.
- `obsinity-client-transport-*` — pluggable HTTP transports for `EventSender` (WebClient, OkHttp, JDK HttpClient, RestTemplate).
- `obsinity-client-testkit` — `InMemoryEventSender` for tests.

Reference demos:
- `obsinity-reference-client-spring` — runnable Spring Boot demo using `@Flow` and the built-in flow sinks.

---

## Add Dependencies

Minimal Spring Boot setup (choose one transport):

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.obsinity</groupId>
      <artifactId>obsinity-bom</artifactId>
      <version>${obsinity.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
 </dependencyManagement>

<dependencies>
  <!-- Collection SDK -->
  <dependency>
    <groupId>com.obsinity</groupId>
    <artifactId>obsinity-collection-api</artifactId>
  </dependency>
  <dependency>
    <groupId>com.obsinity</groupId>
    <artifactId>obsinity-collection-core</artifactId>
  </dependency>
  <dependency>
    <groupId>com.obsinity</groupId>
    <artifactId>obsinity-collection-spring</artifactId>
  </dependency>
  <!-- Flow sinks -->
  <dependency>
    <groupId>com.obsinity</groupId>
    <artifactId>obsinity-collection-sink-logging</artifactId>
  </dependency>
  <dependency>
    <groupId>com.obsinity</groupId>
    <artifactId>obsinity-collection-sink-obsinity</artifactId>
  </dependency>
  <!-- Choose ONE transport (or let auto-config choose by classpath) -->
  <dependency>
    <groupId>com.obsinity</groupId>
    <artifactId>obsinity-client-transport-webclient</artifactId>
  </dependency>
  <!-- or -->
  <dependency>
    <groupId>com.obsinity</groupId>
    <artifactId>obsinity-client-transport-okhttp</artifactId>
  </dependency>
  <!-- or -->
  <dependency>
    <groupId>com.obsinity</groupId>
    <artifactId>obsinity-client-transport-jdkhttp</artifactId>
  </dependency>
  <!-- or -->
  <dependency>
    <groupId>com.obsinity</groupId>
    <artifactId>obsinity-client-transport-resttemplate</artifactId>
  </dependency>
</dependencies>
```

Testing support:

```xml
<dependency>
  <groupId>com.obsinity</groupId>
  <artifactId>obsinity-client-testkit</artifactId>
  <scope>test</scope>
  </dependency>
```

---

## Configuration

Essential properties (Spring Boot `application.properties` or env/system props):
- `obsinity.collection.logging.enabled=true|false` — enable SLF4J logging sink (default true).
- `obsinity.collection.obsinity.enabled=true|false` — enable Obsinity REST sink (default true when an `EventSender` bean exists).
- `obsinity.collection.trace.enabled=true|false` — register inbound trace propagation filters (default true).
- `obsinity.ingest.url` or `OBSINITY_INGEST_URL` — HTTP endpoint for Obsinity ingest; defaults to `http://localhost:8086/events/publish` (auto-resolves Docker host IP when running in a container).

Example:

```properties
# application.properties
obsinity.collection.logging.enabled=true
obsinity.collection.obsinity.enabled=true
# Point to your ingest endpoint (local controller example)
obsinity.ingest.url=http://localhost:8086/events/publish
```

Transports are discovered via classpath. If both WebClient and OkHttp are present, WebClient wins (see `obsinity-reference-client-spring`). If none are present, the JDK HttpClient transport is used. Flow sinks (`@FlowSink`-annotated beans) are auto-detected by `FlowSinkScanner` when you include `obsinity-collection-spring`.

---

## Spring Boot Quick Start

Annotate methods with `@Flow` (and optionally `@Step` inside flows) to emit lifecycle events. Use `@PushAttribute` and `@PushContextValue` to enrich attributes and context.

```java
import com.obsinity.client.core.ObsinityApplication;
import io.opentelemetry.api.trace.SpanKind;

@SpringBootApplication
@ObsinityApplication
class DemoApp {
  public static void main(String[] args) {
    org.springframework.boot.SpringApplication.run(DemoApp.class, args);
  }

  @Bean CommandLineRunner demo(SampleFlows flows) {
    return args -> {
      flows.checkout("alice", 3);
      try { flows.checkoutFail("bob", -1); } catch (RuntimeException ignore) {}
    }; 
  }
}

@Component
class SampleFlows {
  @Flow(name = "demo.checkout")
  @Kind(SpanKind.SERVER)
    void checkout(@PushAttribute("user.id") String userId,
                @PushContextValue("cart.size") int items) {
    // business logic
  }

  @Flow(name = "demo.checkout")
  @Kind(SpanKind.SERVER)   void checkoutFail(@PushAttribute("user.id") String userId,
                    @PushContextValue("cart.size") int items) {
    throw new RuntimeException("boom");
  }
}
```

### Register flow sinks

To react to emitted events, declare Spring beans annotated with `@FlowSink`. Combine `@OnFlowScope` with lifecycle annotations to target specific flows and phases. The auto-configured `FlowSinkScanner` discovers these beans and wires them into the registry.

```java
@FlowSink
@OnFlowScope("demo.")
class DemoAuditSink {

  @OnFlowCompleted
  public void recordCompletion(FlowEvent event) {
    // persist audit entry
  }
}
```

What happens at runtime:
- `FlowAspect` wraps annotated methods and emits `STARTED` → `COMPLETED` (or `FAILED`) events.
- Attributes/context from parameters are merged into the current `FlowEvent`.
- Methods with non-`void` signatures record their return value into the completed `FlowEvent` (`return` field).
- When an end timestamp is recorded the SDK computes `elapsedNanos` (also echoed under `time.elapsedNanos`).
- Flow sinks run on each lifecycle event:
  - Logging sink prints to application logs.
  - Obsinity sink serializes to JSON and posts to `obsinity.ingest.url` via the active `EventSender`.
- If an inbound HTTP request has `traceparent`/`b3` headers, `TraceContextFilter` copies them into MDC so trace IDs propagate into events automatically.

---

 

## Choosing a Transport

`EventSender` implementations (pick one that matches your stack):
- `obsinity-client-transport-webclient` — Spring WebClient.
- `obsinity-client-transport-resttemplate` — Spring RestTemplate.
- `obsinity-client-transport-okhttp` — OkHttp.
- `obsinity-client-transport-jdkhttp` — JDK 11+ `HttpClient` (no external deps).

Endpoint resolution order for all senders:
- System property `obsinity.ingest.url`
- Env var `OBSINITY_INGEST_URL`
- Default `http://localhost:8086/events/publish` (container builds auto-switch to the Docker host IP)

---

## Trace Propagation

If your app handles HTTP requests:
- Servlet stack: `TraceContextFilter` reads `traceparent`/`tracestate` (W3C) or `b3` headers and copies values into MDC.
- WebFlux stack: `TraceContextWebFilter` does the same for reactive pipelines.
- The aspect reads trace IDs from MDC and attaches them to emitted events.
- Toggle with `obsinity.collection.trace.enabled=true|false`.

---

## Testing the Integration

Use `InMemoryEventSender` to capture outbound payloads without doing HTTP calls:

```java
var sender = new com.obsinity.client.testkit.InMemoryEventSender();
var obsinity = new com.obsinity.collection.sink.obsinity.FlowObsinitySink(sender);
// register obsinity sink into your registry…
// run a @Flow method or manual processor calls
// assert on sender.payloads()
```

For Spring Boot tests, keep the logging sink on and assert log lines, or inject a bean of type `EventSender` as the in-memory variant.

---

## Run the Reference Demo

The demo app shows `@Flow` + both built-in sinks working together.

- Run via Maven: `mvn -pl obsinity-reference-client-spring spring-boot:run`
- Or build all then run the reference service + controller ingest:
  - Start DB: `docker compose up -d`
  - Start reference service: `cd obsinity-reference-service && ./build.sh && ./run.sh`

Check logs for `START/DONE/FAIL` lines and verify events arrive at the controller ingest endpoint if enabled.

---

## Troubleshooting

- No HTTP sends happening
  - Ensure a single `EventSender` is on the classpath or provided as a Spring bean.
  - Set `obsinity.collection.obsinity.enabled=true`.
- 4xx/5xx on send
  - Verify `obsinity.ingest.url` and that the ingest service is reachable.
  - Look for exceptions from the chosen transport.
- No log lines
  - Ensure `obsinity.collection.logging.enabled=true` and logging level permits INFO.
- No trace IDs on events
  - Confirm `obsinity.collection.trace.enabled=true` and inbound requests include `traceparent`/`b3`.
- Annotation not triggering
  - Ensure component is managed by Spring, method is proxied (non-final), and `obsinity-collection-spring` is on the classpath.

---

## Notes

- The collection core is transport-agnostic and usable without Spring; the aspect and auto-config live in `obsinity-collection-spring`.
- Resource/service metadata can be added via attributes/context today; richer resource binding may be added in a future revision.
# Obsinity Telemetry Developer Guide

(Aligned to current SDK in this repository. Where your August 2025 ruleset introduces new concepts, notes are included.)

---

## Annotation Reference

### Core (structure & selection)

| Annotation           | Target         | Purpose                                                                                              |
| -------------------- | -------------- | ---------------------------------------------------------------------------------------------------- |
| `@Flow`              | Method         | Starts a flow. May be root or nested. Activates context so nested `@Step` calls become children.     |
| `@Step`              | Method         | Emits a step inside the active flow; auto‑promoted to a flow if none is active.                      |
| `@Kind`              | Method / Class | Sets OTEL SpanKind (`SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL`).                         |
| `@OrphanAlert`       | Method / Class | Controls log level when a `@Step` is auto‑promoted because no active `@Flow` exists.                 |
| `@OnFlowLifecycle`   | Method / Class | Lifecycle selector to receive only a specific phase (`STARTED`, `COMPLETED`, `FAILED`).              |
| `@OnAllLifecycles`   | Method / Class | Shorthand to observe all phases for the annotated sink/handler.                                       |
| `@OnFlowLifecycles`  | Method / Class | Container to specify multiple lifecycles.                                                            |
| `@OnFlowScope`       | Method / Class | Declare name/prefix scope for matching events; supports dot‑chop fallback.                           |

Note: Use class‑level annotations to apply defaults for all handlers within a sink; method‑level annotations further refine.

When a flow contains nested `@Step` calls, the resulting event payload includes a tree of `events[]`, each reusing the same `time` block (`startedAt` / `startUnixNano` / `endedAt` / `endUnixNano`) and, if available, a `status` object mirroring OTEL span status.

### Flow sinks (flow‑centric handlers)

| Annotation          | Target | Purpose                                                                      |
| ------------------- | ------ | ---------------------------------------------------------------------------- |
| `@FlowSink`         | Class  | Marks a bean containing flow/step event handlers.                            |
| `@OnFlowStarted`    | Method | Handle when a flow starts (exact name or prefix via `@OnFlowScope`).         |
| `@OnFlowCompleted`  | Method | Handle when a matched flow finishes (success or failure).                    |
| `@OnFlowFailure`    | Method | Handle only when a matched flow fails.                                       |
| `@OnFlowSuccess`    | Method | Handle only when a matched flow succeeds (non‑root vs root depends on scope).|
| `@OnFlowNotMatched` | Method | Component‑scoped fallback when no other handler in this sink matched.        |

Note: A global fallback annotation (e.g., `@GlobalFlowFallback`) does not exist in this repo.

### Outcome filtering

| Type / Annotation | Target | Purpose                                             |
| ----------------- | ------ |-----------------------------------------------------|
| `enum Outcome`    | —      | `SUCCESS`, `FAILURE`.                               |
| `@OnOutcome`      | Method | Restrict a handler to a single outcome (`SUCCESS` or `FAILURE`).             |

### Attribute & context I/O (producer‑side “push”)

| Annotation          | Target    | Purpose                                                                                                        |
| ------------------- | --------- | -------------------------------------------------------------------------------------------------------------- |
| `@PushAttribute`    | Parameter | Save a method parameter value into attributes (saved on the event). Supports `value`/`name`, `omitIfNull`.     |
| `@PushContextValue` | Parameter | Save a method parameter value into event context (ephemeral).                                                  |

### Parameter binding (consumer‑side “pull”)

| Annotation              | Target    | Purpose                                                                 |
| ----------------------- | --------- | ----------------------------------------------------------------------- |
| `@PullAttribute`        | Parameter | Bind a single attribute key to the parameter (`value`/`name`).                   |
| `@PullAllAttributes`    | Parameter | Bind the entire attributes map.                                                 |
| `@PullContextValue`     | Parameter | Bind a single event context key to the parameter.                                |
| `@PullAllContextValues` | Parameter | Bind the entire event context map.                                              |

Throwable binding: For failure handlers, you can declare a `Throwable` parameter to receive the error. Optionally use `@FlowException` to bind the root cause.

### Preconditions (handler gating)

| Annotation              | Target | Purpose                                                                            |
| ----------------------- | ------ | ---------------------------------------------------------------------------------- |
| `@RequiredAttributes`   | Method | Require one or more attribute keys be present before invoking the handler.         |
| `@RequiredEventContext` | Method | Require one or more event context keys be present before invoking the handler.     |

---

## Selection & Matching (dot‑chop, scope, lifecycle, kind)

- Lifecycle: use `@OnFlowLifecycle`/`@OnAllLifecycles`/`@OnFlowLifecycles` or the specific annotations (`@OnFlowStarted`, `@OnFlowCompleted`, `@OnFlowFailure`, `@OnFlowSuccess`).
- Scope/name: class‑level and method‑level `@OnFlowScope` combine (both must pass). Matching supports exact, prefix (`startsWith`), and dot‑chop fallback.
- Outcomes: use `@OnOutcome(Outcome.SUCCESS|FAILURE)` to narrow finish handlers.

---

## Quick Rules (current SDK)

1) Lifecycles are `STARTED`, `COMPLETED`, `FAILED`.
   - Use `@OnFlowCompleted` for “always at finish”.
   - Use `@OnFlowSuccess` or `@OnFlowFailure` for outcome‑specific handlers.
2) Throwable binding is only allowed for failure handlers; completed handlers with `Throwable` params are ignored.
3) Avoid duplicate handlers with identical selection (scope + lifecycle + outcome + signature).
4) Class scopes refine downwards: class‑level and method‑level filters both apply.
5) Fallbacks: `@OnFlowNotMatched` exists but is not registered; there’s no global fallback.

Note: The August 2025 guide removes a standalone “COMPLETED” concept in favor of pure SUCCESS/FAILURE. This repo still emits `COMPLETED` for success and `FAILED` for failures in `eventContext.lifecycle`.

---

## Examples

### Flow & Steps

```java
import io.opentelemetry.api.trace.SpanKind;

@Flow("checkout.start")                     // Start a checkout flow
@Kind(SpanKind.SERVER)
public void startCheckout(
    @PushAttribute("user.id") String userId,
    @PushContextValue("session.id") String sessionId
) { /* ... */ }

@Step("checkout.reserve")                   // Step within checkout
public void reserveInventory(@PushAttribute("sku") String sku) { /* ... */ }

@Step("checkout.payment")                   // Auto-promoted if no flow is active
@OrphanAlert(OrphanAlert.Level.WARN)
public void processPayment(@PushAttribute("payment.method") String method) { /* ... */ }
```

### Flow sink with lifecycle + outcomes + scope

```java
@FlowSink
@OnFlowScope("checkout")
public class CheckoutSink {

  @OnFlowStarted
  public void onStart(@PullAttribute("user.id") String userId) { /* ... */ }

  @OnFlowCompleted @OnOutcome(Outcome.SUCCESS)
  public void onSuccess(@PullAllAttributes java.util.Map<String,Object> attrs) { /* ... */ }

  @OnFlowCompleted @OnOutcome(Outcome.FAILURE)
  public void onFailure(Throwable ex, @PullAllAttributes java.util.Map<String,Object> attrs) { /* ... */ }
}
```

### Failure binding rules

```java
@FlowSink
public class FailureSpecificSink {

  @OnFlowFailure
  public void onAnyFailure(Throwable t) { /* fallback */ }

  @OnFlowFailure
  public void onRuntime(RuntimeException ex) { /* more specific */ }

  @OnFlowFailure
  public void onIllegalArg(IllegalArgumentException ex) { /* most specific */ }
}
```

If an `IllegalArgumentException` occurs, only `onIllegalArg` is invoked (most specific type wins).

### Guarded sink

```java
@FlowSink
public class GuardedSink {
  @OnFlowStarted
  @RequiredAttributes({"user.id", "amount"})
  public void charge(@PullAttribute("user.id") String uid,
                     @PullAttribute("amount") java.math.BigDecimal amount) { /* ... */ }
}
```

---

## Lifecycle Overview (current SDK)

```
@Flow/@Step entry
  ↓
Save values (push annotations)
  ↓
Emit event
  ↓
Dispatcher: lifecycle filter → scope → name → outcome
  ↓
Flow Started:
   └── @OnFlowStarted
  ↓
Flow Finished:
   ├── SUCCESS
   │     ├─ @OnFlowSuccess
   │     └─ @OnFlowCompleted(+ optional @OnOutcome SUCCESS)
   │
   └── FAILURE
         ├─ @OnFlowFailure (throwable binding allowed)
         └─ @OnFlowCompleted(+ optional @OnOutcome FAILURE)
  ↓
Parameter binding (holders or throwable)
  ↓
Attributes saved; context is ephemeral
```

---

## OTEL SpanKind Reference

| SpanKind   | Use when…                               | Examples                                       |
| ---------- | --------------------------------------- | ---------------------------------------------- |
| `SERVER`   | Handles an incoming request/message      | HTTP controller, gRPC server, message consumer |
| `CLIENT`   | Makes an outgoing request                | HTTP/gRPC client, external API, DB driver      |
| `PRODUCER` | Publishes to a broker/topic/queue        | Kafka/RabbitMQ/SNS send                        |
| `CONSUMER` | Receives/processes a brokered message    | Kafka poll, RabbitMQ listener, SQS handler     |
| `INTERNAL` | Performs in‑process work                 | Cache recompute, rule evaluation, CPU step     |

---

## Spring Boot Quick Start

1) Add dependencies: `obsinity-collection-api`, `obsinity-collection-core`, `obsinity-collection-spring`, at least one sink module (`-receiver-logging`, `-receiver-obsinity`), and one transport (`obsinity-client-transport-*`).
2) Annotate methods with `@Flow` / `@Step` and `@Push*`.
3) Enable telemetry (AOP + auto-config):
   - Preferred: annotate your app with `@ObsinityApplication`.
   - Or add `spring-boot-starter-aop` and enable `@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)` yourself.
4) Configure properties:
   - `obsinity.collection.logging.enabled=true|false`
   - `obsinity.collection.obsinity.enabled=true|false`
   - `obsinity.collection.trace.enabled=true|false`
   - `obsinity.ingest.url` or `OBSINITY_INGEST_URL`
5) Run the demo: `mvn -pl obsinity-reference-client-spring spring-boot:run`.

---

## Configuration

- `obsinity.collection.logging.enabled=true|false` — enable SLF4J logging sink (default true).
- `obsinity.collection.obsinity.enabled=true|false` — enable Obsinity REST sink (default true when an `EventSender` bean exists).
- `obsinity.collection.trace.enabled=true|false` — register inbound trace propagation filters (default true).
- `obsinity.ingest.url` or `OBSINITY_INGEST_URL` — HTTP endpoint for Obsinity ingest (default `http://localhost:8086/events/publish`).

Transports (`EventSender`) available: WebClient, RestTemplate, OkHttp, JDK HttpClient. Endpoint resolution: System property → Env var → Default.

---

## Troubleshooting

- Handler never fires → Check lifecycle filter, scope, name/prefix, outcome, and that the sink bean is scanned.
- Throwable not injected → Only failure handlers may declare `Throwable` parameters.
- Duplicates → Avoid identical selection/signature pairs.
- No HTTP sends → Ensure a single `EventSender` is on classpath or bean provided; set `obsinity.collection.obsinity.enabled=true`.
- No trace IDs → Confirm `obsinity.collection.trace.enabled=true` and inbound headers include `traceparent`/`b3`.
