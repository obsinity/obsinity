# Obsinity JQL — Request/Response Format

## 1) Top-level Request Object

**OB‑JQL (JSON):**

```json
{
  "service": "payment",                 // REQUIRED: service short name
  "event":   "http_request",            // REQUIRED: event type/name
  "period":  { "previous": "1h" },     // REQUIRED: primary time window
  "match":   { /* indexed-attribute predicates (fast) */ },
  "filter":  { /* full-row predicates (envelope/attributes) */ },
  "order":   [ { "field": "occurred_at", "dir": "desc" } ],
  "limit":   100,
  "offset":  0,
  "tz": "Europe/Dublin"                 // OPTIONAL: response timezone only (output rendering)
}
```

**OB‑SQL:**

```sql
FIND EVENTS
  SERVICE 'payment'
  EVENT   'http_request'
  PERIOD  PREVIOUS 1h
  MATCH ( /* indexed-attribute predicates (fast) */ )
  FILTER ( /* full-row predicates (envelope/attributes) */ )
  ORDER BY occurred_at DESC
  LIMIT 100 OFFSET 0
  TZ 'Europe/Dublin';
```

### Fields

| Field     | Type    | Required | Description                                                                                                |
| --------- | ------- | -------- | ---------------------------------------------------------------------------------------------------------- |
| `service` | string  | ✓        | Maps to `events_raw.service_name`. Used to prune LIST partitions.                                          |
| `event`   | string  | ✓        | Maps to `events_raw.event_name`.                                                                           |
| `period`  | object  | ✓        | Primary time window (see §2).                                                                              |
| `match`   | object  | –        | **Indexed attribute** query. Evaluated via `event_attr_index` and `INTERSECT`. See §3.                     |
| `filter`  | object  | –        | **Full‑row** filter evaluated on the selected rows (envelope and/or attributes). See §4.                   |
| `order`   | array   | –        | Sort list. Default: `occurred_at desc` (backend adds `event_id asc` tiebreak).                             |
| `limit`   | integer | –        | Page size (default varies).                                                                                |
| `offset`  | integer | –        | Offset‑based pagination.                                                                                   |
| `tz`      | string  | –        | **Response rendering timezone only**. Examples: `UTC`, `Europe/Dublin`, `+01:00`, `-05:30`. Default `UTC`. |

> **Why ****************************************************`order`****************************************************?**  Stable ordering is required so that pagination works deterministically. Without a defined sort, different pages could overlap or skip results if new events arrive between requests.

---

## 2) `period` — Time Window

Examples now use **`previous`** windows:

**JQL:**

```json
{ "period": { "previous": "1h" } }
{ "period": { "previous": "24h" } }
```

**OB‑SQL:**

```sql
PERIOD PREVIOUS 1h;
PERIOD PREVIOUS 24h;
```

You may also supply an **absolute** range:

**JQL:**

```json
{ "period": { "from": "2025-09-01T00:00:00Z", "to": "2025-09-02T00:00:00Z" } }
```

**OB‑SQL:**

```sql
PERIOD FROM '2025-09-01T00:00:00Z' TO '2025-09-02T00:00:00Z';
```

### Flexible formats for `previous`

* Units: `5s`, `1m`, `15m`, `1h`, `2d`, `7d`, `12d`
* Mixed durations:

   * `1:30` → 1 hour, 30 minutes
   * `2:12:30` → 2 days, 12 hours, 30 minutes

### Time zone semantics

* **All times are normalized to UTC internally** for filtering and matching.
* Top-level **`tz`** controls **only response formatting** (how timestamps are rendered in the payload).
* `period.previous` always ends at server `now` in UTC; rendering uses `tz`.
* If `tz` is omitted, responses render in `UTC`.

#### Timezone examples (requests & responses)

| Example           | Meaning                    |
| ----------------- | -------------------------- |
| `"UTC"`           | Coordinated Universal Time |
| `"Europe/Dublin"` | IANA zone name             |
| `"+01:00"`        | Fixed offset +1 hour       |
| `"-05:30"`        | Fixed offset −5.5 hours    |

