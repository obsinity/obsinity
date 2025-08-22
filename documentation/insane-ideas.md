# **Obsinity Query Language (OB‑SQL)**

**Developer + Implementer Guide**
*(with OB‑JQL + OB‑Q Java API + Reference Emitters)*

---

## 1) Overview

OB‑SQL queries **pre‑aggregated observability data** (events, counters, gauges, histograms, state transitions) on fixed rollups: `5s, 1m, 1h, 1d, 7d`.

**Principles**

* **WHERE**: only **indexed fields**.
* **FILTER**: additional predicates on **attributes‑relative** paths (post‑index).
* **Aggregation**: requires **`USING ROLLUP`** or **`INTERVAL`** (arbitrary duration).
* **Tenancy**: `USE <schema>`; roles grant schema access.
* **Safety**: Java **OB‑Q builder** quotes strings & validates; emitters are deterministic stringifiers.

---

## 2) OB‑SQL Essentials

### 2.1 Clauses

* `USE <schema>;`
* `SELECT … FROM <source>`
* `WHERE <indexed conditions>`
* `FILTER <attributes‑relative conditions>`
* `USING ROLLUP <5s|1m|1h|1d|7d>` **or** `INTERVAL <duration>` *(aggregations only)*
* `BETWEEN '<from‑iso>' AND '<to‑iso>'`
* `TIMEZONE '<IANA/Zone>'`
* `OPTIONS (sortOrder = 'asc|desc', limit = N, offset = N, daysBack = N)`

### 2.2 Predicates

**Field predicates (WHERE):**

```
field = 'x' | <> | > | >= | < | <= | IN (...) | NOT IN (...) | LIKE 'pattern' | NOT LIKE 'pattern'
```

**Path predicates (FILTER, attributes‑relative):**

```
attributes->'k1'->'k2' = 'x' | ... | IN (...) | LIKE 'pattern'
```

**MATCHES (NEW): at least N sub‑conditions must hold**

```sql
MATCHES <N> OF (
  <predicate-or-boolean>,
  <predicate-or-boolean>,
  ...
)
```

* Allowed in `WHERE` (sub‑conditions must be **indexed** field predicates / boolean compositions of them)
  or in `FILTER` (sub‑conditions must be **attributes‑relative** path predicates / boolean comps).

---

## 3) OB‑JQL (Canonical JSON)

### 3.1 Top‑level

```json
{
  "use": "schema",
  "select": [
    "field",
    { "agg": "count|gauge|histogram|state_transition", "target": "field|*", "params": {} }
  ],
  "from": "source",
  "where": { /* Bool | FieldPredicate | Matches */ },
  "filter": { /* Bool | PathPredicate | Matches */ },
  "rollup": "5s|1m|1h|1d|7d",
  "interval": "duration",
  "between": { "from": "ISO-8601", "to": "ISO-8601" },
  "timezone": "IANA/Zone",
  "options": { "sortOrder": "asc|desc", "limit": 100, "offset": 0, "daysBack": 7 }
}
```

### 3.2 Predicates

**Field (WHERE):**

```json
{ "field": "api_name", "op": "eq|ne|gt|gte|lt|lte|in|nin|like|prefix", "value": "'…'", "indexed": true }
```

**Path (FILTER, attributes‑relative):**

```json
{ "path": ["region"], "op": "eq|ne|in|nin|like|prefix", "value": "'EU'" }
```

**Boolean:**

```json
{ "and": [ /* conditions */ ] }
{ "or":  [ /* conditions */ ] }
{ "not": { /* condition  */ ] }
```

**MATCHES:**

```json
{ "matches": { "require": 2, "conditions": [ /* conditions */ ] } }
```

**Rules**

* `where` must contain **indexed** field predicates (including inside `matches`).
* `filter` uses **path** predicates (attributes‑relative) and may include `matches`.
* Aggregations: exactly one of `rollup` or `interval`.

---

## 4) OB‑SQL ⇄ OB‑JQL Examples

### 4.1 Event search (basic)

**OB‑SQL**

