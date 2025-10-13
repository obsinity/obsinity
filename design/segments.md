# Obsinity — **Segment** Model Proposal (v1)

> **Purpose:** Define **Segment** as the top-level namespace for events and metrics. Services subscribe to segments with explicit **read** and/or **write** permissions. This allows multiple services to share observability data in common segments while maintaining clear access control boundaries.

---

## 1) Concept Overview

* **Segment:** The authoritative container for events and metrics. Each segment represents a logical collaboration space (e.g., `riskops`, `analytics`, `fraudlab`).
* **Service:** A tenant or participant that can subscribe to one or more segments. Each subscription specifies **read** and/or **write** permissions.
* **Region/Zone:** Infrastructure or geographic deployment boundary — independent of segments.
* **Environment:** Lifecycle context such as `dev`, `staging`, or `prod`. A segment may span multiple environments.

**Key principles**

* Events and metrics are always stored under a **segment**, not under a service.
* **Location of segment (OTEL-friendly):** carried in `resource.telemetry.segment`.
* A single event is associated with **exactly one segment** (processes may switch segments between events, but not within one event).
* `attributes.correlation_id` is a **generic attribute** (not namespaced under `obsinity`).
* Services use their bindings to determine where they can publish or query.
* Cross-segment access is explicit and requires authorization.

---

## 2) Event Definitions

Event definitions are now owned by segments. Each segment defines its catalog of event types that services may emit.

```yaml
apiVersion: obsinity/v1
kind: Event
metadata:
  segment: riskops
  name: http_request
  displayName: HTTP Request
  labels:
    category: http
spec:
  schema:
    type: object
    properties:
      http.method: { type: string }
      http.route: { type: string }
      http.status_code: { type: integer }
  retention:
    ttl: "7d"
```

A single event definition applies to all services allowed to publish within that segment.

---

## 3) Subscriptions (Service → Segment)

Each service declares which segments it participates in and the access level it has for each.

```yaml
apiVersion: obsinity/v1
kind: SegmentSubscription
metadata:
  name: payments-to-riskops
spec:
  segment: riskops
  service: payments
  access:
    - write
    - read
```

Multiple services can subscribe to the same segment, allowing shared observability of common event streams.

Example:

```yaml
apiVersion: obsinity/v1
kind: SegmentSubscription
metadata:
  name: auth-to-riskops
spec:
  segment: riskops
  service: auth
  access: [read, write]
```

---

## 4) Example Events

### 4.1 `POST {{ base_url }}/events/publish` — payments → `http_request` (OK)

```json
{
  "event": { "name": "http_request", "domain": "http", "kind": "SERVER" },
  "resource": {
    "service": { "name": "payments", "namespace": "{{ service_namespace }}", "instance": { "id": "{{ service_instance_id }}" }, "version": "{{ service_version }}" },
    "host": { "name": "{{ host_name }}" },
    "telemetry": { "sdk": { "name": "{{ sdk_name }}", "version": "{{ sdk_version }}" }, "segment": "{{ segment }}" },
    "cloud": { "provider": "{{ cloud_provider }}", "region": "{{ cloud_region }}" }
  },
  "trace": { "traceId": "{{ trace_id }}", "spanId": "{{ span_id }}" },
  "time": { "startedAt": "{{ nowIso }}", "endedAt": "{{ nowIso }}", "startUnixNano": 0, "endUnixNano": 0 },
  "attributes": {
    "correlation_id": "{{ correlation_id }}",
    "http.method": "POST",
    "http.route": "/api/pay",
    "http.status_code": 200
  },
  "events": [],
  "links": [],
  "status": { "code": "OK", "message": "" },
  "synthetic": false
}
```

### 4.2 `POST {{ base_url }}/events/publish` — auth → `auth_attempt` (OK)

```json
{
  "event": { "name": "auth_attempt", "domain": "security", "kind": "SERVER" },
  "resource": {
    "service": { "name": "auth", "namespace": "{{ service_namespace }}", "instance": { "id": "{{ service_instance_id }}" }, "version": "{{ service_version }}" },
    "telemetry": { "sdk": { "name": "{{ sdk_name }}", "version": "{{ sdk_version }}" }, "segment": "{{ segment }}" }
  },
  "trace": { "traceId": "{{ trace_id }}", "spanId": "{{ span_id_2 }}" },
  "time": { "observed": "2025-10-13T08:12:05Z" },
  "attributes": {
    "correlation_id": "{{ correlation_id }}",
    "auth.result": "success",
    "user.id": "1234"
  }
}
```

### 4.3 `POST {{ base_url }}/events/publish` — payments error (500)

```json
{
  "event": { "name": "http_request", "domain": "http", "kind": "SERVER" },
  "resource": {
    "service": { "name": "payments", "namespace": "{{ service_namespace }}", "instance": { "id": "{{ service_instance_id }}" }, "version": "{{ service_version }}" },
    "host": { "name": "{{ host_name }}" },
    "telemetry": { "sdk": { "name": "{{ sdk_name }}", "version": "{{ sdk_version }}" }, "segment": "{{ segment }}" },
    "cloud": { "provider": "{{ cloud_provider }}", "region": "{{ cloud_region }}" }
  },
  "trace": { "traceId": "{{ trace_id }}", "spanId": "{{ span_id_error }}" },
  "time": { "startedAt": "{{ nowIso }}", "endedAt": "{{ nowIso }}", "startUnixNano": 0, "endUnixNano": 0 },
  "attributes": {
    "correlation_id": "{{ correlation_id }}",
    "api.name": "createTransaction",
    "http.method": "POST",
    "http.route": "/v2/transactions",
    "http.status_code": 500
  },
  "events": [],
  "links": [],
  "status": { "code": "ERROR", "message": "Server error" },
  "synthetic": false
}
```