### **Time zone semantics**

* **All times are normalized to UTC internally** for filtering and matching.
* Top-level \`\` controls **only response formatting** (how timestamps are rendered in the payload).
* `period.previous` always ends at server `now` in UTC; rendering uses `tz`.
* If `tz` is omitted, responses render in `UTC`.

#### **Timezone examples (requests & responses)**

| **ExampleMeaning** |                            |
| ------------------ | -------------------------- |
| `"UTC"`            | Coordinated Universal Time |
| `"Europe/Dublin"`  | IANA zone name             |
| `"+01:00"`         | Fixed offset +1 hour       |
| `"-05:30"`         | Fixed offset −5.5 hours    |

### **Timestamp formats (supported)**

**Accepted with explicit timezone (required for Obsinity JQL):**

1. **ISO instant (UTC “Z”)** `YYYY-MM-DDTHH:mm[:ss[.fraction]]Z` e.g. `2025-09-16T12:34:56Z`, `2025-09-16T12:34:56.123Z`
2. **ISO date-time with numeric offset** `YYYY-MM-DDTHH:mm[:ss[.fraction]]±HH:MM` e.g. `2025-09-16T12:34+01:00`, `2025-09-16T12:34:56.123+05:30`
3. **Space + seconds + offset** `YYYY-MM-DD HH:mm:ss±HH[:MM]` e.g. `2025-09-16 12:34:56+01:00`
4. **ISO date-time with offset + IANA zone (bracketed)** `YYYY-MM-DDTHH:mm[:ss[.fraction]]±HH:MM[Area/City]` e.g. `2025-09-16T12:34:56+01:00[Europe/Dublin]`
5. **ISO date-time + space + IANA zone** `YYYY-MM-DDTHH:mm[:ss[.fraction]] Area/City` e.g. `2025-09-16T12:34 Europe/Dublin`
6. **ISO date-time with bracketed IANA zone (no numeric offset)** `YYYY-MM-DDTHH:mm[:ss[.fraction]][Area/City]` e.g. `2025-09-16T12:34[Europe/Dublin]`
7. **Date-only with IANA zone** `YYYY-MM-DD Area/City` e.g. `2025-09-16 Europe/Dublin`

**Ignored / not supported (ambiguous):**

* Local ISO date-time without zone (e.g. `2025-09-16T12:34`).
* Date-only without zone (e.g. `2025-09-16`).

---

## 4) `filter` — Full-Row Predicates

Used for envelope or non-indexed paths. Two shapes parallel `match`:

1. **Single clause**

```json
"filter": { "path": "event.subCategory", "op": "=", "value": "http.server" }
```

**OB‑SQL equivalent**

```sql
FIND EVENTS
  FILTER ( event.subCategory = 'http.server' );
