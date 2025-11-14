# 📊 Obsinity: Implicit State Change Detection & Counters

This document outlines the design for **state change detection** in Obsinity, using declarative configuration and existing raw events — without requiring any explicit `state.change` event type.

---

## 🧩 Overview

Obsinity supports two key state-driven features:

1. **State Snapshot Counters**

   * Track how many objects are currently in each state (e.g., how many accounts are "active")
   * Emit **historical snapshots** periodically for time-series trend analysis

2. **State Transition Counters**

   * Count how many times objects have transitioned between states (e.g., from `pending → active`), bucketed over time (5s, 1m, 1h, etc.)

All of this is derived **implicitly** from existing raw events, using configured extractors and a live view of current object state. **No `state.change` events need to be emitted.**

---

## 🧠 Key Concept: State is Inferred, Not Emitted

> A state change is **detected**, not declared.

Obsinity compares:

* The **new value** of a configured attribute in an incoming event
* The **current known value** for the object (from the snapshot store)

If the values differ → a state transition has occurred.

This powers both:

* Live snapshot updates
* Historical transition counter metrics

---

## ⚙️ Configuration Model

### `stateExtractors`

Define which raw event types contain stateful attributes:

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

> The system will monitor raw events of type `rawType`, extract the specified `stateAttributes`, and check for state changes by comparing with the current snapshot.

---

## 🔎 Detection Workflow

For every ingested event:

1. **Match** the event's `type` to a configured `rawType`
2. **Extract** the `object_id` and any `stateAttributes` present in the payload
3. **Look up** the current state snapshot for that object
4. **Compare** previous and new values
5. **If different:**

   * Update the snapshot
   * Record a transition (from → to)
   * Emit metrics:

      * `state.count` (current state counts)
      * `state.transition.count` (time-bucketed transitions)

No intermediate `state.change` event is required.

---

## 📈 Metrics Emitted

### 1. `state.count` (Current & Historical)

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

* Emitted periodically (5s, 1m, etc.)
* Snapshots are retained historically for time-series dashboards

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

* Emitted on transition detection
* Bucketed for time-based trend analysis

---

## 🗃 Storage & Partitioning

* `obs_current_state_snapshots`: Stores the latest state for each object
* `obs_state_count_snapshots_5s`, `_1m`, `_1h`: Historical point-in-time state counts
* `obs_state_transition_counts_5s`, `_1m`, `_1h`: Transition counts by bucket

Partitioning follows existing Obsinity strategy:

```
obs_events/
  └── 2025/
      └── 11/
          └── 14/
              └── sync.job.updated/
```

---

## ✅ Implementation Plan (Staged)

| Stage | Task                                                       |
| ----- | ---------------------------------------------------------- |
| 0️⃣   | Define and load `stateExtractors` config                   |
| 1️⃣   | Implement state comparison and snapshot update logic       |
| 2️⃣   | Emit `state.transition.count` metrics when values change   |
| 3️⃣   | Emit periodic `state.count` metrics and store historically |
| 4️⃣   | Backfill from historical raw events if needed              |

---

## 🧪 Notes & Guarantees

* State is detected via comparison: no need for `from` in events
* Snapshots are versioned and retained for reproducibility
* Transitions are only counted when `old ≠ new`
* System works with any raw event structure — as long as config is present

---

> ✅ This design minimizes duplication, maximizes reusability of raw events, and allows full historical and real-time tracking without changing the producer interface.
