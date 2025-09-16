# 📊 Obsinity CRDs — Developer Guide

Obsinity uses **Custom Resource Definitions (CRDs)** to describe **events** and **metrics** declaratively.
This guide covers:

1. 📨 **Events** — schemas for emitted signals.
2. 🔢 **MetricCounters** — event counts, segmented by dimensions.
3. 📈 **MetricHistograms** — value distributions with percentiles.
4. 🧮 **Derived Attributes** — scripted transformations with JavaScript/Groovy.

---

## 📨 1. Event CRD

Defines the **base schema** for an event. Metrics are derived from events.

### 📄 Structure

```yaml
apiVersion: obsinity/v1
kind: Event
metadata:
  service: <string>
  name: <string>
  displayName: <string>
  labels: { ... }
spec:
  schema:
    type: object
    properties:
      <field>: { type: <json-type>, index: <bool> }
```

### 💡 Example

```yaml
apiVersion: obsinity/v1
kind: Event
metadata:
  service: payments
  name: http_request
  displayName: HTTP Request
  labels:
    category: http
spec:
  schema:
    type: object
    properties:
      api:
        type: object
        properties:
          name:    { type: string, index: true }
          version: { type: string, index: true }
      http:
        type: object
        properties:
          method: { type: string, index: true }
          status: { type: integer, index: true }
          server:
            type: object
            properties:
              duration_ms: { type: integer }
```

---

## 🔢 2. MetricCounter CRD

Defines **event counts**, grouped by dimensions.

### 📄 Structure

```yaml
apiVersion: obsinity/v1
kind: MetricCounter
metadata:
  service: <string>
  event: <event-name>
  name: <string>
  displayName: <string>
  labels: { ... }
spec:
  sourceEvent: { service: <string>, name: <event> }

  derived: [ ... ]          # optional: scripted attributes

  key:
    dynamic: [ <path>, ... ] # dimensions included in metric identity

  aggregation:
    windowing: { granularities: [5s, 1m, 1h, 1d, 7d] }
    operation: count

  attributeMapping: { <alias>: <path> }
```

### 💡 Example — Requests by Status Code

```yaml
apiVersion: obsinity/v1
kind: MetricCounter
metadata:
  service: payments
  event: http_request
  name: http_requests_by_status_code
  displayName: HTTP Requests by Status Code
  labels:
    category: http
spec:
  sourceEvent: { service: payments, name: http_request }
  key:
    dynamic:
      - http.method
      - http.route
      - http.status
  aggregation:
    windowing: { granularities: [5s, 1m, 1h, 1d, 7d] }
    operation: count
  attributeMapping:
    method: http.method
    route: http.route
    status_code: http.status
```

---

## 📈 3. MetricHistogram CRD

Defines **distributions of numeric values**.

### 📄 Structure

```yaml
apiVersion: obsinity/v1
kind: MetricHistogram
metadata:
  service: <string>
  event: <event>
  name: <string>
  displayName: <string>
  labels: { ... }
spec:
  sourceEvent: { service: <string>, name: <event> }

  derived: [ ... ]          # optional

  value: <path>             # numeric attribute to measure

  key:
    dynamic: [ <path>, ... ]

  buckets:
    strategy: fixed|log|custom
    count: <int>
    min: <number>
    max: <number>

  aggregation:
    windowing: { granularities: [5s, 1m, 1h, 1d, 7d] }
    percentiles: [0.5, 0.9, 0.95, 0.99]
```

### 💡 Example — Request Latency

```yaml
apiVersion: obsinity/v1
kind: MetricHistogram
metadata:
  service: payments
  event: http_request
  name: http_request_latency_ms
  displayName: HTTP Request Latency (ms)
  labels: { category: http }
spec:
  sourceEvent: { service: payments, name: http_request }
  value: http.server.duration_ms
  key:
    dynamic: [ http.method, http.route ]
  buckets:
    strategy: fixed
    count: 100
    min: 1
    max: 10000
  aggregation:
    windowing: { granularities: [5s, 1m, 1h, 1d, 7d] }
    percentiles: [0.5, 0.9, 0.95, 0.99]
```

---

## 🧮 4. Derived Attributes

`derived` lets you define **new attributes** based on event fields using JS or Groovy.

### 📄 Structure

```yaml
derived:
  - target: <string>         # new attribute path
    source: <path>           # single attribute (simplest case)
    sources: [<path>, ...]   # OR multiple attributes (map input)
    lang: js|groovy
    script: |                # derive(value) or derive(values)
      ...
    index: true|false        # optional
    type: string|number|bool # optional
    whenMissing: skip|null|drop
```

---

### 🟦 Example 1 — Status Group (single source, JS)

```yaml
- target: http.status_code_group
  source: http.status
  lang: js
  script: |
    function derive(value) {
      if (value == null) return null;
      if (value >= 500 && value < 600) return "5xx";
      if (value >= 400 && value < 500) return "4xx";
      if (value >= 200 && value < 300) return "2xx";
      return "other";
    }
  index: true
  type: string
```

---

### 🟫 Example 2 — Latency Bucket (single source, Groovy)

```yaml
- target: http.latency_bucket
  source: http.server.duration_ms
  lang: groovy
  script: |
    def derive = { value ->
      if (value == null) return null
      if (value < 100) return "<100ms"
      if (value < 500) return "100–499ms"
      if (value < 1000) return "500–999ms"
      "≥1000ms"
    }
  type: string
```

---

### ⚡ Example 3 — Throughput (multi-source, JS)

```yaml
- target: http.throughput_bps
  sources: [http.response.size_bytes, http.server.duration_ms]
  lang: js
  script: |
    function derive(values) {
      const bytes = values["http.response.size_bytes"];
      const ms    = values["http.server.duration_ms"];
      if (bytes == null || ms == null || ms == 0) return null;
      return bytes / (ms / 1000.0);
    }
  type: number
```

---

### ⚙️ Runtime Rules

* Scripts must be **pure functions**: input → output.
* No side effects, I/O, or randomness.
* Execution is sandboxed (≤2 ms, ≤128 KB).
* Errors return `null`.
* Metrics track `derived_ok`, `derived_null`, `derived_error`, `derived_timeout`.

---

## 🏆 Best Practices

* ✅ **Index selectively** — only index fields used in queries.
* ✅ **Keep scripts small** — <20 lines, easy to reason about.
* ✅ **Chain derivations** — build complex attributes step by step.
* ✅ **Favor percentiles over averages** in histograms.
* ✅ **Granularity sets** should always include `[1m, 1h, 1d]` for efficient rollups.

---

## 🧪 Local Testing

**JS (Node.js)**

```bash
node -e '
  function derive(value) {
    if (value >= 500 && value < 600) return "5xx";
    return "other";
  }
  console.log(derive(503));
'
```

**Groovy**

```bash
groovy -e '
  def derive = { value -> value >= 500 && value < 600 ? "5xx" : "other" }
  println derive(503)
'
```

---

## 🚀 Summary

* **Events** define raw schemas.
* **Counters** count occurrences.
* **Histograms** measure distributions.
* **Derived attributes** enrich events with scripted transformations.

Everything is **declarative, composable, and OTEL-aligned** — making Obsinity a unified observability engine.