```

2. **Boolean groups**

```json
"filter": {
  "and": [
    { "path": "trace.correlation_id", "op": "=", "value": "c-123" },
    { "or": [
      { "path": "attributes.api.name", "op": "ilike", "value": "create%" },
      { "path": "attributes.http.method", "op": "=", "value": "GET" }
    ]}
  ]
}
```

**OB‑SQL:**

```sql
FILTER (
  trace.correlation_id = 'c-123' AND
  ( attributes.api.name ILIKE 'create%' OR attributes.http.method = 'GET' )
);
```

---

## 5) Sorting & Paging

For time‑series queries, results are **always ordered by ****************************************************`occurred_at`**************************************************** (time) first**, with `event_id` as a tiebreaker. This ordering is applied by default, even if not specified.

* Clients **may** include `order` in the request, but `occurred_at` will always be the primary sort key.
* If `order` includes `occurred_at`, it must be consistent with the default direction (`desc`).
* Additional secondary sorts may be provided but will always come **after** `occurred_at` and `event_id`.

Paging:

```json
"limit": 25,
"offset": 25
```

**OB‑SQL:**

```sql
ORDER BY occurred_at DESC
LIMIT 25 OFFSET 25;
```

---

## 6) Response Envelope (typical)

All query responses are returned in **JSON/HAL** format (Hypertext Application Language). This applies consistently to JQL, OB‑SQL, and all other query interfaces.

```json
{
	"count": 0,
	"total": 0,
	"limit": 100,
	"offset": 0,
	"data": {
		"events": []
	},
	"links": {
		"self": {
			"href": "/api/search/events",
			"method": "POST",
			"body": {
				"service": "payments",
				"event": "http_request",
				"period": {
					"between": [
						"2025-09-15T00:00:00[Dublin/Ireland]",
						"2025-09-15T06:00:00[Dublin/Ireland]"
					]
				},
				"match": [
					{
						"attribute": "http.status",
						"op": "!=",
						"value": 500
					}
				],
				"filter": {
					"and": [
						{
							"path": "attributes.client.ip",
							"op": "!=",
							"value": "10.0.0.1"
						},
						{
							"or": [
								{
									"path": "attributes.api.name",
									"op": "like",
									"value": "%account%"
								},
								{
									"path": "attributes.api.name",
									"op": "ilike",
									"value": "create%"
								}
							]
						}
					]
				},
				"order": [
					{
						"field": "occurred_at",
						"dir": "desc"
					}
				],
				"limit": 100,
				"offset": 0
			}
		},
		"first": {
			"href": "/api/search/events",
			"method": "POST",
			"body": {
				"service": "payments",
				"event": "http_request",
				"period": {
					"between": [
						"2025-09-15T00:00:00[Dublin/Ireland]",
						"2025-09-15T06:00:00[Dublin/Ireland]"
					]
				},
				"match": [
					{
						"attribute": "http.status",
						"op": "!=",
						"value": 500
					}
				],
				"filter": {
					"and": [
						{
							"path": "attributes.client.ip",
							"op": "!=",
							"value": "10.0.0.1"
						},
						{
							"or": [
								{
									"path": "attributes.api.name",
									"op": "like",
									"value": "%account%"
								},
								{
									"path": "attributes.api.name",
									"op": "ilike",
									"value": "create%"
								}
							]
						}
					]
				},
				"order": [
					{
						"field": "occurred_at",
						"dir": "desc"
					}
				],
				"limit": 100,
				"offset": 0
			}
		},
		"last": {
			"href": "/api/search/events",
			"method": "POST",
			"body": {
				"service": "payments",
				"event": "http_request",
				"period": {
					"between": [
						"2025-09-15T00:00:00[Dublin/Ireland]",
						"2025-09-15T06:00:00[Dublin/Ireland]"
					]
				},
				"match": [
					{
						"attribute": "http.status",
						"op": "!=",
						"value": 500
					}
				],
				"filter": {
					"and": [
						{
							"path": "attributes.client.ip",
							"op": "!=",
							"value": "10.0.0.1"
						},
						{
							"or": [
								{
									"path": "attributes.api.name",
									"op": "like",
									"value": "%account%"
								},
								{
									"path": "attributes.api.name",
									"op": "ilike",
									"value": "create%"
								}
							]
						}
					]
				},
				"order": [
					{
						"field": "occurred_at",
						"dir": "desc"
					}
				],
				"limit": 100,
				"offset": 0
			}
		}
	}
}
```

---

## 7) Validation & Errors

| Code      | Meaning                                             |
| --------- | --------------------------------------------------- |
| `JQL-100` | Missing `service` / `event`                         |
| `JQL-101` | Missing/invalid `period`                            |
| `JQL-121` | Bad `period.previous` duration format               |
| `JQL-122` | `period.from`/`period.to` missing explicit timezone |
| `JQL-200` | Bad `match` clause (unknown `op`, wrong types)      |
| `JQL-210` | Bad `filter` clause                                 |
| `JQL-300` | `limit`/`offset` out of range                       |

---

## 8) Notes & Tips

* Always specify `order` for deterministic paging.
* All comparisons are evaluated in **UTC** internally.
* Use `tz` to control how response timestamps are formatted.
* Prefer `match` for attribute constraints to leverage the index.
* Keep `period` tight for partition pruning.

---
