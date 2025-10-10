# 📊 Obsinity Engine: Event & Counter Flow Spec

> **Source of truth:** All telemetry flows through `FlowEvent` (OTEL-shaped container with Obsinity-native fields).
> **Hard rules:**
>
> * ✅ **Idempotency:** duplicates are strict no-ops.
> * 📦 **Raw first:** every accepted event is stored in full.
> * ⏱️ **Max rollup horizon:** 7 days (no higher materialisation).
> * 🗂️ **Indexes selective, raw is canonical.**

---

## 1️⃣ Telemetry Envelope (OTEL-like)

**Core identity & timing**

* `name` 🏷️ — span/operation name.
* `timestamp` / `timeUnixNano` ⏰ — event start; must align.
* `endTimestamp` ⏳ — optional event end.

**Trace context**

* `traceId`, `spanId`, `parentSpanId` 🔗 — OTEL trace structure.
* `kind` 🎭 — OTEL `SpanKind` (SERVER, CLIENT, PRODUCER, CONSUMER, INTERNAL).

**Service identity**

* `serviceId` or `resource["service.id"]` 🛠️ — required; if both exist, must match.

**Resource & attributes**

* `resource` 🌍 — stable metadata (service, region, env, version).
* `attributes` 📋 — arbitrary key/values; all stored raw, only declared ones indexed.

**Relationships & outcome**

* `events` 🗒️ — child annotations/steps.
* `links` 🔀 — cross-span references.
* `status` ✅/❌ — success/error with message.

**Operational flags**

* `correlationId` 🔑 — business correlation.
* `synthetic` 🧪 — synthetic/test marker.

**Runtime only (non-serialized)**

* `eventContext` 🧩 — flow scratchpad.
* `throwable` 💥 — runtime error reference.
* `step`, `startNanoTime`, `eventStack` 🔄 — step emulation metadata.

---

## 2️⃣ Ingestion Flow

1. **Validation 🚦**

    * Service ID present & consistent.
    * Timestamp sane.
    * Event type allowed.
    * Attribute counts within guardrails.

2. **Idempotency Gate 🔐**

    * First `event_id` → accepted.
    * Replay same payload → ignored (no raw, no index, no metrics).
    * Replay different payload → conflict, rejected, no side effects.

3. **Routing 📬**

    * Store **raw event** (canonical).
    * Populate **indexes** (declared paths only).
    * Derive **metrics** (counters, histograms, gauges, states).

---

## 3️⃣ Raw Storage 📦

* Full event persisted exactly as received.
* Immutable, partitioned, auditable.
* Holds **all attributes**, even those not indexed.
* Canonical source for rebuilds & backfills.

---

## 4️⃣ Selective Indexing 🗂️

* Only declared paths are indexed (e.g., `service.id`, `region`, `http.status_code`).
* Index used exclusively for `WHERE` pruning.
* Raw always retained for `FILTER`.
* Index entries created **only** for newly accepted events.

---

## 5️⃣ Metrics Derivation & 5-Second Buffers ⏱️

* Metrics derived from event semantics:

    * 🔢 Counters → increment sums.
    * 📈 Gauges → avg/min/max/last (policy).
    * 📊 Histograms → merge bucket counts.
    * 🔄 States → track transitions.

* Keyed by `(tenant, metric, dimension tuple, 5s bucket)`.

**Flush triggers**

* End of 5-second window.
* Memory thresholds.
* Shutdown/rotation.

**Flush behavior**

* Exactly one record per key per 5s bucket.
* Idempotent writes.
* Duplicates never reach buffer.

---

## 6️⃣ Materialised Rollups 📐

* Fixed ladder: `5s → 1m → 1h → 1d → 7d`.
* Continuous workers aggregate forward.
* Functions:

    * Sum (counters)
    * Merge (histograms)
    * Policy (gauges)
    * Rules (states)
* ✅ Idempotent, rebuildable, deterministic.
* ❌ Nothing above 7d is materialised.

---

## 7️⃣ Querying 🔎

* **OB-SQL / OB-JQL surface** — PostgreSQL hidden.
* `WHERE` → indexed pruning.
* `FILTER` → raw evaluation.
* `USING ROLLUP` → pick exact level (≤7d).
* `INTERVAL <duration>` → re-bucket from nearest rollup ≤ interval.

**Examples**

* `30s` → from 5s buckets.
* `15m` → from 1m buckets.
* `3h` → from 1h buckets.
* `9d` → composed from 1d/7d (not materialised).

**Response formats**

* 🧾 Row-oriented: one row per (time bucket × dimension tuple).
* 📊 Series/bucketed: compact arrays per tuple.

---

## 8️⃣ Guarantees & SLOs 📜

* **Idempotency:** strict — duplicates = no effect.
* **Freshness:** 5s metrics visible after one flush; rollups lag by one sweep.
* **Determinism:** rollups associative/commutative; results stable.
* **Consistency:** queries always on materialised data; no ad-hoc query-time recompute.
* **Rebuildability:** indexes/rollups always reconstructable from raw.

---

## 9️⃣ Configurable Knobs ⚙️

* `rollups.enabled` → `[5s,1m,1h,1d,7d]`
* `rollups.maxMaterialised` → `7d`
* `interval.crossBoundaryPolicy` → `includePartial | truncate | padWithNulls`
* `interval.requireAligned` → `false | true`
* `buffer.flush.thresholds` → memory/size triggers
* `dimensions.maxCardinality` → per-metric guardrail
* `index.declaredPaths` → explicit indexed attributes
* `ingest.rejectOnConflict` → true/false

---
