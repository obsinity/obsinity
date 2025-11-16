# 📊 Obsinity CRDs — Developer Guide (Embedded Scriptlets)

Obsinity models observability with **declarative CRDs**. At ingest, each event is:

1. validated against its **Event schema**,
2. **derived** attributes are computed (from embedded scriptlets), and
3. the enriched event feeds **MetricCounters** and **MetricHistograms**.

No metric contains scripts. No runtime WHERE. A series is identified solely by its **exact key**.

---

## 🧱 Service Config Snapshot (Server View)

Controllers ingest CRDs (event + metric YAML) and materialise them into a `ServiceConfig` record:

```json
{
  "service": "payments",
  "snapshotId": "config-2025-11-14T15:30:00Z",
  "createdAt": "2025-11-14T15:30:00Z",
  "defaults": { "rollups": ["5s","1m","1h","1d"], "backfillWindow": null },
  "events": [
    {
      "eventName": "http_request",
      "eventNorm": "http_request",
      "category": "http",
      "metrics": [ { "name": "http_requests_by_status", ... } ],
      "attributeIndex": { "indexed": ["http.status","http.route"] },
      "retentionTtl": "7d"
    }
  ],
  "stateExtractors": [
    { "rawType": "user_profile.updated", "objectType": "UserProfile", "objectIdField": "user.profile_id", "stateAttributes": ["user.status"] }
  ]
}
```

`obsinity-controller-admin` accepts either JSON `ServiceConfig` payloads (`POST /api/admin/config/service`) or `.tar/.tar.gz` archives shaped like `services/<service>/events/<event>/…` and produces the same server-side structure. `obsinity.config.init.*` properties (see `obsinity-reference-service`) point to a directory of CRDs to auto-load.

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

> **Note on flows vs. steps:** `@Flow` and `@Step` instrumentation each materialise as their **own Event definitions**. A step is not embedded inside the flow’s CRD; instead it becomes a distinct event (e.g., `checkout.step.payment`) that references the same service partition key. This keeps schemas small and lets you version or retire steps independently while still linking them at query-time via the parent/child relationship stored in the raw event payload.

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
    dimensions: [ <path>, ... ]   # exact-match identity (e.g., [http.method, http.route, http.status_code_group])
  fold:                          # optional, rename/bundle source attributes before counting
    sourceAttribute: http.status
    foldedAttribute: http.status_group
    rules:
      - name: "5xx"
        when: { gte: 500, lt: 600 }
        output: "5xx"
  filters:                       # optional map or array of filter definitions, carried verbatim into the spec
    includeStatus: ["2xx", "5xx"]

  rollup:
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

  rollup:
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

  value: <path>                  # optional override; defaults to event elapsed time
  key:
    dynamic: [ <path>, ... ]     # optional additional dims (e.g., http.method, http.route)

  sketch:
    kind: ddsketch               # first-class latency storage
    relativeAccuracy: 0.01       # 1% default

  # Optional alternative when explicit bins are required. Skipped when sketch.kind = ddsketch.
  buckets:
    strategy: fixed|log|custom
    count: <int>
    min: <number>
    max: <number>

  rollup:
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
  value: http.server.duration_ms        # defaults to elapsed time if omitted
  key:
    dynamic: [ http.method, http.route ]
  sketch:
    kind: ddsketch
    relativeAccuracy: 0.01
  rollup:
    windowing: { granularities: [5s, 1m, 1h, 1d, 7d] }
    percentiles: [0.5, 0.9, 0.95, 0.99]
  retention:
    ttl: "90d"                # optional metric retention window
```

---

## 🔁 4) State Extractor Config

State trackers tell the ingest pipeline which raw events contain stateful attributes. They live alongside events as `state-extractors.yaml`:

```yaml
service: payments
stateExtractors:
  - rawType: user_profile.updated
    objectType: UserProfile
    objectIdField: user.profile_id
    stateAttributes:
      - user.status
      - billing.tier
