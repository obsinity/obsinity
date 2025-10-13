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

### 4.1 Payment Service emits an event

```json
{
  "segment": "riskops",
  "resource": { "service": { "name": "payments" } },
  "event": { "name": "http_request", "domain": "http", "kind": "SERVER" },
  "time": { "observed": "2025-10-13T08:12:00Z" },
  "attributes": {
    "http.method": "POST",
    "http.route": "/api/pay",
    "http.status_code": 200
  }
}
```

### 4.2 Auth Service emits an event into the same segment

```json
{
  "segment": "riskops",
  "resource": { "service": { "name": "auth" } },
  "event": { "name": "auth_attempt", "domain": "security", "kind": "SERVER" },
  "time": { "observed": "2025-10-13T08:12:05Z" },
  "attributes": {
    "auth.result": "success",
    "user.id": "1234"
  }
}
```

Both events live under the **`riskops`** segment, allowing cross-service correlation.

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

## 6) Example Queries

Example 1 — Aggregate HTTP requests in `riskops`:

```sql
FROM events
WHERE segment = 'riskops' AND event.name = 'http_request'
GROUP BY time(1m), attrs->>'http.status_code';
```

Example 2 — Correlate authentication and payments events in the same segment:

```sql
FROM events
WHERE segment = 'riskops'
  AND service IN ('auth','payments')
GROUP BY time(30s), service, event.name;
```

Example 3 — Multi-segment (if allowed):

```sql
FROM events
WHERE segment IN ('riskops','fraudlab')
GROUP BY time(5m), service;
```

---

## 7) Benefits

* **Unified collaboration layer:** Multiple services share a single observability surface.
* **Fine-grained access:** Segments define who can write or read.
* **Simplified analytics:** Correlation and aggregation within a segment are natural.
* **Clear boundaries:** Cross-segment access is explicit and governed.

---

## 8) Next Steps

1. Introduce `Segment` and `SegmentSubscription` CRDs in the control plane.
2. Update event ingestion to require `segment` as a top-level field.
3. Enforce ACL checks on publish and query operations.
4. Update documentation and dashboards to present data under segments instead of services.

---

> **Outcome:** Segments become the primary containers for event data, enabling shared analysis across services while maintaining strong access controls.
