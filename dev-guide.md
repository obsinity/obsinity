# Obsinity Developer Experience — High‑Level Guide (DX v0.2)

> **Scope:** High‑level, opinionated overview of how developers instrument, ship, query, and learn from telemetry in **Obsinity**. Anchored on a **sample event**, a **sample query**, and a **sample HAL‑compliant response**.

---

## 1) What is Obsinity?

**Obsinity** is an OTEL‑aligned observability and time‑series platform backed by the **Obsinity Engine** — *“Powering observability across all time. Where every signal lives forever.”* It ingests application/runtime events, indexes key attributes for fast filtering, and precomputes rollups (counters & histograms) across canonical granularities (5s → 1m → 1h → 1d → 7d). Developers keep their payloads simple and strongly‑typed; the platform makes them queryable and explorable at scale.

**Core tenets**

* **OTEL‑aligned envelope** + flexible **attributes**. Keep the envelope stable; evolve attributes liberally.
* **Service‑scoped storage** (partition on `resource.service.name`) for predictable performance and tenancy.
* **Attribute index** for sub‑second filtering on common paths (`http.status`, `api.name`, …).
* **Rollups by default** for counts and latency percentiles. Raw events remain the source of truth.
* **Two query modes**: OB‑JQL (JSON) and OB‑SQL (readable DSL). Same model, different ergonomics.

---

## 2) The Developer Workflow (happy path)

1. **Instrument**: add an SDK or use existing OTEL exporters; emit events using the envelope below.
2. **Emit**: send to Obsinity Ingest (HTTPS/gRPC). Client batching + retries keep things efficient.
3. **Ingest & Store**: raw events land in an append‑only, service‑partitioned inbox.
4. **Index**: attribute extraction creates fast paths for `MATCH`/faceting.
5. **Rollup**: counters & histograms are computed at multiple granularities; late data is reconciled idempotently.
6. **Query**: use OB‑JQL/OB‑SQL to fetch events, counts, or latency percentiles.
7. **Observe**: dashboards and “current state snapshots” visualize trends and SLIs.
8. **Iterate**: evolve attributes (no schema migration), add derived metrics if/when needed.

---

## 3) Event Model (your sample event)

Developers ship a compact, OTEL‑aligned envelope plus structured attributes:

```json
{
  "event":   { "name": "{{ event_name }}", "kind": "SERVER" },
  "resource":{ "service": { "name": "{{ service_id }}" } },
  "trace":   { "correlation_id": "{{ correlation_id }}", "trace_id": "{{ trace_id }}", "span_id": "{{ span_id }}" },
  "attributes": {
    "api":  { "name": "getAccountHolders", "version": "v2" },
    "http": { "status": 201, "method": "GET" }
  },
  "started_at": "{{ nowIso }}"
}
```

### Envelope at a glance

* **event.name / event.kind** — semantic identity and SpanKind‑style role.
* **resource.service.name** — short, stable slug (e.g., `payment`). Primary partition key.
* \**trace.* \*\*— correlation and tracing identifiers for stitching flows.
* \**attributes.* \*\*— flexible nested payloads (e.g., `api.*`, `http.*`).
* **occurred\_at** — producer time (UTC ISO‑8601). The engine also records `received_at`.

**Conventions**

* Prefer integers for status codes (`http.status = 201`).
* Version strings are explicit (`api.version = "v2"`).
* Add attributes you’ll want to filter/aggregate on; Obsinity’s index will do the rest.

---

## 4) Query Experience (your sample query)

You supplied the following **OB‑JQL**. It looks for a specific service & event over a fixed UTC window, with a mix of **indexed predicates** (`match`) and **row filters** (`filter`).

```json
{
  "service": "{{ service_id }}",
  "event": "{{ event_name }}",
  "period": { "between": ["2025-09-15T00:00:00Z", "2025-09-15T06:00:00Z"] },
  "match": [
    { "attribute": "http.status", "op": "!=", "value": 500 }
  ],
  "filter": {
    "and": [
      { "path": "attributes.client.ip", "op": "!=", "value": "10.0.0.1" },
      { "or": [
          { "path": "attributes.api.name", "op": "like",  "value": "%account%" },
          { "path": "attributes.api.name", "op": "ilike", "value": "create%" }
        ]
      }
    ]
  },
  "order": [{ "field": "started_at", "dir": "desc" }],
  "limit": 100
}
```

### OB‑SQL (readable DSL) equivalent

```sql
FIND EVENTS
  SERVICE  '{{ service_id }}'
  EVENT    '{{ event_name }}'
  PERIOD   BETWEEN '2025-09-15T00:00:00Z' AND '2025-09-15T06:00:00Z'
  MATCH (
    http.status != 500
  )
  FILTER (
    attributes.client.ip != '10.0.0.1' AND (
      attributes.api.name LIKE '%account%' OR
      attributes.api.name ILIKE 'create%'
    )
  )
  ORDER BY started_at DESC
  LIMIT 100;
```

