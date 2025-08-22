# Obsinity Query Language (OB-SQL)

**Developer + Implementer Guide (with OB-JQL + OB-Q Java API)**

---

## 1) Overview

OB-SQL is the query language for **Obsinity Engine** (events, counters, gauges, histograms, state transitions) operating on **pre-aggregated rollups** (`5s, 1m, 1h, 1d, 7d`).

**Design points**

* **Fixed rollups only** (no dynamic schema generation).
* **WHERE = indexed only**; extra conditions go to `FILTER`.
* **Tenancy**: `USE <schema>` per query. Roles grant schema access.
* **Two modes**:

  * Event search (raw rows).
  * Aggregation (counters/gauges/histograms/state transitions).
* **Security**: users/apps authenticate → roles → schema access.

---

## 2) Schemas, Roles & Authentication

**Schema = Tenant** → `USE schema_name;`

**Roles**

```sql
GRANT USAGE ON SCHEMA finance TO role_analyst;
```

**Users & Apps**

```sql
CREATE ROLE analyst;
CREATE USER alice WITH PASSWORD 's3cr3t' IN ROLE analyst;
CREATE USER app_service WITH CERTIFICATE 'CN=app-service' IN ROLE analyst;
```

---

## 3) Query Types (OB-SQL + OB-JQL)

For every OB-SQL example, an **OB-JQL** equivalent is shown.

*(Examples omitted here for brevity — same as before, but now labelled OB-SQL instead of OBSQL. They map one-to-one with JSON as in the earlier draft.)*

---

## 4) Interval vs Rollup

* **USING ROLLUP** → one of `5s, 1m, 1h, 1d, 7d`.
* **INTERVAL <duration>** → arbitrary size, **aggregations only**.

---

## 5) Response Shapes

* **HAL-style** (`data`, `_links`, pagination).
* **Flat rows** (UI integration).

---

## 6) Implementer Notes

* Pre-created rollups only.
* **WHERE** = indexed fields, enforced by planner.
* **FILTER** = non-indexed, post-retrieval.
* **Planner** selects minimal rollup and up-aggregates.

---

## 7) Grammar (Simplified)

```ebnf
select_stmt  ::= "SELECT" select_list
                 "FROM" source
                 where_clause?
                 filter_clause?
                 rollup_clause?
                 interval_clause?
                 between_clause?
                 timezone_clause?
                 options_clause?
```

---

## 8) OB-JQL (Canonical JSON)

*(Same as before: canonical JSON form with keys: `use`, `select`, `from`, `where`, `filter`, `rollup`, `interval`, `between`, `timezone`, `options`).*

---

## 9) OB-Q (Criteria Creator, Java)

### 9.1) Java Interfaces

```java
package com.obsinity.query;

import java.util.*;

public final class OBQ {
    private String use;
    private List<Select> select = new ArrayList<>();
    private String from;
    private Condition where;
    private Condition filter;
    private String rollup;
    private String interval;
    private Range between;
    private String timezone;
    private Options options;

    // --- Nested types ---
    public record Select(String agg, String target) {}  // e.g. ("count", "*") or ("histogram", "duration_ms")

    public sealed interface Condition permits Predicate, Bool {}

    public record Predicate(String field, String op, Object value, boolean indexed) implements Condition {}
    public record PathPredicate(List<String> path, String op, Object value) implements Condition {}

    public record Bool(String type, List<Condition> conditions) implements Condition {
        // type = "and" | "or" | "not"
    }

    public record Range(String from, String to) {}
    public record Options(Integer minMatch, String sortOrder, Integer limit, Integer offset, Integer daysBack) {}

    // getters/setters/builder methods omitted for brevity
}
```

### 9.2) Builders

```java
public final class OBQBuilder {
    private final OBQ q = new OBQ();

    public static OBQBuilder use(String schema) {
        OBQBuilder b = new OBQBuilder();
        b.q.setUse(schema);
        return b;
    }

    public OBQBuilder select(String field) {
        q.getSelect().add(new OBQ.Select(null, field));
        return this;
    }

    public OBQBuilder selectAgg(String agg, String target) {
        q.getSelect().add(new OBQ.Select(agg, target));
        return this;
    }

    public OBQBuilder from(String src) {
        q.setFrom(src);
        return this;
    }

    public OBQBuilder where(OBQ.Condition c) {
        q.setWhere(c);
        return this;
    }

    public OBQBuilder filter(OBQ.Condition c) {
        q.setFilter(c);
        return this;
    }

    public OBQBuilder rollup(String r) {
        q.setRollup(r);
        return this;
    }

    public OBQBuilder interval(String i) {
        q.setInterval(i);
        return this;
    }

    public OBQBuilder between(String from, String to) {
        q.setBetween(new OBQ.Range(from, to));
        return this;
    }

    public OBQBuilder timezone(String tz) {
        q.setTimezone(tz);
        return this;
    }

    public OBQBuilder options(OBQ.Options opts) {
        q.setOptions(opts);
        return this;
    }

    public OBQ build() { return q; }
}
```

### 9.3) Emitters

* **To OB-SQL (string)**
  Walk the `OBQ` object, render `SELECT … FROM …` with clauses.
  Enforce:

  * `where` predicates must have `indexed = true`.
  * aggregations require `rollup` or `interval`.

* **To OB-JQL (JSON)**
  Serialize `OBQ` → JSON (Jackson/Gson/etc).

```java
public interface OBQEmitter {
    String toOBSQL(OBQ query);
    String toOBJQL(OBQ query); // JSON string
}
```

---

## 10) Example (Java → OB-SQL & OB-JQL)

```java
OBQ q = OBQBuilder.use("finance")
    .select("event_id").select("timestamp").select("user_id").select("status")
    .from("ConsentStatusChange")
    .where(new OBQ.Bool("and", List.of(
        new OBQ.Predicate("consent_id","eq","abc123",true),
        new OBQ.Predicate("status","eq","ACTIVE",true)
    )))
    .filter(new OBQ.PathPredicate(List.of("attributes","region"),"eq","EU"))
    .options(new OBQ.Options(1,"asc",50,null,7))
    .build();

String sql = emitter.toOBSQL(q);   // → OB-SQL string
String jql = emitter.toOBJQL(q);   // → OB-JQL JSON
```
