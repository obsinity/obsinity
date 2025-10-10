# Obsinity

Obsinity is a modular telemetry system with a PostgreSQL backend, REST controllers, and client libraries.

- Build all modules: `mvn clean verify`
- Run reference service: `cd obsinity-reference-service && ./build.sh && ./run.sh`
- Start local DB: `docker compose up -d`

## Collection SDK (Client-Side Only)

A transport-agnostic SDK to collect app telemetry via annotations and AOP and emit to pluggable sinks.

- Quick start, JSON schema, and configuration are documented here:
  - `documentation/collection-sdk.md`
  - `documentation/client-dev-guide.md` (developer guide with setup, transports, testing)
- Default Obsinity ingest endpoint used by the SDK: `http://localhost:8086/events/publish` (detects Docker host IP automatically when containerized)
  - Override with `-Dobsinity.ingest.url=...` or env `OBSINITY_INGEST_URL`.

Modules:
- `obsinity-collection-api` (annotations)
- `obsinity-collection-core` (OEvent model, FlowContext, DispatchBus, EventSink)
- `obsinity-collection-spring` (auto-config + aspect)
- `obsinity-collection-sink-logging` (logging sink)
- `obsinity-collection-sink-obsinity` (adapter to Obsinity REST ingest)

See `obsinity-reference-client-spring` for a runnable demo using `@Flow` and both sinks.

Trace propagation
- Servlet and WebFlux filters auto-capture W3C (traceparent/tracestate) and B3 headers and populate MDC + FlowContext.
- The aspect auto-populates trace meta (traceId/spanId/parentSpanId/tracestate) on emitted events.
- Toggle with `obsinity.collection.trace.enabled=true|false` (default true).
