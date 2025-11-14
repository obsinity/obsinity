# (DRAFT) 📊 Obsinity: State Change Events & Counters

This document outlines the design for **state change tracking** in Obsinity, including event format, counting, snapshotting, and how to derive these events from existing raw telemetry.

---

## 🧩 Overview

Obsinity supports two key features:

1. **State Snapshot Counters**

    * Current count of objects in each state (e.g., how many accounts are "active")
    * Historical snapshots over time to support time-series graphs and retroactive analysis
2. **State Transition Counters**

    * Time-bucketed counts of transitions (e.g., how many accounts moved from "pending" → "active" in a 5m window)

All this is done **without requiring the producer to emit the old state**—Obsinity computes the `from` state by looking up the previous snapshot.

---

## 🗃️ Event Format: `state.change`

### ✅ Minimal required format:

```json
{
  "type": "state.change",
  "object_id": "account-xyz",
  "object_type": "Account",
  "attribute": "status",          // optional, defaults to "state"
  "to": "active",
  "timestamp": "2025-11-14T08:00:00Z"
}
```

* `object_id`: Unique identifier for the object.
* `object_type`: E.g., Account, Payment, SyncJob.
* `attribute`: Optional; used if multiple attributes have independent state.
* `to`: New state value.
* `timestamp`: Standard event timestamp (from raw event metadata).

> ❌ No `from` is required — Obsinity looks it up based on the current known state.

---

## 🔁 Deriving `state.change` from Raw Events

Rather than emitting explicit `state.change` events, existing raw events can be **used as source material** to derive state transitions.

### ✅ Example

Raw event:

```json
{
  "type": "account.updated",
  "account_id": "abc123",
  "status": "suspended",
  "timestamp": "2025-11-14T08:00:00Z"
}
```

Derived `state.change`:

```json
{
  "type": "state.change",
  "object_id": "abc123",
  "object_type": "Account",
  "attribute": "status",
  "to": "suspended",
  "timestamp": "2025-11-14T08:00:00Z"
}
```

---

## ⚙️ Configuration: `stateCounters` + `stateExtractors`

### `stateCounters`

```yaml
stateCounters:
  - objectType: Account
    attribute: status
    allowedStates: [pending, active, suspended, closed]
```

* Required for tracking and validation
* If state or attribute is not listed → ignore or route to dead-letter

### `stateExtractors`

```yaml
stateExtractors:
  - rawType: account.updated
    objectType: Account
    objectIdField: account_id
    stateAttributes:
      - status

  - rawType: sync.job.updated
    objectType: SyncJob
    objectIdField: job_id
    stateAttributes:
      - status
      - sync_phase
```

* Enables derived `state.change` event projection from raw input
* No timestamp field is needed — uses the raw event’s canonical `timestamp`

---

## 🧮 Metrics Emitted

### 1. `state.count`

```json
{
  "type": "state.count",
  "object_type": "Account",
  "attribute": "status",
  "state": "active",
  "count": 491,
  "timestamp": "2025-11-14T08:00:00Z"
}
```

* Emitted by snapshotting the latest known state of all objects
* Used for dashboards and trend graphs
* Snapshots are retained historically in partitioned/bucketed form (e.g. every 5s, 1m, etc.)

### 2. `state.transition.count`

```json
{
  "type": "state.transition.count",
  "object_type": "Account",
  "attribute": "status",
  "from": "pending",
  "to": "active",
  "count": 17,
  "bucket_start": "2025-11-14T08:00:00Z",
  "bucket_size": "5m"
}
```

* Emitted by aggregators that detect transitions over time
* Bucketed (5s → 1m → 1h, etc.) for trend visualizations

---

## 🗂 Storage & Partitioning

* `obs_state_change_events`: Raw or derived state change events
* `obs_current_state_snapshots`: Latest known state per object
* `obs_state_transition_counts_5s`, `_1m`, `_1h`: Transition metrics by bucket
* `obs_state_count_snapshots_5s`, `_1m`, `_1h`: Snapshot counts over time for time-series graphs
* `obs_unconfigured_state_events`: Validation failures

Partitioning follows the existing model:

```
obs_events/
  └── 2025/
      └── 11/
          └── 14/
              └── state_change/
```

---

## ✅ Implementation Plan (Staged)

| Stage | Task                                                                               |
| ----- | ---------------------------------------------------------------------------------- |
| 0️⃣   | Ingest & validate `state.change` events                                            |
| 1️⃣   | Emit `state.transition.count` from change deltas                                   |
| 2️⃣   | Implement current-state snapshotting                                               |
| 3️⃣   | Emit `state.count` snapshots periodically (5s, 1m, 1h) and store them historically |
| 4️⃣   | Enable raw→derived event projection using `stateExtractors`                        |
| 5️⃣   | Backfill transitions from historical raw event logs                                |

---

## 🧪 Notes

* Snapshots always reflect the most recent known `to` state.
* Historical snapshots are persisted for trend and analytics use cases.
* Validation layer ensures only configured types/states are counted.
* Unconfigured events are safely captured for audit/debug.
* Derived events avoid code duplication by leveraging existing telemetry.

---
