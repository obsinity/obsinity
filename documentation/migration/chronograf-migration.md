# Target module layout (add alongside your client modules)

```
obsinity/
├─ obsinity-bom/
├─ obsinity-client-api/
├─ obsinity-client-core/
├─ obsinity-client-transport-spi/
├─ obsinity-client-transport-*/           (apache/okhttp/jdkhttp/resttemplate/webclient)
├─ obsinity-client-logging-sink/
├─ obsinity-client-testkit/
├─ obsinity-reference-client(-spring)/
└─ engine/
   ├─ obsinity-engine-app/
   ├─ obsinity-engine-api/
   ├─ obsinity-engine-storage/
   ├─ obsinity-engine-service/
   ├─ obsinity-engine-web/
   ├─ obsinity-engine-messaging/
   ├─ obsinity-engine-simulator/         (optional)
   └─ obsinity-engine-testkit/           (optional)
```

If you prefer flat modules, drop the `engine/` folder and place them at top level; the mapping is the same.

---

# File‑by‑area mapping (Chronograf → Engine)

Use package root rename to `com.obsinity.engine` during the move.

## 1) API (pure DTOs & enums) → `engine/obsinity-engine-api`

Move **request/response/view** models used by web & messaging:

* `models.EventPublishRequest`, `EventResponse`
* `models.EventSearchRequest`, `EventSearchOptions`
* `models.Aggregation*` (Request, Response, PagedResult, Result, AggregatedKeyCount, TimeBucket, BucketSegment)
* `models.ResolvedTimeRange`
* `models.EventConfig` / `EventConfigIO` (public representation)
* `models.SimulationRequest` (if you keep simulator)
* `models.HashStatus`
* Any simple API enums (e.g., aggregation mode, bucket sizes) that are **not** JPA enums

**New packages:** `com.obsinity.engine.api.event|aggregation|config|sim|status`

---

## 2) Storage (DB) → `engine/obsinity-engine-storage`

All JPA entities, repositories, DB enums, and SQL:

* Entities: `EventEntity`, `AttributeEntity`, `EventCountEntity (ChronografEventCountEntity)`, `EventConfigEntity`, composite IDs
* Repositories: `EventRepository`, `AttributeRepository`, `EventCountRepository`, `EventConfigRepository` (+ Custom/Impl)
* DB‑only enums: `Bucket` / `BucketSize` (if persisted)
* Migrations: move `scripts/sql/*.sql` → `src/main/resources/db/migration` (Flyway), e.g. `V1__init.sql`

**New packages:** `com.obsinity.engine.storage.entity|repo|enums|migration`

---

## 3) Domain services (business logic) → `engine/obsinity-engine-service`

Time resolution, ingestion, counters, aggregation:

* `ChronografService` → `EventIngestService`
* `ChronografAggregationService` → `AggregationService`
* `ChronografTimeRangeResolver` → `TimeRangeResolver`
* `ChronografIntervalAggregator` → `IntervalAggregator`
* `ChronografBucketResolver` → `BucketResolver`
* `ChronografKeyExpander` → `KeyExpander`
* Counter pipeline: `ChronografCounterBuffer`, `ChronografCounterPersistService`, `ChronografCounterFlusher`
* Utility domain services: `AttributeHashService` (old `ChronografHashService`)
* Internal observability helpers (cache stats logger)

**New packages:** `com.obsinity.engine.service.*`

---

## 4) Web (REST) → `engine/obsinity-engine-web`

Controllers and REST error handling:

* Controllers: `EventsController` (old `ChronoGrafController`), `AggregationController`, `EventConfigController`
* `@ControllerAdvice`: `GlobalExceptionHandler`
* Request validators / mappers (if not trivial)
* Web‑facing exceptions (or map service exceptions here)

**New packages:** `com.obsinity.engine.web.*`

---

## 5) Messaging (RabbitMQ) → `engine/obsinity-engine-messaging`

AMQP configs and listeners:

* `RabbitMQConfig` (exchanges, queues, bindings, converters)
* `EventQueueListener` → `EventIngestListener`
* Any message adapters (DTO ↔ domain)

**New packages:** `com.obsinity.engine.messaging.*`

**Properties to introduce (in `obsinity-engine-app`):**

```
obsinity.messaging.events-queue=obsinity.events
obsinity.messaging.dlq=obsinity.events.dlq
```

---

## 6) App (Spring Boot launcher) → `engine/obsinity-engine-app`

Boot app, top‑level configuration, profiles:

* `Application` → `EngineApplication`
* Cross‑module `@Configuration` that wires beans across `web`, `service`, `messaging`
* `application.yml` (DB, Rabbit, feature flags)

**New package:** `com.obsinity.engine.app`

---

## 7) Simulator (optional) → `engine/obsinity-engine-simulator`

Any load‑gen / sample endpoints separated from prod:

* `SimulationController` / `SimulationService`
* Sample data generators

---

## 8) Testkit (optional) → `engine/obsinity-engine-testkit`

* In‑memory adapters, fixtures, canned requests, Testcontainers helpers

---

# What **not** to place in client modules

None of the above should go into:

* `obsinity-client-api`, `obsinity-client-core`, or any `obsinity-client-transport-*`
* Client testkit/logging sink

Those are for **producer** (emit) only. Chronograf is **consumer/engine**.

---

# Quick move checklist

1. Create the `engine/*` modules (or separate repo) and add them to the parent `pom`.
2. Bulk rename packages: `net.theresnolimits.chronograf` → `com.obsinity.engine`.
3. Move files per sections above. Keep DTOs free of Spring/JPA.
4. Convert raw SQL to Flyway in `-storage`.
5. Split configs: AMQP → `-messaging`, MVC advice → `-web`, domain beans → `-service`.
6. Wire `obsinity-engine-app` to depend on `web` and `messaging` (and transitively on `service`/`storage`/`api`).
