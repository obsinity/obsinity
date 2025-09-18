# Obsinity ⇄ OTEL Mapping

| **Obsinity field**                   | **OTEL proto field**                                             | **Notes**                                             |
| ------------------------------------ | ---------------------------------------------------------------- | ----------------------------------------------------- |
| `event.name`                         | `Span.name`                                                      | The span’s display name.                              |
| `event.kind`                         | `Span.kind`                                                      | Enum: SERVER, CLIENT, PRODUCER, CONSUMER, INTERNAL.   |
| `event.domain`                       | — (not in OTEL core)                                             | Keep as `attributes["obsinity.domain"]` if exporting. |
|                                      |                                                                  |                                                       |
| `resource.service.name`              | `Resource.attributes["service.name"]`                            | Canonical service identifier.                         |
| `resource.service.namespace`         | `Resource.attributes["service.namespace"]`                       | Optional grouping (e.g. “payments”).                  |
| `resource.service.instance.id`       | `Resource.attributes["service.instance.id"]`                     | Unique instance ID (pod, VM, etc.).                   |
| `resource.service.version`           | `Resource.attributes["service.version"]`                         | App version.                                          |
| `resource.host.name`                 | `Resource.attributes["host.name"]`                               | Machine/host name.                                    |
| `resource.telemetry.sdk.name`        | `Resource.attributes["telemetry.sdk.name"]`                      | SDK identity.                                         |
| `resource.telemetry.sdk.version`     | `Resource.attributes["telemetry.sdk.version"]`                   | SDK version.                                          |
| `resource.cloud.provider`            | `Resource.attributes["cloud.provider"]`                          | Cloud vendor.                                         |
| `resource.cloud.region`              | `Resource.attributes["cloud.region"]`                            | Cloud region.                                         |
|                                      |                                                                  |                                                       |
| `trace.traceId`                      | `Span.trace_id`                                                  | 16-byte (32 hex chars).                               |
| `trace.spanId`                       | `Span.span_id`                                                   | 8-byte (16 hex chars).                                |
| `trace.parentSpanId`                 | `Span.parent_span_id`                                            | Optional.                                             |
| `trace.state`                        | `Span.trace_state`                                               | Tracestate string.                                    |
|                                      |                                                                  |                                                       |
| `time.startUnixNano`                 | `Span.start_time_unix_nano`                                      | Required (nanoseconds since epoch).                   |
| `time.endUnixNano`                   | `Span.end_time_unix_nano`                                        | Required (nanoseconds since epoch).                   |
| `time.startedAt` / `time.endedAt`    | — (human-readable only)                                          | Convenience, not OTEL.                                |
|                                      |                                                                  |                                                       |
| `attributes.api.name`                | `Span.attributes["rpc.method"]` or `Span.attributes["api.name"]` | Depending on semantic convention.                     |
| `attributes.api.version`             | `Span.attributes["api.version"]`                                 | Custom semantic convention.                           |
| `attributes.http.method`             | `Span.attributes["http.method"]`                                 | Standard OTEL semantic.                               |
| `attributes.http.status_code`        | `Span.attributes["http.status_code"]`                            | Standard OTEL semantic.                               |
| `attributes.http.route`              | `Span.attributes["http.route"]`                                  | Standard OTEL semantic.                               |
| `attributes.http.flavor`             | `Span.attributes["http.flavor"]`                                 | E.g. "1.1", "2".                                      |
| `attributes.net.peer.ip`             | `Span.attributes["net.peer.ip"]`                                 | OTEL semantic.                                        |
| `attributes.net.peer.port`           | `Span.attributes["net.peer.port"]`                               | OTEL semantic.                                        |
| `attributes.net.transport`           | `Span.attributes["net.transport"]`                               | OTEL semantic.                                        |
| `attributes.client.ip`               | `Span.attributes["client.address"]`                              | Standard semantic.                                    |
| `attributes.obsinity.correlation_id` | `Span.attributes["obsinity.correlation_id"]`                     | Obsinity-native, outside OTEL core.                   |
|                                      |                                                                  |                                                       |
| `events[].name`                      | `Span.events[i].name`                                            | Event name (annotation).                              |
| `events[].timeUnixNano`              | `Span.events[i].time_unix_nano`                                  | Event timestamp.                                      |
| `events[].attributes.*`              | `Span.events[i].attributes.*`                                    | Nested in Obsinity, dotted in OTEL.                   |
|                                      |                                                                  |                                                       |
| `links[].traceId`                    | `Span.links[i].trace_id`                                         | Linked span’s trace ID.                               |
| `links[].spanId`                     | `Span.links[i].span_id`                                          | Linked span’s span ID.                                |
| `links[].attributes.*`               | `Span.links[i].attributes.*`                                     | Additional link attributes.                           |
|                                      |                                                                  |                                                       |
| `status.code`                        | `Span.status.code`                                               | `OK`, `ERROR`, `UNSET`.                               |
| `status.message`                     | `Span.status.message`                                            | Optional human-readable status.                       |
|                                      |                                                                  |                                                       |
| `synthetic`                          | — (not in OTEL)                                                  | Keep as `attributes["obsinity.synthetic"]`.           |

---

✅ **Summary**:

* **Obsinity top-level span** = OTEL `Span`.
* **`resource` block** = OTEL `Resource`.
* **`event` block** = local span identity; `event.kind` aligns with `Span.kind`.
* **`events[]`** = OTEL span events.
* **`links[]`** = OTEL links.
* **`status`** = OTEL span status.
* **Anything Obsinity-native** (e.g. `event.domain`, `correlationId`, `synthetic`) should be stored in `attributes.obsinity.*` when exported.

---
