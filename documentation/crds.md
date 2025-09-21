# 📊 Obsinity CRDs — Developer Guide (Embedded Scriptlets)

Obsinity models observability with **declarative CRDs**. At ingest, each event is:

1. validated against its **Event schema**,
2. **derived** attributes are computed (from embedded scriptlets), and
3. the enriched event feeds **MetricCounters** and **MetricHistograms**.

No metric contains scripts. No runtime WHERE. A series is identified solely by its **exact key**.

---

## 📨 1) Event CRD (with embedded `derived`)

### Structure

```yaml
apiVersion: obsinity/v1
kind: Event
metadata:
  service: <string>
  name: <string>
  displayName: <string>
  labels: { <k>: <v>, ... }
spec:
  retention:
    ttl: "7d"                 # optional raw-event retention window
  schema:
    type: object
    properties:
      <field>: { type: <json-type>, index: <bool> }

  # Run once per event, in order. Single-source only.
  derived:
    - target: <path>            # where to write the derived value (e.g., http.status_code_group)
      source: <path>            # one attribute path passed into the script (e.g., http.status)
      lang: js|groovy
      script: |                 # embedded scriptlet; must expose derive(value)
        ...
      index: true|false         # add derived field to attribute index (default false)
      type: string|number|bool  # optional type validation/coercion
      whenMissing: skip|null|drop  # behavior if derive returns null (default: skip)
```

### Example

```yaml
apiVersion: obsinity/v1
kind: Event
metadata:
  service: payments
  name: http_request
  displayName: HTTP Request
  labels: { category: http }
spec:
  schema:
    type: object
    properties:
      http:
        type: object
        properties:
          method:       { type: string,  index: true }
          status:       { type: integer, index: true }
          route:        { type: string,  index: true }
          server:
            type: object
            properties:
              duration_ms: { type: integer }

  derived:
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

    - target: http.latency_bucket
      source: http.server.duration_ms
      lang: groovy
      script: |
        def derive = { value ->
          if (value == null) return null
          if (value < 100)   return "<100ms"
          if (value < 500)   return "100–499ms"
          if (value < 1000)  return "500–999ms"
          "≥1000ms"
        }
      index: true
      type: string
```

---

## 🔢 2) MetricCounter CRD

Counts events, grouped by **dimensions** from the enriched event (raw or derived fields).

### Structure

```yaml
apiVersion: obsinity/v1
kind: MetricCounter
metadata:
  service: <string>
  event: <event-name>
  name: <string>
  displayName: <string>
  labels: { <k>: <v>, ... }
spec:
  sourceEvent: { service: <string>, name: <event> }

  key:
    dynamic: [ <path>, ... ]   # exact-match identity (e.g., [http.method, http.route, http.status_code_group])

  aggregation:
    windowing: { granularities: [5s, 1m, 1h, 1d, 7d] }
    operation: count

  attributeMapping: { <alias>: <path> }  # optional, for UI naming
```

### Example — HTTP 5xx Requests

```yaml
apiVersion: obsinity/v1
kind: MetricCounter
metadata:
  service: payments
  event: http_request
  name: http_requests_5xx
  displayName: HTTP 5xx Requests
  labels: { category: http }
spec:
  sourceEvent: { service: payments, name: http_request }

  key:
    dynamic: [ http.method, http.route, http.status_code_group ]

  aggregation:
    windowing: { granularities: [5s, 1m, 1h, 1d, 7d] }
    operation: count
  retention:
    ttl: "90d"                # optional metric retention window

  attributeMapping:
    method: http.method
    route:  http.route
    status: http.status_code_group
```

> Want only the “5xx” series? Query the **key** with `http.status_code_group='5xx'`. No runtime WHERE needed.

---

## 📈 3) MetricHistogram CRD

Captures **distributions** (latency, sizes, etc.) with buckets and percentiles.

### Structure

```yaml
apiVersion: obsinity/v1
kind: MetricHistogram
metadata:
  service: <string>
  event: <event>
  name: <string>
  displayName: <string>
  labels: { <k>: <v>, ... }
spec:
  sourceEvent: { service: <string>, name: <event> }

  value: <path>                  # numeric field to measure (raw or derived)
  key:
    dynamic: [ <path>, ... ]     # optional additional dims (e.g., http.latency_bucket, http.method)

  buckets:
    strategy: fixed|log|custom
    count: <int>
    min: <number>
    max: <number>

  aggregation:
    windowing: { granularities: [5s, 1m, 1h, 1d, 7d] }
    percentiles: [0.5, 0.9, 0.95, 0.99]
```

### Example — HTTP Request Latency

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
    dynamic: [ http.method, http.route, http.latency_bucket ]
  buckets:
    strategy: fixed
    count: 100
    min: 1
    max: 10000
  aggregation:
    windowing: { granularities: [5s, 1m, 1h, 1d, 7d] }
    percentiles: [0.5, 0.9, 0.95, 0.99]
  retention:
    ttl: "90d"                # optional metric retention window