```sql
USE finance;

SELECT event_id, timestamp, user_id, status
FROM ConsentStatusChange
WHERE consent_id = 'abc123' AND status = 'ACTIVE'
FILTER attributes->'region' = 'EU'
OPTIONS (sortOrder = 'asc', limit = 50, daysBack = 7);
```

**OB‑JQL**

```json
{
  "use": "finance",
  "select": ["event_id","timestamp","user_id","status"],
  "from": "ConsentStatusChange",
  "where": {
    "and": [
      { "field": "consent_id", "op": "eq", "value": "'abc123'", "indexed": true },
      { "field": "status", "op": "eq", "value": "'ACTIVE'", "indexed": true }
    ]
  },
  "filter": { "and": [ { "path": ["region"], "op": "eq", "value": "'EU'" } ] },
  "options": { "sortOrder": "asc", "limit": 50, "daysBack": 7 }
}
```

---

### 4.2 Event search with **MATCHES** (replaces OR+minMatch)

**OB‑SQL**

```sql
USE finance;

SELECT event_id, timestamp
FROM api_request
WHERE api_name = 'get_account_balance'
  AND MATCHES 2 OF (
    http_status_code = '200',
    region_code = 'EU',
    user_id LIKE 'svc-%'
  )
OPTIONS (sortOrder = 'asc', limit = 50, offset = 0, daysBack = 7);
```

**OB‑JQL**

```json
{
  "use": "finance",
  "select": ["event_id","timestamp"],
  "from": "api_request",
  "where": {
    "and": [
      { "field": "api_name", "op": "eq", "value": "'get_account_balance'", "indexed": true },
      { "matches": {
        "require": 2,
        "conditions": [
          { "field": "http_status_code", "op": "eq", "value": "'200'", "indexed": true },
          { "field": "region_code", "op": "eq", "value": "'EU'", "indexed": true },
          { "field": "user_id", "op": "prefix", "value": "'svc-'", "indexed": true }
        ]
      }}
    ]
  },
  "options": { "sortOrder": "asc", "limit": 50, "offset": 0, "daysBack": 7 }
}
```

---

### 4.3 Counters

**OB‑SQL**

```sql
USE finance;

SELECT count(*)
FROM api_request
WHERE api_name IN ('create_project','create_transaction','get_account_balance')
  AND http_status_code IN ('200','400','403','500')
USING ROLLUP 30m
BETWEEN '2025-04-04T00:00:00Z' AND '2025-07-20T23:59:59Z'
TIMEZONE 'America/New_York'
OPTIONS (offset = 0, limit = 100);
```

**OB‑JQL**

```json
{
  "use": "finance",
  "select": [{ "agg": "count", "target": "*" }],
  "from": "api_request",
  "where": {
    "and": [
      { "field": "api_name", "op": "in", "value": ["'create_project'","'create_transaction'","'get_account_balance'"], "indexed": true },
      { "field": "http_status_code", "op": "in", "value": ["'200'","'400'","'403'","'500'"], "indexed": true }
    ]
  },
  "rollup": "30m",
  "between": { "from": "2025-04-04T00:00:00Z", "to": "2025-07-20T23:59:59Z" },
  "timezone": "America/New_York",
  "options": { "offset": 0, "limit": 100 }
}
```

---

### 4.4 Gauges

**OB‑SQL**

```sql
USE finance;

SELECT gauge(balance)
FROM account_balance
WHERE account_id = 'xyz'
USING ROLLUP 1h
BETWEEN '2025-07-01T00:00:00Z' AND '2025-07-05T00:00:00Z';
```

**OB‑JQL**

```json
{
  "use": "finance",
  "select": [{ "agg": "gauge", "target": "balance" }],
  "from": "account_balance",
  "where": { "and": [ { "field": "account_id", "op": "eq", "value": "'xyz'", "indexed": true } ] },
  "rollup": "1h",
  "between": { "from": "2025-07-01T00:00:00Z", "to": "2025-07-05T00:00:00Z" }
}
```

---

### 4.5 Histograms (with params)

**OB‑SQL**

