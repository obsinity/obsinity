Here is a prompt you can feed to Codex:

---

**Context:**

`OBJqlCteBuilder.java` builds a multi-CTE native SQL query against two partitioned Postgres tables:

- `events_raw` — partitioned `LIST(service_partition_key)` → `RANGE(started_at)`
- `event_attr_index` — same two-level partitioning scheme; PK is `(service_partition_key, started_at, event_id, attr_name, attr_value)`

A time range (`ts_start`, `ts_end`) is **always** supplied. The `base` CTE filters `events_raw` by `service_partition_key` and `started_at` range and selects `(event_id, started_at)`.

---

**Problem:**

There are two places where `started_at` is dropped from a join/select, preventing Postgres from using the full PK for partition pruning and direct lookups:

**1. `appendAttrCte` — attr index join only uses `event_id`:**
```java
sql.append("  JOIN base b ON b.event_id = x.event_id\n");
```
`started_at` is not included, so Postgres cannot prune `event_attr_index` partitions per-row. It must scan all service partitions in scope.

**2. `page` CTE — drops `started_at`, so the final fetch cannot do a targeted PK lookup:**
```java
// hasAttrMatch branch:
.append("  SELECT event_id FROM ordered OFFSET :off LIMIT :lim\n")
// no-attr branch:
.append("  SELECT event_id FROM ordered OFFSET :off LIMIT :lim\n")
```
The final fetch joins only on `pg.event_id = e.event_id`, so Postgres cannot eliminate individual partitions per page row.

---

**Required changes:**

1. **`appendAttrCte`**: change the SELECT and JOIN to carry `started_at`:
   - `SELECT x.event_id, x.started_at`
   - `JOIN base b ON b.event_id = x.event_id AND b.started_at = x.started_at`

2. **`page` CTE** (both the `hasAttrMatch` branch and the no-attr branch): select `started_at` as well:
   - `SELECT event_id, started_at FROM ordered OFFSET :off LIMIT :lim`

3. **`matched` / set expression CTEs** (`renderSetExpr`, `render`, flat INTERSECT block): propagate `started_at` through every `SELECT event_id FROM <cte>` so the `matched_base` join can use it.

4. **`matched_base` CTE join** (if present): add `started_at` to the join condition:
   - `JOIN matched m ON m.event_id = b.event_id AND m.started_at = b.started_at`

5. **Final fetch join**:
   - `JOIN page pg ON pg.event_id = e.event_id AND pg.started_at = e.started_at`

Ensure `started_at` flows consistently through every intermediate CTE so no ambiguity or column-not-found errors arise. Do not change the public API (`Built`, constructor, `build` signature). Do not add new query parameters.
