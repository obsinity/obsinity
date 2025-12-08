Collection SDK (Client-Side Only)

Overview
- Goal: Collect application telemetry (annotations + AOP) and dispatch to pluggable sinks.
- Transport-agnostic core; sinks integrate with logging, Obsinity ingest, or other backends.
- The AOP-based experience requires Spring. `obsinity-collection-spring` supplies the aspect, filters, and auto-configuration; if you need to use the core types without Spring you must wire flows and sinks manually.

Modules
- obsinity-collection-api: Public annotations used by apps.
- obsinity-collection-core: Minimal event model (OEvent), thread-local FlowContext, DispatchBus, DispatchBus (FlowSink), processor.
- obsinity-collection-spring: Spring Boot autoconfig + AOP aspect (@Flow) that emits lifecycle events.
- obsinity-collection-sink-logging: Logs OEvent via SLF4J; enabled by default (toggle property).
- obsinity-collection-sink-obsinity: Adapts OEvent to Obsinity REST ingest JSON and posts via EventSender.
- obsinity-client-transport-* modules: HTTP transport SPI implementations (`EventSender`) for WebClient, OkHttp, RestTemplate, and the JDK HttpClient, plus `obsinity-client-transport-rabbitmq` for AMQP delivery.
- obsinity-client-testkit: `InMemoryEventSender` for tests and smoke harnesses.

## Instrumentation Building Blocks

- `@Flow` wraps a method in the `FlowAspect`, producing a `FlowEvent` that carries attributes, context, trace metadata, and runtime status. Nested `@Flow` methods form parent/child flows. Each root flow also records a batch so sinks can emit correlated events.
- `@Step` emits nested step events inside the active flow and records them under `events[]`. If a `@Step` executes without an active flow it is auto-promoted to a flow; use `@OrphanAlert` to set the log level for that promotion.
- Attribute + context pushers: `@PushAttribute`, `@PushContextValue`, and their `@Pull*` counterparts enrich or consume the flow payload. `@PullAttribute`, `@PullAllAttributes`, `@PullContextValue`, and `@PullAllContextValues` bind values back into sink handlers.
- Flow sinks (`@FlowSink` beans) subscribe to lifecycle callbacks using `@OnFlowStarted`, `@OnFlowCompleted`, `@OnFlowFailure`, `@OnOutcome`, `@OnFlowScope`, and `@RequiredAttributes`. `FlowObsinitySink` and the logging sink are both built with the same hook annotations you can use.
- `FlowProcessorSupport` maintains a thread-local stack and event batch per root flow. `FlowContext` is cleared after every method exit so async hand-offs do not leak attributes.

### Hibernate entity validation (safe defaults)

By default the Spring starter scans attributes/context for Hibernate/JPA entities and throws on the first match (to avoid LazyInitializationException, proxies, and memory blow-ups). You can tune or disable it:

```yaml
# application.yml
obsinity:
  collection:
    validation:
      hibernate-entity-check:
        enabled: true    # default true; set false to skip the check entirely
        log-level: ERROR # ERROR (throws), WARN (log + continue), INFO (log + continue)
```

Quick Start (Spring)
1) Add dependencies (choose a transport; WebClient shown):
   - com.obsinity:obsinity-collection-api
   - com.obsinity:obsinity-collection-core
   - com.obsinity:obsinity-collection-spring
   - com.obsinity:obsinity-collection-sink-logging (optional, default on)
   - com.obsinity:obsinity-collection-sink-obsinity
   - com.obsinity:obsinity-client-transport-webclient

2) Annotate code:
   - @Flow(name = "checkout") on methods
   - @PushAttribute("user.id"), @PushContextValue("cart.size") on parameters

   Optional consumer side: create beans annotated with `@FlowSink` and scoped with `@OnFlowScope`/lifecycle annotations to react to emitted events.