```sql
USE finance;

SELECT histogram(duration_ms)
FROM api_latency
WHERE api_name = 'get_account_balance'
USING ROLLUP 1h
BETWEEN '2025-07-01T00:00:00Z' AND '2025-07-02T00:00:00Z';
```

**OB‑JQL**

```json
{
  "use": "finance",
  "select": [{ "agg": "histogram", "target": "duration_ms", "params": { "buckets": [50,100,500] } }],
  "from": "api_latency",
  "where": { "and": [ { "field": "api_name", "op": "eq", "value": "'get_account_balance'", "indexed": true } ] },
  "rollup": "1h",
  "between": { "from": "2025-07-01T00:00:00Z", "to": "2025-07-02T00:00:00Z" }
}
```

---

### 4.6 State transitions

**OB‑SQL**

```sql
USE finance;

SELECT state_transition(consent_status)
FROM ConsentStatusChange
WHERE consent_id = 'abc123'
USING ROLLUP 1d
BETWEEN '2025-07-01T00:00:00Z' AND '2025-07-05T00:00:00Z';
```

**OB‑JQL**

```json
{
  "use": "finance",
  "select": [{ "agg": "state_transition", "target": "consent_status" }],
  "from": "ConsentStatusChange",
  "where": { "and": [ { "field": "consent_id", "op": "eq", "value": "'abc123'", "indexed": true } ] },
  "rollup": "1d",
  "between": { "from": "2025-07-01T00:00:00Z", "to": "2025-07-05T00:00:00Z" }
}
```

---

### 4.7 INTERVAL (arbitrary aggregation)

**OB‑SQL**

```sql
USE finance;

SELECT count(*)
FROM api_request
WHERE api_name = 'create_project'
INTERVAL 37m
BETWEEN '2025-07-01T00:00:00Z' AND '2025-07-01T12:00:00Z';
```

**OB‑JQL**

```json
{
  "use": "finance",
  "select": [{ "agg": "count", "target": "*" }],
  "from": "api_request",
  "where": { "and": [ { "field": "api_name", "op": "eq", "value": "'create_project'", "indexed": true } ] },
  "interval": "37m",
  "between": { "from": "2025-07-01T00:00:00Z", "to": "2025-07-01T12:00:00Z" }
}
```

---

## 5) Grammar (simplified)

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

where_clause   ::= "WHERE" condition
filter_clause  ::= "FILTER" condition

condition      ::= predicate
                 | "(" condition ")"
                 | condition "AND" condition
                 | condition "OR"  condition
                 | "NOT" condition
                 | matches

matches        ::= "MATCHES" integer "OF" "(" condition_list ")"
condition_list ::= condition ("," condition)*

predicate      ::= field_pred | path_pred

field_pred     ::= field cmp value
                 | field "IN" "(" value_list ")"
                 | field "NOT" "IN" "(" value_list ")"
                 | field "LIKE" value
                 | field "NOT" "LIKE" value
cmp            ::= "=" | "<>" | ">" | ">=" | "<" | "<="

path_pred      ::= attributes_path cmp value
                 | attributes_path "IN" "(" value_list ")"
                 | attributes_path "NOT" "IN" "(" value_list ")"
                 | attributes_path "LIKE" value

attributes_path::= "attributes->'" key "'"
                 | attributes_path "->'" key "'"
```

---

## 6) OB‑Q (Java) — Model & Builder

> The builder **quotes/escapes strings**, leaves numbers unquoted, marks WHERE field predicates as `indexed=true`, and enforces aggregation rules.

### 6.1 Model

```java
package com.obsinity.query;

import java.util.*;

public final class OBQ {
    private String use;
    private List<Select> select = new ArrayList<>();
    private String from;
    private Condition where;   // indexed only
    private Condition filter;  // attributes-relative
    private String rollup;
    private String interval;
    private Range between;
    private String timezone;
    private Options options;

    /* --- Types --- */
    public static final class Select {
        public final String agg;     // null = plain field
        public final String target;  // field or "*"
        public final Map<String,Object> params;
        public Select(String agg, String target) { this(agg, target, Map.of()); }
        public Select(String agg, String target, Map<String,Object> params) {
            this.agg = agg; this.target = target; this.params = params == null ? Map.of() : params;
        }
    }

