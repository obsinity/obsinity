# Reference Client (Spring MVC Demo)

This demo app exposes HTTP endpoints that exercise the Obsinity client annotations and receivers.

## Endpoints

- GET `/api/checkout?user=alice&items=3`
  - @Flow("demo.checkout") + @Kind(SERVER)
  - Nested @Step hierarchy (`demo.reserve` → `demo.reserve.stock`)
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
- Provide a service identifier with `OBSINITY_SERVICE` (or `-Dobsinity.collection.service=<service>`); the ingest rejects events without `resource.service.name`.
- Call the endpoints above, and watch logs for START/DONE/FAIL lines (logging receiver).
- To send events to an Obsinity controller ingest, enable:
  - `obsinity.collection.obsinity.enabled=true`
  - `obsinity.ingest.url=http://obsinity-reference-server:8086/events/publish` (auto-detects Docker host IP when containerized; override as needed)

## Container

- `./obsinity-reference-client-spring/build.sh` — formats, builds, and packages a Docker image (`IMAGE_NAME`, default `obsinity-demo-client`).
- `./obsinity-reference-client-spring/run.sh [--clean] [docker args...]` — starts the container on port 8080 and tails logs (container defaults to `obsinity-demo-client`). Use `--clean` to rebuild before starting.
- Pass additional `docker run` arguments after `run.sh`, e.g. `./run.sh -e OBSINITY_INGEST_URL=http://host.docker.internal:8086/events/publish`.

## Developer Aids

- `obsinity-reference-client-spring/insomnia.yaml` — import into Insomnia to exercise each endpoint with pre-filled parameters.
- A lightweight UI is served at `/` (e.g. <http://localhost:8080/>) that calls the demo endpoints directly from the browser.

## Notes

- `@Kind` uses OTEL `SpanKind` enum.
- `@Kind` sets the span role (SERVER, CLIENT, PRODUCER, CONSUMER, INTERNAL).