3) Configure service and endpoint:
   - obsinity.collection.service = payments (system property) or OBSINITY_SERVICE env
   - obsinity.ingest.url (optional; defaults to http://localhost:8086/events/publish and auto-detects Docker host IP when containerized)
     - system property: obsinity.ingest.url
     - environment variable: OBSINITY_INGEST_URL
   - obsinity.collection.logging.enabled = true|false
   - obsinity.collection.obsinity.enabled = true|false

Obsinity determines the HTTP endpoint in this order:
1. `obsinity.ingest.url` system property
2. `OBSINITY_INGEST_URL` environment variable
3. Auto-detected `http://<host.docker.internal or default gateway>:8086/events/publish` when running in a container
4. Hard-coded fallback `http://localhost:8086/events/publish`

The service identifier on outbound payloads is resolved from:
1. `FlowEvent.serviceId()` if you set it programmatically
2. `resource.service.name` inside the event attributes
3. `obsinity.collection.service` system property or `OBSINITY_SERVICE` environment variable
4. Failure (the sink throws if no service name can be determined)

### RabbitMQ emitter

When you include `obsinity-client-transport-rabbitmq`, FlowObsinitySink can publish completed flow events directly to an AMQP exchange instead of HTTP. Configuration is handled via system properties or environment variables:

| System property | Environment variable | Default |
| --------------- | -------------------- | ------- |
| `obsinity.rmq.host` | `OBSINITY_RMQ_HOST` | `localhost` |
| `obsinity.rmq.port` | `OBSINITY_RMQ_PORT` | `5672` |
| `obsinity.rmq.username` | `OBSINITY_RMQ_USERNAME` | `guest` |
| `obsinity.rmq.password` | `OBSINITY_RMQ_PASSWORD` | `guest` |
| `obsinity.rmq.vhost` | `OBSINITY_RMQ_VHOST` | `/` |
| `obsinity.rmq.exchange` | `OBSINITY_RMQ_EXCHANGE` | `obsinity.events` |
| `obsinity.rmq.routing-key` | `OBSINITY_RMQ_ROUTING_KEY` | `flows` |
| `obsinity.rmq.mandatory` | `OBSINITY_RMQ_MANDATORY` | `false` |

Pair the emitter with `obsinity-ingest-rabbitmq` (or your own worker) to process the canonical payload from a queue.

What gets sent to Obsinity
- The Obsinity sink listens to completed/failed lifecycles only, forces an `endedAt` timestamp if one is missing, and converts the terminal `FlowEvent` (plus any nested `events[]` recorded via `@Step`) into the REST body accepted by `UnifiedPublishController`. Minimal fields below; optional richer fields are included when present (event.kind, trace, status, time, resource.telemetry, host, cloud).

  {
    "time": {
      "startedAt": "2025-09-21T19:40:00Z",
      "endedAt": "2025-09-21T19:40:00.123Z",
      "startUnixNano": 1726665303120000000,
      "endUnixNano": 1726665303243000000,
      "elapsedNanos": 123000000
    },
    "resource": {
      "service": { "name": "payments", "namespace": "core", "version": "1.42.0", "instance": {"id": "p-1"} },
      "telemetry": { "sdk": { "name": "obsinity-java", "version": "0.9.0" } },
      "host": { "name": "ip-10-0-3-15" },
      "cloud": { "provider": "aws", "region": "eu-west-1" },
      "context": { ... }
    },
    "event": { "name": "checkout", "kind": "SERVER" },
    "trace": { "traceId": "...", "spanId": "...", "parentSpanId": "..." },
    "status": { "code": "OK", "message": "HTTP 200" },
    "attributes": { "user.id": "alice" },
    "elapsedNanos": 123000000,
    "return": "OK"
  }

- Name suffixes produced by the aspect (:started|:completed|:failed) are stripped, and a status attribute is added if not present.

Reference Demo
- obsinity-reference-client-spring demonstrates:
  - @Flow + @Push* annotations on methods
  - Logging and Obsinity sinks via Boot autoconfig
  - Auto-created EventSender (WebClient/OkHttp/JDK) depending on classpath

Builder helpers
- OEvent.builder()
  - startedAt(Instant), endedAt(Instant), startUnixNano(long), endUnixNano(long)
  - elapsedNanos(Long) — optional override; normally computed when `endedAt` is set
  - name(String), kind(String)
  - `events[]` may contain nested steps; each entry includes its own `time` object and `status` block when available.
  - trace(traceId, spanId, parentSpanId, state)
  - serviceName(String), serviceNamespace(String), serviceVersion(String), serviceInstanceId(String)
  - telemetrySdk(name, version), hostName(String), cloud(provider, region)
  - attributes(Map), resourceContext(Map), status(code, message)

Notes
- The collection core is independent of Spring; only the aspect + autoconfig live in obsinity-collection-spring.
- To send to different backends, implement EventSink and register it as a bean (Spring) or pass to DispatchBus.

Trace Propagation
- Sources (priority order):
  - FlowContext: context keys traceId, spanId, parentSpanId, tracestate.
  - MDC (SLF4J): keys traceparent (W3C), tracestate, b3 (B3 single), traceId/spanId/parentSpanId, X-B3-TraceId/X-B3-SpanId/X-B3-ParentSpanId.
  - HTTP headers via auto-registered filters (populate FlowContext + MDC):
    - W3C: traceparent, tracestate
    - B3 single header: b3
    - B3 multi headers: X-B3-TraceId, X-B3-SpanId, X-B3-ParentSpanId
- Spring auto-configured filters (enabled by default):
  - Servlet stack: TraceContextFilter (jakarta.servlet.Filter)
  - WebFlux stack: TraceContextWebFilter (org.springframework.web.server.WebFilter)
- Configuration:
  - obsinity.collection.trace.enabled=true|false (default true) — controls filter registration
- Behavior:
  - Filters copy inbound headers to MDC and FlowContext for the request.
  - FlowAspect builds FlowMeta automatically from FlowContext first, then MDC fallback.
  - MDC state is restored and FlowContext is cleared after each request to avoid leaks.