    public sealed interface Condition permits Predicate, PathPredicate, Bool, MatchesGroup {}

    /** WHERE field predicate (indexed) */
    public static final class Predicate implements Condition {
        public final String field, op; public final Object value; public final boolean indexed;
        public Predicate(String field, String op, Object value, boolean indexed) {
            this.field = field; this.op = op; this.value = value; this.indexed = indexed;
        }
    }

    /** FILTER path predicate (attributes-relative) */
    public static final class PathPredicate implements Condition {
        public final List<String> path; public final String op; public final Object value;
        public PathPredicate(List<String> path, String op, Object value) {
            this.path = path; this.op = op; this.value = value;
        }
    }

    /** Boolean group */
    public static final class Bool implements Condition {
        public final String type; // "and"|"or"|"not"
        public final List<Condition> conditions;
        public Bool(String type, List<Condition> conditions) {
            this.type = type; this.conditions = conditions;
        }
    }

    /** MATCHES group */
    public static final class MatchesGroup implements Condition {
        public final int require; public final List<Condition> conditions;
        public MatchesGroup(int require, List<Condition> conditions) {
            this.require = require; this.conditions = conditions;
        }
    }

    public record Range(String from, String to) {}
    public record Options(String sortOrder, Integer limit, Integer offset, Integer daysBack) {}

    /* getters/setters omitted for brevity */
}
```

### 6.2 Builder (helpers + quoting)

```java
package com.obsinity.query;

import java.util.*;
import static java.util.Arrays.asList;

public final class OBQBuilder {
    private final OBQ q = new OBQ();

    public static OBQBuilder use(String schema){ var b = new OBQBuilder(); b.q.setUse(schema); return b; }
    public OBQBuilder from(String src){ q.setFrom(src); return this; }
    public OBQBuilder select(String field){ q.getSelect().add(new OBQ.Select(null, field)); return this; }
    public OBQBuilder count(){ q.getSelect().add(new OBQ.Select("count","*")); return this; }
    public OBQBuilder gauge(String field){ q.getSelect().add(new OBQ.Select("gauge", field)); return this; }
    public OBQBuilder histogram(String field, Map<String,Object> params){ q.getSelect().add(new OBQ.Select("histogram", field, params)); return this; }
    public OBQBuilder stateTransition(String field){ q.getSelect().add(new OBQ.Select("state_transition", field)); return this; }

    public OBQBuilder where(OBQ.Condition c){ q.setWhere(c); return this; }
    public OBQBuilder filter(OBQ.Condition c){ q.setFilter(c); return this; }
    public OBQBuilder rollup(String r){ q.setRollup(r); return this; }
    public OBQBuilder interval(String i){ q.setInterval(i); return this; }
    public OBQBuilder between(String from, String to){ q.setBetween(new OBQ.Range(from, to)); return this; }
    public OBQBuilder timezone(String tz){ q.setTimezone(tz); return this; }
    public OBQBuilder options(String sortOrder, Integer limit, Integer offset, Integer daysBack){
        q.setOptions(new OBQ.Options(sortOrder, limit, offset, daysBack)); return this;
    }
    public OBQ build(){ return q; }

    /* Boolean helpers */
    public static OBQ.Condition and(OBQ.Condition... cs){ return new OBQ.Bool("and", asList(cs)); }
    public static OBQ.Condition or (OBQ.Condition... cs){ return new OBQ.Bool("or",  asList(cs)); }
    public static OBQ.Condition not(OBQ.Condition c){ return new OBQ.Bool("not", List.of(c)); }
    public static OBQ.Condition matches(int require, OBQ.Condition... cs){ return new OBQ.MatchesGroup(require, asList(cs)); }