### Semantics & ergonomics

* **`period.between`** is UTC; presentation TZ can be controlled per request (e.g., `TZ 'Europe/Dublin'`).
* **`match`** targets **indexed attributes** (fast). Prefer common paths there (`http.status`, `http.method`, `api.name`, …).
* **`filter`** operates on the full row (envelope + attributes). Use it for expressive predicates that aren’t in the index.
* **Ordering & paging**: stable ordering is by `started_at` with `event_id` tiebreakers under the hood; client uses `limit/offset` (or cursors in streaming mode).

---

## 5) Response Experience (HAL‑compliant)

Obsinity’s API responses are **HAL‑compliant**, making them navigable and self‑describing with embedded `links`. Your sample response illustrates the pattern:

```json
{
  "count": 0,
  "total": 0,
  "limit": 100,
  "offset": 0,
  "data": {
    "events": []
  },
  "links": {
    "self": {
      "href": "/api/search/events",
      "method": "POST",
      "body": { "… query …" }
    },
    "first": {
      "href": "/api/search/events",
      "method": "POST",
      "body": { "… query with offset 0 …" }
    },
    "last": {
      "href": "/api/search/events",
      "method": "POST",
      "body": { "… query with last offset …" }
    }
  }
}
```

### HAL Highlights

* **`_links` → `links`** pattern: every response includes `self`, `first`, `last`, and optionally `next`/`prev`.
* Each link is **callable**: contains `href`, `method`, and the request `body`.
* Encourages **HATEOAS** style navigation; clients can paginate or repeat searches without hardcoding request shapes.
* HAL compliance ensures interoperability with generic clients and HAL‑aware libraries.

---

## 6) What Obsinity does for you (behind the scenes)

* **Append‑only raw store**: durable, time‑partitioned by `service` for efficient pruning & scans.
* **Attribute index**: selective inverted maps over popular paths (configurable) for sub‑second lookups.
* **Rollup fabric**: idempotent aggregation into 5s/1m/1h/1d/7d buckets for counters & histograms.
* **Late arrivals**: delta recompute updates affected buckets; raw timelines remain consistent.
* **Trace stitching**: follow flows using `trace_id`/`span_id`/`correlation_id` without custom joins.

---

## 7) Developer ergonomics & principles

* **Name it once**: keep `event.name` stable (e.g., `http_request`). Specialize via attributes.
* **Be generous with attributes**: anything you’ll need for slicing should be an attribute. Indexing makes it fast later.
* **Time is first‑class**: always set `started_at` in UTC. Choose output TZ at query time.
* **No schema migration tax**: adding attributes doesn’t require table changes; the index adapts.
* **Readable queries**: OB‑SQL mirrors OB‑JQL but is optimized for human reading & dashboards.
* **HAL everywhere**: responses are HAL‑compliant, making pagination and navigation consistent.

---

## 8) Local Developer Loop

* **Spin up** Obsinity Engine + PostgreSQL locally.
* **Emit** sample events (curl/SDK) and **query immediately** in the CLI/UI.
* **Validate** attribute names/types early; adopt a short naming guide for team convergence.
* **Snapshot**: use “current state snapshots” for clear, shareable debugging views.

---

## 9) Production Posture

* **Throughput**: client‑side batching & async; horizontal ingest scaling.
* **Durability**: raw before rollups; safe restarts and idempotent recompute.
* **Governance**: central registry of event names and common attribute keys (light‑weight, code‑reviewed).
* **SLOs**: counters & latency percentiles feed SLIs; derive error‑rate series from `http.status` folds (e.g., 5xx).

---

## 10) Quick Patterns & Gotchas

* **Use `match` for speed**: place high‑cardinality, frequently‑filtered fields in the index (or request indexing).
* **Folded values**: you can derive groups like `2xx`/`4xx`/`5xx` later without changing producers.
* **Case sensitivity**: use `LIKE` vs `ILIKE` intentionally; prefer `ILIKE` for user‑facing search.
* **TZ awareness**: ingest in UTC; render in your local TZ (e.g., `Europe/Dublin`).
* **Trace joins**: prefer `trace_id` and `correlation_id` for cross‑service flow inspection.
* **HAL navigation**: paginate with `first`/`last`/`next`/`prev` instead of manually stitching offsets.

---

## 11) One‑page DX checklist

* [ ] Stable `event.name` and `resource.service.name`.
* [ ] `started_at` set in UTC (ISO‑8601).
* [ ] Attributes for everything you’ll filter/aggregate by.
* [ ] Common paths in the index (or requested for indexing).
* [ ] Queries written in OB‑JQL and OB‑SQL (readable for dashboards).
* [ ] Responses interpreted via HAL links (`self`, `first`, `last`, …).
* [ ] Dashboards using counters & histogram percentiles (p50/p90/p95/p99).
* [ ] Team naming guide published and enforced in code review.

---
