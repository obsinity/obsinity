# Reference Client (Spring MVC Demo)

This demo app exposes HTTP endpoints that exercise the Obsinity client annotations and receivers.

## Endpoints

- GET `/api/checkout?user=alice&items=3`
  - @Flow("demo.checkout") + @Kind(SERVER) + @Domain("http")
  - Demonstrates push annotations: @PushAttribute("user.id"), @PushContextValue("cart.size")
- GET `/api/checkout/fail?user=bob&items=1`
  - Same as above but throws to produce FAILED lifecycle
- GET `/api/checkout/with-step?user=carol&items=2&sku=sku-123`
  - @Flow with nested @Step in the service (`demo.reserve`)
- GET `/api/orphan-step?note=hello`
  - Orphan @Step auto-promoted to a Flow; @OrphanAlert controls log level
- GET `/api/client-call?target=service-x`
  - @Kind(CLIENT) flow; attaches attribute `client.target`
- GET `/api/produce?topic=demo-topic`
  - @Kind(PRODUCER) flow; attaches attribute `messaging.topic`

## Receivers

Class `DemoFlowReceivers` is scoped to `demo.*` via `@OnEventScope("demo.")` and demonstrates:
- `@OnFlowStarted` with `@PullAllAttributes`
- Finish handlers split by outcome: `@OnFlowCompleted` + `@OnOutcome(SUCCESS|FAILURE)`
- Failure specificity with `@OnFlowFailure(IllegalArgumentException ...)`
- Guarded start using `@RequiredAttributes` + `@PullAttribute`/`@PullContextValue`
- Per-receiver fallback using `@OnFlowNotMatched`

## Running

- `mvn -pl obsinity-reference-client-spring spring-boot:run`
- Call the endpoints above, and watch logs for START/DONE/FAIL lines (logging receiver).
- To send events to an Obsinity controller ingest, enable:
  - `obsinity.collection.obsinity.enabled=true`
  - `obsinity.ingest.url=http://localhost:8080/events/publish` (or your endpoint)

## Notes

- `@Kind` uses OTEL `SpanKind` enum.
- `@Domain` can accept a free-form string (e.g., `"http"`) or the enum `Domain.Type` for common values (`HTTP`, `MESSAGING`, `DB`, `RPC`, `INTERNAL`).