```

- `rawType` matches the event name emitted by producers.
- `objectType` is a logical name used for reporting/querying transitions.
- `objectIdField` is a dotted attribute path pointing to the identity of the object being tracked.
- `stateAttributes` is a list of dotted attribute paths. When the value changes, Obsinity updates the snapshot table, emits state-count deltas, and increments the `state.transition.count` buffer for that attribute.

The ingest pipeline can be toggled with `obsinity.stateExtractors.enabled=true|false` and `obsinity.stateExtractors.loggingEnabled=true|false`. Transition counts flush through the same counter infrastructure (5s → 7d rollups) and are queried via `/api/query/state-transitions`.

---

## 🧮 5) Embedded Scriptlets — Runtime Contract

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

## 🧪 6) Local Testing (Java Driver)

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

## 🛡️ 7) Security, Performance & Ops

* **Deterministic**: one input → one output; no side effects.
* **Precompile & cache**: keyed by `(service, event, target, lang, script_sha256)`.
* **Observability**: per-target counters — `derived_ok`, `derived_null`, `derived_error`, `derived_timeout`.
* **Indexing**: only set `index: true` for fields you’ll query/rollup on (write-amp trade-off).
* **Storage**:

    * Option A: persist derived fields in `events_raw.attributes` (traceability, replays match).
    * Option B: ephemeral (apply at ingest only) if you must minimize storage.
* **Versioning**: consider `metadata.annotations.obsinity.io/derived-rev: "<semver or git sha>"`.

---

## ♻️ 7.1) Schema Evolution & Compatibility (Attributes)

- No explicit version field is required on the attribute schema; however, all schema changes MUST be backward compatible.
- New fields MUST be optional by default. If a new field is intended to be indexed (`index: true`), roll out in two phases:
  1. Update producers first so they begin emitting the new field (SDKs or services generate the attribute into events).
  2. Update the CRD to allow the additional field (keep it optional) and, once producers are broadly updated, enable `index: true`.
- During the rollout window, events missing the new field will be accepted. Retrospective metrics and index regeneration will NOT backfill missing values for events that did not originally contain the field.
- Removing fields or changing types is discouraged; prefer additive changes or add a new field with a new name.

### Compatibility Checklist (Authoring & Rollout)

1) Adding a new attribute path
   - Author CRD: add the field under `spec.schema.properties` as optional (no `required`, no `index: true`).
   - Deploy producer changes first (SDK/services start emitting the field).
   - After most producers are updated, set `index: true` only if you plan to query on this field.

2) Adding a new indexed field
   - Same as above, but split deployment into two steps: (a) producers first, (b) CRD `index: true` later.
   - Expect partial nulls during rollout; queries should handle missing values.

3) Changing a field’s type
   - Avoid in place. Instead, add a new field (e.g., `foo_v2`) with the desired type.
   - Migrate producers to emit the new field; deprecate the old field over time.

4) Renaming or removing a field
   - Avoid renames/removals. Add the new field, keep the old field for compatibility.
   - Optionally ship a derived mapping to populate the new field from the old during transition.

5) Validating compatibility
   - Ensure `spec.schema` remains backward compatible: new fields optional, no tightening of existing constraints.
   - Run synthetic ingest tests with a mix of old/new payloads; confirm no rejects.

6) Index & metrics regeneration expectations
   - Attribute index and metric rollups do not backfill missing fields for past events.
   - If backfill is required, plan a one‑off job that reads raw events and emits enriched copies (outside normal ingest SLA).

---

## 📋 8) Cheat-Sheet (Field Reference)

**Event**

| Field              | Req | Notes                                                      |
| ------------------ | --- | ---------------------------------------------------------- |
| `metadata.service` | ✔   | Service partition key                                      |
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
| `rollup.windowing[]`  | ✔   | Rollups (e.g., \[5s,1m,1h,1d,7d]) |
| `rollup.operation`    | ✔   | `count`                           |
| `attributeMapping`         |     | Optional UI aliasing              |

**MetricHistogram**

| Field                               | Req | Notes                         |
| ----------------------------------- | --- | ----------------------------- |
| `value`                             | ✔   | Numeric path (raw or derived) |
| `key.dynamic[]`                     |     | Additional dims               |
| `buckets.strategy/count/min/max`    | ✔   | Bucket schema                 |
| `rollup.windowing/percentiles` |     | Rollups & percentiles         |

---

## 🔁 9) Migration Notes

* Replace legacy **`fold`** with **`derived`**.
* Remove **filters** and **fixed constants** — keys are **exact match**.
* Move any metric-level scripts to **event-level** `spec.derived`.
* Ensure ordering if one derived depends on another’s `target`.
* Decide which derived fields need `index: true`.

---

## 🏆 10) Best Practices (TL;DR)

* Keep scriptlets **small, pure, and fast** (≤ 20 lines; ≤ 2 ms).
* **Index sparingly**; dimensions explode cardinality.
* Always include `[1m, 1h, 1d]` rollups; add `5s` and `7d` as needed.
* Prefer **percentiles** over averages in latency histograms.
* Write **golden tests** for each derived block using the Java driver.
