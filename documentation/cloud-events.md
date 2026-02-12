# Using CloudEvents as Our Event Model

This document describes how we use the CloudEvents standard as an interchange model while preserving Obsinity/OTEL-compatible semantics. It also highlights what CloudEvents does not cover directly and how we fill gaps with extensions.

## Why CloudEvents
- Clear, vendor-neutral envelope (`id`, `source`, `type`, `time`, `specversion`).
- Portable across transports (HTTP, Kafka, RabbitMQ).
- Extensible with custom attributes for observability context.

Obsinity is not live yet, so we still have a choice for the default event model. We can continue with OTEL-like events, define a pure Obsinity event shape, adopt extended CloudEvents as the default, or support multiple ingestion formats in parallel. This document assumes CloudEvents as the interchange model and shows how we bridge the gaps to the Obsinity/OTEL shape.

## Mapping: CloudEvents -> Obsinity/OTEL Shape

CloudEvents core fields:
- `id` -> `eventId`
- `type` -> `event.name`
- `source` -> `resource.service.name` (or full resource map)
- `time` -> `time.startedAt`
- `datacontenttype` -> payload hint (optional)
- `data` -> `attributes` (and nested business attributes)

Obsinity/OTEL fields that are not present in CloudEvents core:
- `time.endedAt` / `time.endUnixNano` / `elapsedNanos`
- `event.kind` (OTEL span kind)
- `status.code` / `status.message`
- `trace.traceId`, `trace.spanId`, `trace.parentSpanId`
- `resource` attributes beyond `service.name` (host, cloud, telemetry.sdk, etc.)
- `events` (nested span events)
- `links` (span links)

## Recommended CloudEvents Extensions

We use CloudEvents extension attributes to carry missing data. Suggested keys:
- `obs_event_kind` -> OTEL span kind (e.g., `SERVER`, `CLIENT`)
- `obs_trace_id`, `obs_span_id`, `obs_parent_span_id`
- `obs_status_code`, `obs_status_message`
- `obs_started_at`, `obs_ended_at`, `obs_elapsed_nanos`
- `obs_resource` (object) for resource attributes when `source` is not enough
- `obs_events` (array) for nested events
- `obs_links` (array) for span links

If our transport allows it, we can also carry a full Obsinity/OTEL object in `data` and just use CloudEvents as the outer envelope. The examples below show both patterns.

## Example: Minimal CloudEvents (mapped to Obsinity)

```json
{
  "specversion": "1.0",
  "id": "2e0c02a1-3b78-4a1c-9b6b-7e4e8ed84b5a",
  "type": "payments.charge",
  "source": "payments-service",
  "time": "2026-02-12T16:24:00Z",
  "datacontenttype": "application/json",
  "data": {
    "amount": 42.95,
    "currency": "USD",
    "user.id": "u_123",
    "payment.method": "card"
  }
}
```

Expected Obsinity mapping (conceptual):
- `event.name` = `payments.charge`
- `resource.service.name` = `payments-service`
- `time.startedAt` = `2026-02-12T16:24:00Z`
- `attributes` = `data`

## Example: CloudEvents with Obsinity/OTEL Extensions

```json
{
  "specversion": "1.0",
  "id": "7f0a4c72-9d88-4eb2-a7be-8d2732f7c4d3",
  "type": "checkout.submit",
  "source": "web-store",
  "time": "2026-02-12T16:24:00Z",
  "datacontenttype": "application/json",
  "obs_event_kind": "SERVER",
  "obs_trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "obs_span_id": "00f067aa0ba902b7",
  "obs_parent_span_id": "",
  "obs_status_code": "OK",
  "obs_status_message": "",
  "obs_ended_at": "2026-02-12T16:24:00.432Z",
  "obs_elapsed_nanos": 432000000,
  "obs_resource": {
    "service": { "name": "web-store", "version": "1.8.2" },
    "host": { "name": "web-01" },
    "cloud": { "provider": "aws", "region": "us-east-1" },
    "telemetry": { "sdk": { "name": "obsinity", "version": "0.7.0" } }
  },
  "data": {
    "user.id": "u_123",
    "cart.size": 3,
    "region": "us-east"
  }
}
```

## Example: Exception with Stack Trace

When an error occurs, we capture failure semantics in CloudEvents extensions and include a structured error object in `data`.

```json
{
  "specversion": "1.0",
  "id": "bf2c7f9b-9f80-4df2-bb42-9e6db0e9f8da",
  "type": "checkout.submit",
  "source": "web-store",
  "time": "2026-02-12T16:24:01Z",
  "datacontenttype": "application/json",
  "obs_event_kind": "SERVER",
  "obs_status_code": "ERROR",
  "obs_status_message": "Payment provider unavailable",
  "obs_trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "obs_span_id": "00f067aa0ba902b7",
  "obs_ended_at": "2026-02-12T16:24:01.231Z",
  "obs_elapsed_nanos": 231000000,
  "data": {
    "user.id": "u_123",
    "cart.size": 3,
    "error": {
      "type": "PaymentProviderException",
      "message": "Timeout from upstream",
      "stacktrace": [
        "com.acme.payments.ProviderClient.charge(ProviderClient.java:214)",
        "com.acme.checkout.CheckoutService.submit(CheckoutService.java:88)",
        "com.acme.checkout.CheckoutController.submit(CheckoutController.java:55)"
      ],
      "caused_by": {
        "type": "java.net.SocketTimeoutException",
        "message": "Read timed out"
      }
    }
  }
}
```

## Example: Embedding the Full Obsinity/OTEL Payload

If we want a 1:1 pass-through, we keep CloudEvents as the envelope and place the canonical Obsinity/OTEL JSON in `data`.

```json
{
  "specversion": "1.0",
  "id": "d92be764-7d7f-4f4a-90f2-ec9a33e9c6ac",
  "type": "payments.charge",
  "source": "payments-service",
  "time": "2026-02-12T16:24:00Z",
  "datacontenttype": "application/json",
  "data": {
    "event": { "name": "payments.charge", "kind": "SERVER" },
    "resource": { "service": { "name": "payments-service" } },
    "time": { "startedAt": "2026-02-12T16:24:00Z", "endedAt": "2026-02-12T16:24:00.120Z" },
    "trace": { "traceId": "4bf92f3577b34da6a3ce929d0e0e4736", "spanId": "00f067aa0ba902b7" },
    "status": { "code": "OK" },
    "attributes": { "amount": 42.95, "currency": "USD" }
  }
}
```

## Implementation Notes
- Extension names are a convention; we keep them stable so downstream mapping is deterministic.
- We prefer structured error data (type, message, stacktrace array) over raw stacktrace strings.
- We use `resource.service.name` as the canonical service identifier and ensure it is always present.