### 4.4 `POST {{ base_url }}/events/publish/batch` — homogeneous batch

```json
[
  {
    "event": { "name": "{{ event_name }}", "kind": "CLIENT" },
    "resource": {
      "service": { "name": "{{ service_id }}", "namespace": "{{ service_namespace }}", "instance": { "id": "{{ service_instance_id }}" }, "version": "{{ service_version }}" },
      "host": { "name": "{{ host_name }}" },
      "telemetry": { "sdk": { "name": "{{ sdk_name }}", "version": "{{ sdk_version }}" }, "segment": "{{ segment }}" },
      "cloud": { "provider": "{{ cloud_provider }}", "region": "{{ cloud_region }}" }
    },
    "trace": { "traceId": "{{ trace_id }}", "spanId": "{{ span_id }}" },
    "time": { "startedAt": "{{ nowIso }}", "endedAt": "{{ nowIso }}", "startUnixNano": 0, "endUnixNano": 0 },
    "attributes": {
      "correlation_id": "{{ correlation_id }}",
      "api.name": "lookupUser",
      "http.status_code": 200,
      "http.method": "GET"
    },
    "events": [],
    "links": [],
    "status": { "code": "OK", "message": "" },
    "synthetic": false
  },
  {
    "event": { "name": "{{ event_name }}", "kind": "INTERNAL" },
    "resource": {
      "service": { "name": "{{ service_id }}", "namespace": "{{ service_namespace }}", "instance": { "id": "{{ service_instance_id }}" }, "version": "{{ service_version }}" },
      "telemetry": { "sdk": { "name": "{{ sdk_name }}", "version": "{{ sdk_version }}" }, "segment": "{{ segment }}" }
    },
    "trace": { "traceId": "{{ trace_id }}", "spanId": "{{ span_id_2 }}" },
    "time": { "startedAt": "{{ nowIso }}", "endedAt": "{{ nowIso }}", "startUnixNano": 0, "endUnixNano": 0 },
    "attributes": {
      "correlation_id": "{{ correlation_id }}",
      "db.system": "postgresql",
      "db.statement": "select 1"
    },
    "events": [],
    "links": [],
    "status": { "code": "OK", "message": "" },
    "synthetic": false
  }
]
```

---

## 5) Access Model

**Segment ownership** is independent from services. Services are granted access via **subscriptions**.

| Permission | Description                                                                  |
| ---------- | ---------------------------------------------------------------------------- |
| **read**   | Can query events and metrics within the segment.                             |
| **write**  | Can publish new events to the segment.                                       |
| **admin**  | (optional future role) Can modify event definitions or manage subscriptions. |

**Example policy logic:**

* A service can only publish if it holds **write** permission for that segment.
* Query APIs enforce `segment IN (<allowed segments>)` automatically.
* Cross-segment queries require the service to have **read** on all listed segments.

---

## 5) Example Queries (JSON Search & OB‑JQL)

Queries now target the segment via **`resource.telemetry.segment`** and use **`attributes.correlation_id`**.

### 6.1 JSON Search — Aggregate HTTP requests in `riskops`

```json
{
  "service": "payments",
  "event": "http_request",
  "period": { "previous": "-1h" },
  "filter": { "path": "resource.telemetry.segment", "op": "=", "value": "riskops" },
  "match": { "attribute": "http.status_code", "op": "=", "value": 200 },
  "order": [{ "field": "started_at", "dir": "desc" }],
  "limit": 100
}
```

### 6.2 JSON Search — Correlate `auth` and `payments` in the same segment

```json
{
  "service": ["auth", "payments"],
  "period": { "previous": "-30m" },
  "filter": {
    "and": [
      { "path": "resource.telemetry.segment", "op": "=", "value": "riskops" },
      { "or": [
          { "path": "event.name", "op": "=", "value": "http_request" },
          { "path": "event.name", "op": "=", "value": "auth_attempt" }
        ]
      }
    ]
  },
  "order": [{ "field": "started_at", "dir": "desc" }],
  "limit": 200
}
```

### 6.3 JSON Search — By correlation id within a segment

```json
{
  "period": { "previous": "-15m" },
  "filter": {
    "and": [
      { "path": "resource.telemetry.segment", "op": "=", "value": "riskops" },
      { "path": "attributes.correlation_id",   "op": "=", "value": "{{ correlation_id }}" }
    ]
  },
  "order": [{ "field": "started_at", "dir": "desc" }],
  "limit": 25
}
```

### 6.4 OB‑JQL — Raw string equivalent

```json
{
  "q": "where res.telemetry.segment = 'riskops'
and  attr.correlation_id = '{{ correlation_id }}'
since -15m
order by started_at desc
limit 25",
  "offset": 0,
  "limit": 25
}
```

---

## 6) Benefits

* **Unified collaboration layer:** Multiple services share a single observability surface.
* **Fine-grained access:** Segments define who can write or read.
* **Simplified analytics:** Correlation and aggregation within a segment are natural.
* **Clear boundaries:** Cross-segment access is explicit and governed.

---

## 7) Next Steps

1. Introduce `Segment` and `SegmentSubscription` CRDs in the control plane.
2. Update event ingestion to require `segment` as a top-level field.
3. Enforce ACL checks on publish and query operations.
4. Update documentation and dashboards to present data under segments instead of services.

---

> **Outcome:** Segments become the primary containers for event data, enabling shared analysis across services while maintaining strong access controls.