```

---

## 🧮 4) Embedded Scriptlets — Runtime Contract

* **Single input only**: engine resolves `source` path → passes that **value**.
* **Entry point**: `derive(value)` (JS function or Groovy closure).
* **Output**: `string | number | boolean | null`.
* **Ordering**: `derived` blocks run **in the order listed**; later ones may reference targets from earlier ones.
* **Sandbox**: no I/O, no reflection, no globals.
* **Limits**: ≤ **2 ms** per invocation, ≤ **128 KB** memory.
* **On error**: log + treat as `null`.

**JS snippet shape**

```js
function derive(value) {
  // compute and return a value (or null)
}
```

**Groovy snippet shape**

```groovy
def derive = { value ->
  // compute and return a value (or null)
}
```

**Null policy**

* `skip`  → don’t set `target`; other metrics may still use other fields.
* `null`  → set JSON null at `target`.
* `drop`  → exclude this event from metrics **that require** the `target` (engine may still store the raw event).

---

## 🧪 5) Local Testing (Java Driver)

Use the existing **Java JSR-223 driver** to execute the **embedded** scripts locally before publishing CRDs.

### CLI examples

```bash
# JS single source
java -jar derived-runner.jar \
  --lang js \
  --script-inline "$(yq '.spec.derived[0].script' events/http_request.yaml)" \
  --single 503
# => 5xx

# Groovy single source
java -jar derived-runner.jar \
  --lang groovy \
  --script-inline "$(yq '.spec.derived[1].script' events/http_request.yaml)" \
  --single 127
# => 100–499ms
```

### JUnit (pseudo)

```java
String script = extractScriptFromCrd("events/http_request.yaml", 0); // first derived block
Object out = DerivedScriptRunner.evalInline("js", script).single(404);
assertEquals("4xx", out);
```

> Tip: keep tiny “golden case” tables in your repo for each derived block.

---

## 🛡️ 6) Security, Performance & Ops

* **Deterministic**: one input → one output; no side effects.
* **Precompile & cache**: keyed by `(service, event, target, lang, script_sha256)`.
* **Observability**: per-target counters — `derived_ok`, `derived_null`, `derived_error`, `derived_timeout`.
* **Indexing**: only set `index: true` for fields you’ll query/aggregate on (write-amp trade-off).
* **Storage**:

    * Option A: persist derived fields in `events_raw.attributes` (traceability, replays match).
    * Option B: ephemeral (apply at ingest only) if you must minimize storage.
* **Versioning**: consider `metadata.annotations.obsinity.io/derived-rev: "<semver or git sha>"`.

---

## 📋 7) Cheat-Sheet (Field Reference)

**Event**

| Field              | Req | Notes                                                      |
| ------------------ | --- | ---------------------------------------------------------- |
| `metadata.service` | ✔   | Service short name                                         |
| `metadata.name`    | ✔   | Event type                                                 |
| `spec.schema`      | ✔   | JSON schema; set `index: true` on frequently queried paths |
| `spec.derived[]`   |     | Event-level derivations (embedded scriptlets)              |

**Derived (embedded)**

| Field         | Req | Notes                                  |      |                       |
| ------------- | --- | -------------------------------------- | ---- | --------------------- |
| `target`      | ✔   | Path for new attribute                 |      |                       |
| `source`      | ✔   | Single input attribute                 |      |                       |
| `lang`        | ✔   | `js` or `groovy`                       |      |                       |
| `script`      | ✔   | Embedded code exposing `derive(value)` |      |                       |
| `index`       |     | Add to attribute index (default false) |      |                       |
| `type`        |     | Validate/coerce return type            |      |                       |
| `whenMissing` |     | \`skip                                 | null | drop`(default`skip\`) |

**MetricCounter**

| Field                      | Req | Notes                             |
| -------------------------- | --- | --------------------------------- |
| `sourceEvent.service/name` | ✔   | Event identity                    |
| `key.dynamic[]`            | ✔   | Exact-match series key            |
| `aggregation.windowing[]`  | ✔   | Rollups (e.g., \[5s,1m,1h,1d,7d]) |
| `aggregation.operation`    | ✔   | `count`                           |
| `attributeMapping`         |     | Optional UI aliasing              |

**MetricHistogram**

| Field                               | Req | Notes                         |
| ----------------------------------- | --- | ----------------------------- |
| `value`                             | ✔   | Numeric path (raw or derived) |
| `key.dynamic[]`                     |     | Additional dims               |
| `buckets.strategy/count/min/max`    | ✔   | Bucket schema                 |
| `aggregation.windowing/percentiles` |     | Rollups & percentiles         |

---

## 🔁 8) Migration Notes

* Replace legacy **`fold`** with **`derived`**.
* Remove **filters** and **fixed constants** — keys are **exact match**.
* Move any metric-level scripts to **event-level** `spec.derived`.
* Ensure ordering if one derived depends on another’s `target`.
* Decide which derived fields need `index: true`.

---

## 🏆 Best Practices (TL;DR)

* Keep scriptlets **small, pure, and fast** (≤ 20 lines; ≤ 2 ms).
* **Index sparingly**; dimensions explode cardinality.
* Always include `[1m, 1h, 1d]` rollups; add `5s` and `7d` as needed.
* Prefer **percentiles** over averages in latency histograms.
* Write **golden tests** for each derived block using the Java driver.
