# Client SDK Patterns (Obsinity)

Obsinity clients are event emitters. The SDK captures flow/step lifecycle data and sends canonical event payloads to the server. Derived metrics (counters, histograms, state transitions) are configured and computed on the server, but the client must emit attributes that make those metrics possible (dimensions, value fields, object ids, and state attributes).

**Core Principles**
- Emit events, not metrics. The SDK never computes counters or histograms.
- Event shape is stable and canonical (resource, event, time, attributes, trace, status, events).
- Pluggable sinks and transports; event capture and event delivery are decoupled.
- Low overhead when disabled (`obsinity.collection.enabled=false`).

**Event Capture Patterns**
- Annotation-driven flows:
- `@Flow` marks a method as a flow root (lifecycle start/complete/fail).
- `@Step` records nested step events; orphan steps auto-promote to flows with a warning.
- `@PushAttribute` and `@PushContextValue` enrich event attributes and context.
- `@Kind` and trace metadata are translated into OTEL-like span fields.
- AOP implementation: `FlowAspect` intercepts annotated methods, manages lifecycle, captures exceptions, and cleans thread-local state.

**Flow Lifecycle Model**
- `FlowProcessor` defines lifecycle hooks (`onFlowStarted`, `onFlowCompleted`, `onFlowFailed`).
- `DefaultFlowProcessor` creates `FlowEvent` instances, merges attributes/context, and dispatches to sinks.
- `FlowProcessorSupport` maintains per-thread flow context and batches completed events.

**Sink & Dispatch Pattern**
- `FlowSink` beans are discovered and compiled by `FlowSinkScanner`.
- `AsyncDispatchBus` routes events to each sink on a dedicated worker thread (per sink) for isolation.
- Built-in sinks:
- `LoggingSink` logs start/complete/fail events.
- `FlowObsinitySink` serializes `FlowEvent` into the unified publish payload and sends via `EventSender`.

**Transport SPI**
- `EventSender` is the minimal interface for payload delivery.
- Implementations live under `obsinity-client-transport-*` (WebClient, OkHttp, JDK HttpClient, RestTemplate, RabbitMQ).
- Endpoint is configured via `obsinity.ingest.url` or `OBSINITY_INGEST_URL`, with container-friendly autodiscovery.

**Resource & Identity Resolution**
- Service identity is derived in `FlowObsinitySink` in this order:
- `FlowEvent.serviceId()`
- `resource.service.name` attribute
- `obsinity.collection.service` / `OBSINITY_SERVICE`
- Missing service id rejects publishing to avoid unlabeled events.

**Trace Propagation**
- Spring MVC and WebFlux filters are provided to capture inbound trace context and populate flow metadata.

**Testing Pattern**
- `obsinity-client-testkit` provides in-memory senders to capture events without IO.

**Implications For Instrumentation**
- Choose attribute keys that align with server configuration (dimensions, value paths, state attributes).
- Keep attributes JSON-serializable (SDK validates to avoid problematic entities).
- Emit consistent flow names; these become event types on the server.