    /* WHERE (indexed) */
    public static OBQ.Predicate eq(String f, String v){ return new OBQ.Predicate(f,"eq",  quote(v), true); }
    public static OBQ.Predicate ne(String f, String v){ return new OBQ.Predicate(f,"ne",  quote(v), true); }
    public static OBQ.Predicate gt(String f, Number v){ return new OBQ.Predicate(f,"gt",  v, true); }
    public static OBQ.Predicate gte(String f,Number v){ return new OBQ.Predicate(f,"gte", v, true); }
    public static OBQ.Predicate lt(String f, Number v){ return new OBQ.Predicate(f,"lt",  v, true); }
    public static OBQ.Predicate lte(String f,Number v){ return new OBQ.Predicate(f,"lte", v, true); }
    public static OBQ.Predicate in(String f, List<String> vs){ return new OBQ.Predicate(f,"in",  quoteAll(vs), true); }
    public static OBQ.Predicate nin(String f,List<String> vs){ return new OBQ.Predicate(f,"nin", quoteAll(vs), true); }
    public static OBQ.Predicate prefix(String f,String v){ return new OBQ.Predicate(f,"prefix", quote(v), true); }
    public static OBQ.Predicate like(String f,  String v){ return new OBQ.Predicate(f,"like",   quote(v), true); }

    /* FILTER (attributes‑relative paths) */
    public static OBQ.PathPredicate filterEq (List<String> path, String v){ return new OBQ.PathPredicate(path,"eq",  quote(v)); }
    public static OBQ.PathPredicate filterNe (List<String> path, String v){ return new OBQ.PathPredicate(path,"ne",  quote(v)); }
    public static OBQ.PathPredicate filterIn (List<String> path, List<String> vs){ return new OBQ.PathPredicate(path,"in",  quoteAll(vs)); }
    public static OBQ.PathPredicate filterNin(List<String> path, List<String> vs){ return new OBQ.PathPredicate(path,"nin", quoteAll(vs)); }
    public static OBQ.PathPredicate filterLike(List<String> path, String v){ return new OBQ.PathPredicate(path,"like", quote(v)); }
    public static OBQ.PathPredicate filterPrefix(List<String> path, String v){ return new OBQ.PathPredicate(path,"prefix", quote(v)); }

    /* quoting */
    private static String quote(String v){ return "'" + v.replace("'", "''") + "'"; }
    private static List<String> quoteAll(List<String> vs){
        List<String> out = new ArrayList<>(vs.size());
        for (String v : vs) out.add(quote(v));
        return out;
    }
}
```

---

## 7) Reference Emitters

### 7.1 OB‑SQL & OB‑JQL Emitter (full source)

```java
package com.obsinity.query.emit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.query.OBQ;

import java.util.*;
import java.util.stream.Collectors;

