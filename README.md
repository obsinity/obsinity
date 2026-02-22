# Obsinity

Obsinity is a modular telemetry system with a PostgreSQL backend, REST controllers, and client libraries.

- Build all modules: `mvn clean verify`
- Run reference service: `cd obsinity-reference-service && ./build.sh && ./run.sh`
- Start local DB: `docker compose up -d`
- Run PIT mutation tests: `mvn -P mutation-testing clean verify [-pl <module> -am]` (add `-DskipTests` to omit regular unit tests; reports in `target/pit-reports`). Service-core is skipped by default; include it with `-Dpitest.excludedProjects=`. Add `-Dpitest.verbose=true -Dpitest.debug=true` if you need minion diagnostics.

## Server Capabilities (Current)

- **Long-term raw event storage**: `obsinity-service-core` writes every event into partitioned Postgres tables (`events_raw`, `event_attr_index`) with per-service/event TTLs for retention control.
- **Multi-dimensional search**: `/api/search/events`, `/api/catalog/*`, and `/api/objql/query` expose attribute-level filtering, HAL pagination, and catalog discovery.
- **Multi-dimensional counters & histograms**: `/api/query/counters` and `/api/histograms/query` serve HAL interval payloads from ingest-time rollups (5s → 7d) with per-metric granularity knobs.
- **Multi-attribute state snapshots, transitions, and ratios**: `StateDetectionService` + `/api/query/state-counts` (current distribution), `/api/query/state-count-timeseries` (aligned minute snapshots with `1m/5m/1h/1d` rollups and arbitrary whole-minute sampling), `/api/query/state-transitions` (A→B flows), and `/api/query/ratio` (named state/transition/mixed ratio slices for pie charts).
- **HAL everywhere**: all query endpoints emit HAL responses (complete with `_links` and interval arrays) for easy dashboard integration.
- **Extremely configurable**: Service configs (JSON/CRD) define indexes, derived fields, metrics, and retention. Pipeline properties (`obsinity.counters.*`, `obsinity.histograms.*`, etc.) tune worker counts, flush rates, and batch sizes.
- **Runs on stock PostgreSQL**: everything targets vanilla Postgres with Flyway migrations; no exotic extensions are needed.
- **Multi-protocol ingest**: `obsinity-controller-rest` handles HTTP/HTTPS, `obsinity-ingest-rabbitmq` consumes AMQP queues, and `obsinity-ingest-kafka` consumes Kafka topics.
- **Extensive Java annotation-based SDK**: `obsinity-collection-*` modules ship `@Flow`, `@Step`, `@FlowSink`, and pluggable transports (HTTP stacks + RabbitMQ) so services can emit events with minimal boilerplate.
- **Reference service**: `obsinity-reference-service` bundles the controllers, ingest workers, config loader (`obsinity.config.init.*`), and TLS-ready Spring Boot settings.
- **Grafana dashboards**: Pre-configured dashboards for visualizing state counters, transitions, latency histograms, and API counters via REST API queries (see [Demo Visualization](#demo-visualization) below).

Read the full highlight reel in [`documentation/obsinity-highlights.md`](documentation/obsinity-highlights.md).

## Planned / Unimplemented Features

These are described in the architecture/design docs but are not yet implemented:

1. **UEQ tooling/backoff** — Kafka/RabbitMQ consumers persist bad payloads to the UEQ today, but automated replay tooling, throttling, and alerting are still planned.
2. **Outbound forwarding connectors (Kafka, RabbitMQ, S3, remote Postgres)** — outbox tables and connector implementations are pending.
3. **OpenTelemetry (OTLP) ingest** — `obsinity-controller-otlp` exposes `/otlp/v1/traces` as a stub only.
4. **Gauges & advanced histogram schemes** — metric model supports them conceptually, but the server currently materialises counters, histograms, and state counters only.
5. **GraphQL/SQL query surfaces** — roadmap mentions richer query APIs; current interfaces are HAL-style REST endpoints.

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
- Transport implementations:
  - `obsinity-client-transport-webclient`
  - `obsinity-client-transport-resttemplate`
  - `obsinity-client-transport-okhttp`
  - `obsinity-client-transport-jdkhttp`
  - `obsinity-client-transport-rabbitmq`

See `obsinity-reference-client-spring` for a runnable demo using `@Flow` and both sinks.

Trace propagation
- Servlet and WebFlux filters auto-capture W3C (traceparent/tracestate) and B3 headers and populate MDC + FlowContext.
- The aspect auto-populates trace meta (traceId/spanId/parentSpanId/tracestate) on emitted events.
- Toggle with `obsinity.collection.trace.enabled=true|false` (default true).

## Configuring ingestion mechanisms

- **HTTPS REST ingest**: configure the reference service with the standard Spring Boot SSL properties (e.g., `server.ssl.enabled=true`, `server.ssl.key-store=classpath:tls.p12`, `server.ssl.key-store-password=changeit`) and point clients at an `https://` `obsinity.ingest.url`.
- **RabbitMQ**: set `obsinity.ingest.rmq.enabled=true` on the server (and `obsinity.ingest.rmq.queue`, broker host/user env vars as needed). On the client include `obsinity-client-transport-rabbitmq` and set the `obsinity.rmq.*` properties from the table above.
- **Kafka**: set `obsinity.ingest.kafka.enabled=true` plus `obsinity.ingest.kafka.bootstrap-servers`, `topic`, `group-id`, and `client-id` to activate the Kafka consumer inside the reference service.

### RabbitMQ transport configuration

Include `obsinity-client-transport-rabbitmq` to publish flow payloads directly to RabbitMQ. Configure it via system properties or environment variables (defaults shown):

| Property | Environment | Default |
| -------- | ----------- | ------- |
| `obsinity.rmq.host` | `OBSINITY_RMQ_HOST` | `localhost` |
| `obsinity.rmq.port` | `OBSINITY_RMQ_PORT` | `5672` |
| `obsinity.rmq.username` | `OBSINITY_RMQ_USERNAME` | `guest` |
| `obsinity.rmq.password` | `OBSINITY_RMQ_PASSWORD` | `guest` |
| `obsinity.rmq.vhost` | `OBSINITY_RMQ_VHOST` | `/` |
| `obsinity.rmq.exchange` | `OBSINITY_RMQ_EXCHANGE` | `obsinity.events` |
| `obsinity.rmq.routing-key` | `OBSINITY_RMQ_ROUTING_KEY` | `flows` |
| `obsinity.rmq.mandatory` | `OBSINITY_RMQ_MANDATORY` | `false` |

Pair the emitter with `obsinity-ingest-rabbitmq` (or your own worker) to deliver the canonical payloads without going through the REST controller.

## Demo Visualization

The demo stack includes **Grafana dashboards** for real-time visualization of Obsinity metrics via the REST API.

### Two Ways to Run

**Option 1: Reference Service Only (Existing Scripts)**
```bash
cd obsinity-reference-service
./build.sh && ./run.sh
```
Starts: PostgreSQL + Obsinity Server (port 8086)  
**No Grafana** - Use this for API development and testing

**Option 2: Full Demo Stack with Grafana**
```bash
# From repository root
docker-compose -f docker-compose.demo.yml up -d

# Or use the quick start script
./start-grafana-demo.sh
```
Starts: PostgreSQL + Obsinity Server + Demo Client + **Grafana** (ports 8086, 8080, 3086)  
**Includes Grafana** - Use this for demos and visualization

### Quick Start with Grafana

```bash
# Start the demo stack (includes Grafana)
docker-compose -f docker-compose.demo.yml up -d

# Start background demo generation
curl -X POST http://localhost:8086/internal/demo/generate-unified-events \
  -H "Content-Type: application/json" \
  -d '{
    "serviceKey": "payments",
    "eventType": "user_profile.updated",
    "duration": "2m",
    "eventsPerSecond": 500,
    "events": 60000,
    "profilePool": 100,
    "statuses": ["NEW", "ACTIVE", "ACTIVE", "ACTIVE", "SUSPENDED", "SUSPENDED", "BLOCKED", "UPGRADED", "ARCHIVED", "ARCHIVED", "ARCHIVED"],
    "channels": ["web", "mobile", "partner"],
    "regions": ["us-east", "us-west", "eu-central"],
    "tiers": ["FREE", "PLUS", "PRO"],
    "maxEventDurationMillis": 1500,
    "recentWindow": "1h",
    "recentWindowSeconds": 10800,
    "runIntervalSeconds": 60
  }'

curl http://localhost:8086/internal/demo/generate-unified-events/status

curl -X POST http://localhost:8086/internal/demo/generate-unified-events/stop

# Access Grafana at http://localhost:3086
# Login: admin/admin
```

### Available Dashboards

**Obsinity Demo - Overview** includes:

- **State Counts**: Current distribution of user profiles by status
- **State Count Time Series**: Historical state counts at 1-minute intervals
- **State Transitions**: State change flow visualization (NEW→ACTIVE, ACTIVE→SUSPENDED, etc.)
- **HTTP Request Latency**: Percentile-based latency histograms (p50, p90, p95, p99)
- **Profile Update Latency**: Update duration metrics broken down by channel
- **API Counters**: Request counts by status code, method, and dimensions
- **Profile Updates by Status/Channel**: Multi-dimensional event counters
- **Funnel outcomes pie chart**: Named ratio query (`funnel_outcomes`) served by `/api/grafana/ratio`

All panels query the Obsinity REST API (not the database directly), demonstrating real-world API usage patterns.

Notes:
- `events` overrides `duration` x `eventsPerSecond`.
- Demo generation is real-time; timestamps are clustered around "now".

### Documentation

See [`obsinity-reference-service/grafana/README.md`](obsinity-reference-service/grafana/README.md) for:
- Complete dashboard documentation
- API query examples
- Customization guide
- Troubleshooting tips