public final class OBQEmitter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /* ===== Public API ===== */

    public String toOBSQL(OBQ q) {
        validate(q);

        List<String> lines = new ArrayList<>();
        if (nonEmpty(q.getUse())) {
            lines.add("USE " + q.getUse() + ";");
            lines.add("");
        }

        lines.add("SELECT " + renderSelect(q.getSelect()));

        if (!nonEmpty(q.getFrom()))
            throw new IllegalArgumentException("FROM (source) is required");
        lines.add("FROM " + q.getFrom());

        if (q.getWhere() != null) {
            String where = renderCondition(q.getWhere(), false);
            if (nonEmpty(where)) lines.add("WHERE " + where);
        }

        if (q.getFilter() != null) {
            String filter = renderCondition(q.getFilter(), true);
            if (nonEmpty(filter)) lines.add("FILTER " + filter);
        }

        boolean hasAgg = q.getSelect().stream().anyMatch(s -> s.agg != null);
        if (hasAgg) {
            if (nonEmpty(q.getRollup())) lines.add("USING ROLLUP " + q.getRollup());
            else if (nonEmpty(q.getInterval())) lines.add("INTERVAL " + q.getInterval());
        }

        if (q.getBetween() != null) {
            lines.add("BETWEEN '" + q.getBetween().from() + "' AND '" + q.getBetween().to() + "'");
        }

        if (nonEmpty(q.getTimezone())) {
            lines.add("TIMEZONE '" + q.getTimezone() + "'");
        }

        String options = renderOptions(q);
        if (nonEmpty(options)) lines.add(options);

        String sql = String.join("\n", lines);
        if (!sql.trim().endsWith(";")) sql = sql + ";";
        return sql;
    }

    public String toOBJQL(OBQ q) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toOBJQLMap(q));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize OB‑JQL", e);
        }
    }

    /* ===== Validation ===== */

    private void validate(OBQ q) {
        if (q == null) throw new IllegalArgumentException("query is null");
        if (q.getSelect() == null || q.getSelect().isEmpty())
            throw new IllegalArgumentException("SELECT is required");

        boolean hasAgg = q.getSelect().stream().anyMatch(s -> s.agg != null);
        if (hasAgg) {
            boolean roll = nonEmpty(q.getRollup());
            boolean intr = nonEmpty(q.getInterval());
            if (roll == intr)
                throw new IllegalArgumentException("Aggregations require exactly one of: rollup OR interval");
        } else {
            if (nonEmpty(q.getRollup()) || nonEmpty(q.getInterval()))
                throw new IllegalArgumentException("Raw event queries must not specify rollup or interval");
        }

        if (q.getWhere() != null) assertWhereIndexed(q.getWhere());
        if (q.getFilter() != null) assertFilterPaths(q.getFilter());
    }

    private void assertWhereIndexed(OBQ.Condition c) {
        if (c instanceof OBQ.Predicate p) {
            if (!p.indexed) throw new IllegalArgumentException("WHERE contains non‑indexed field: " + p.field);
        } else if (c instanceof OBQ.PathPredicate) {
            throw new IllegalArgumentException("PathPredicate not allowed in WHERE (use FILTER)");
        } else if (c instanceof OBQ.Bool b) {
            for (OBQ.Condition child : b.conditions) assertWhereIndexed(child);
        } else if (c instanceof OBQ.MatchesGroup mg) {
            if (mg.require < 1) throw new IllegalArgumentException("MATCHES require must be >= 1");
            for (OBQ.Condition child : mg.conditions) assertWhereIndexed(child);
        }
    }

    private void assertFilterPaths(OBQ.Condition c) {
        if (c instanceof OBQ.Predicate) {
            throw new IllegalArgumentException("Field predicate not allowed in FILTER (use path)");
        } else if (c instanceof OBQ.PathPredicate) {
            // ok
        } else if (c instanceof OBQ.Bool b) {
            for (OBQ.Condition child : b.conditions) assertFilterPaths(child);
        } else if (c instanceof OBQ.MatchesGroup mg) {
            if (mg.require < 1) throw new IllegalArgumentException("MATCHES require must be >= 1");
            for (OBQ.Condition child : mg.conditions) assertFilterPaths(child);
        }
    }

    /* ===== Rendering ===== */

    private String renderSelect(List<OBQ.Select> xs) {
        return xs.stream()
                 .map(s -> s.agg == null ? s.target : (s.agg + "(" + s.target + ")"))
                 .collect(Collectors.joining(", "));
    }

    private String renderCondition(OBQ.Condition c, boolean inFilter) {
        if (c instanceof OBQ.Predicate p) {
            return renderFieldPredicate(p);
        } else if (c instanceof OBQ.PathPredicate pp) {
            if (!inFilter) throw new IllegalArgumentException("PathPredicate only allowed in FILTER");
            return renderPathPredicate(pp); // attributes-relative
        } else if (c instanceof OBQ.Bool b) {
            return renderBool(b, inFilter);
        } else if (c instanceof OBQ.MatchesGroup mg) {
            String inner = mg.conditions.stream()
                    .map(ch -> renderCondition(ch, inFilter))
                    .filter(this::nonEmpty)
                    .collect(Collectors.joining(", "));
            return "MATCHES " + mg.require + " OF (" + inner + ")";
        }
        throw new IllegalArgumentException("Unknown condition type: " + c.getClass());
    }

    private String renderFieldPredicate(OBQ.Predicate p) {
        String v = valueToSQL(p.value);
        return switch (p.op) {
            case "eq" -> p.field + " = " + v;
            case "ne" -> p.field + " <> " + v;
            case "gt" -> p.field + " > " + v;
            case "gte" -> p.field + " >= " + v;
            case "lt" -> p.field + " < " + v;
            case "lte" -> p.field + " <= " + v;
            case "in" -> p.field + " IN (" + listToSQL(p.value) + ")";
            case "nin" -> p.field + " NOT IN (" + listToSQL(p.value) + ")";
            case "prefix" -> p.field + " LIKE " + toPrefix(v);
            case "like" -> p.field + " LIKE " + v;
            default -> throw new IllegalArgumentException("Unsupported WHERE op: " + p.op);
        };
    }

    /** attributes-relative path → attributes->'k1'->'k2' */
    private String renderPathPredicate(OBQ.PathPredicate p) {
        if (p.path() == null || p.path().isEmpty())
            throw new IllegalArgumentException("FILTER path cannot be empty");
        String lhs = "attributes->'" + String.join("'->'", p.path()) + "'";
        String v = valueToSQL(p.value);
        return switch (p.op) {
            case "eq" -> lhs + " = " + v;
            case "ne" -> lhs + " <> " + v;
            case "in" -> lhs + " IN (" + listToSQL(p.value) + ")";
            case "nin" -> lhs + " NOT IN (" + listToSQL(p.value) + ")";
            case "prefix" -> lhs + " LIKE " + toPrefix(v);
            case "like" -> lhs + " LIKE " + v;
            default -> throw new IllegalArgumentException("Unsupported FILTER op: " + p.op);
        };
    }

    private String renderBool(OBQ.Bool b, boolean inFilter) {
        List<String> parts = b.conditions.stream()
                .map(c -> renderCondition(c, inFilter))
                .filter(this::nonEmpty)
                .toList();
        if (parts.isEmpty()) return "";
        String joined = switch (b.type) {
            case "and" -> String.join(" AND ", parts);
            case "or"  -> String.join(" OR " , parts);
            case "not" -> {
                if (parts.size() != 1)
                    throw new IllegalArgumentException("NOT expects exactly one child");
                yield "NOT (" + parts.get(0) + ")";
            }
            default -> throw new IllegalArgumentException("Unknown bool type: " + b.type);
        };
        return (parts.size() > 1 && !"not".equals(b.type)) ? "(" + joined + ")" : joined;
    }

    private String renderOptions(OBQ q) {
        var o = q.getOptions();
        if (o == null) return "";
        List<String> kv = new ArrayList<>();
        if (nonEmpty(o.sortOrder())) kv.add("sortOrder = '" + o.sortOrder() + "'");
        if (o.limit()    != null)    kv.add("limit = " + o.limit());
        if (o.offset()   != null)    kv.add("offset = " + o.offset());
        if (o.daysBack() != null)    kv.add("daysBack = " + o.daysBack());
        return kv.isEmpty() ? "" : "OPTIONS (" + String.join(", ", kv) + ");";
    }

    /* ===== Helpers ===== */

    @SuppressWarnings("unchecked")
    private String listToSQL(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("IN/NIN expects a List");
        return list.stream().map(this::valueToSQL).collect(Collectors.joining(", "));
    }

    private String valueToSQL(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        String s = String.valueOf(v);
        boolean looksQuoted = s.length() >= 2 && s.charAt(0) == '\'' && s.charAt(s.length()-1) == '\'';
        return looksQuoted ? s : "'" + s.replace("'", "''") + "'";
    }

    private String toPrefix(String alreadyQuoted) {
        if (alreadyQuoted.startsWith("'") && alreadyQuoted.endsWith("'")) {
            String inner = alreadyQuoted.substring(1, alreadyQuoted.length()-1);
            return "'" + inner + "%'";
        }
        return alreadyQuoted + "%";
    }

    private boolean nonEmpty(String s) { return s != null && !s.isBlank(); }

    /* ===== OB‑JQL map ===== */

    private Map<String, Object> toOBJQLMap(OBQ q) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (nonEmpty(q.getUse())) m.put("use", q.getUse());
        m.put("select", q.getSelect().stream().map(s ->
                s.agg == null ? s.target : Map.of("agg", s.agg, "target", s.target, "params", s.params)
        ).toList());
        m.put("from", q.getFrom());
        if (q.getWhere()  != null) m.put("where",  toJsonCondition(q.getWhere()));
        if (q.getFilter() != null) m.put("filter", toJsonCondition(q.getFilter()));
        if (nonEmpty(q.getRollup()))   m.put("rollup", q.getRollup());
        if (nonEmpty(q.getInterval())) m.put("interval", q.getInterval());
        if (q.getBetween() != null)    m.put("between", Map.of("from", q.getBetween().from(), "to", q.getBetween().to()));
        if (nonEmpty(q.getTimezone())) m.put("timezone", q.getTimezone());
        if (q.getOptions() != null) {
            Map<String, Object> opts = new LinkedHashMap<>();
            if (nonEmpty(q.getOptions().sortOrder())) opts.put("sortOrder", q.getOptions().sortOrder());
            if (q.getOptions().limit() != null)  opts.put("limit", q.getOptions().limit());
            if (q.getOptions().offset()!= null)  opts.put("offset", q.getOptions().offset());
            if (q.getOptions().daysBack()!=null) opts.put("daysBack", q.getOptions().daysBack());
            if (!opts.isEmpty()) m.put("options", opts);
        }
        return m;
    }

    private Object toJsonCondition(OBQ.Condition c) {
        if (c instanceof OBQ.Predicate p) {
            return Map.of("field", p.field, "op", p.op, "value", p.value, "indexed", p.indexed);
        } else if (c instanceof OBQ.PathPredicate pp) {
            return Map.of("path", pp.path(), "op", pp.op(), "value", pp.value());
        } else if (c instanceof OBQ.Bool b) {
            List<Object> arr = b.conditions.stream().map(this::toJsonCondition).toList();
            return Map.of(b.type, arr);
        } else if (c instanceof OBQ.MatchesGroup mg) {
            List<Object> arr = mg.conditions.stream().map(this::toJsonCondition).toList();
            return Map.of("matches", Map.of("require", mg.require, "conditions", arr));
        }
        throw new IllegalArgumentException("Unknown condition type: " + c.getClass());
    }
}
```

---

## 8) End‑to‑End Java Example (builder → emitters)

```java
import com.obsinity.query.*;
import com.obsinity.query.emit.OBQEmitter;
import java.util.List;
import java.util.Map;

public class Demo {
  public static void main(String[] args) {
    var q = OBQBuilder.use("finance")
      .select("event_id").select("timestamp")
      .from("api_request")
      .where(OBQBuilder.and(
          OBQBuilder.eq("api_name","get_account_balance"),
          OBQBuilder.matches(2,
              OBQBuilder.eq("http_status_code","200"),
              OBQBuilder.eq("region_code","EU"),
              OBQBuilder.prefix("user_id","svc-")
          )
      ))
      .filter(OBQBuilder.filterEq(List.of("region"), "EU"))
      .options("asc", 50, 0, 7)
      .build();

    var emitter = new OBQEmitter();
    System.out.println(emitter.toOBSQL(q));
    System.out.println(emitter.toOBJQL(q));
  }
}
```

**Produces OB‑SQL**

```sql
USE finance;

SELECT event_id, timestamp
FROM api_request
WHERE (api_name = 'get_account_balance' AND MATCHES 2 OF (http_status_code = '200', region_code = 'EU', user_id LIKE 'svc-%'))
FILTER attributes->'region' = 'EU'
OPTIONS (sortOrder = 'asc', limit = 50, offset = 0, daysBack = 7);
```

**Produces OB‑JQL** (pretty JSON, matching the example in §4.2)

---

## 9) Implementer Notes

* **Rollups**: pre‑created (`5s, 1m, 1h, 1d, 7d`); planner picks minimal base and aggregates upward.
* **Index discipline**: `WHERE` is strictly indexed; non‑indexed belong in `FILTER`.
* **MATCHES** is evaluated within the **row/bucket** context; it can nest boolean groups.
* **Builder** centralizes quoting & validation; emitters do formatting only.
* **FILTER** uses attributes‑relative paths; emitter renders `attributes->'k1'->'k2'`.

---
