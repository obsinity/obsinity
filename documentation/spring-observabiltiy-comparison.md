# Mango4j Flow Framework vs Spring Observability

## Purpose

This document compares the developer-facing programming model of:

- Mango4j Flow framework's annotation-based instrumentation
- Spring Observability via Micrometer `Observation`

The examples here are based on the actual annotations and demo code in this repository, especially the `demo.*` flows in the Spring reference client.

## Concept Mapping

| Mango4j Flow framework | Spring Observability | Meaning |
| --- | --- | --- |
| `@Flow` | `Observation` | Root unit of work |
| `@Step` | nested `Observation` | Nested operation inside a unit of work |
| attributes + event context | low/high-cardinality key values + `Observation.Context` | Metadata attached to execution |
| `@FlowSink` handlers | `ObservationHandler` | Lifecycle callbacks / extension points |
| STARTED / COMPLETED / FAILED | `onStart` / `onStop` / `onError` | Runtime lifecycle |
| `@Kind(SpanKind.*)` | convention / context-dependent span kind | Role of the operation |

## Bootstrapping

### Mango4j Flow Framework

The Mango4j Flow framework is bootstrapped with `@Mango4jApplication`:

```java
@SpringBootApplication
@Mango4jApplication
public class DemoApplication {}
```

That annotation imports the collection auto-configuration and enables proxy exposure:

```java
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
```

### Spring Observability

Spring Observability is typically enabled by Boot auto-configuration plus an `ObservationRegistry`, then used either directly or through framework integrations.

## AOP Constraint: Self Invocation

`@Flow` and `@Step` are applied through Spring AOP. A same-class call like `this.someStep()` bypasses the proxy and will not trigger instrumentation.

Call through another bean, or through the current proxy when necessary:

```java
@Component
class CheckoutService {

    @Flow(name = "demo.checkout")
    public void checkout() {
        ((CheckoutService) AopContext.currentProxy()).validate();
    }

    @Step("demo.validate")
    public void validate() {}
}
```

The framework enables `exposeProxy = true` through `@Mango4jApplication`.

## Root Unit of Work

### Mango4j Flow Framework

From the reference client:

```java
@RestController
class DemoController {

    @GetMapping("/api/checkout")
    @Flow(name = "demo.checkout")
    @Kind(SpanKind.SERVER)
    public Map<String, Object> checkout(
            @PushAttribute("user.id") String userId,
            @PushContextValue("cart.size") int items) {
        return Map.of("status", "ok", "user", userId, "items", items);
    }
}
```

### Spring Observability

Equivalent direct usage is explicit:

```java
@RestController
class DemoController {

    private final ObservationRegistry registry;

    DemoController(ObservationRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/api/checkout")
    public Map<String, Object> checkout(String userId, int items) {
        return Observation.createNotStarted("demo.checkout", registry)
                .contextualName("GET /api/checkout")
                .observe(() -> Map.of("status", "ok", "user", userId, "items", items));
    }
}
```

## Nested Work

### Mango4j Flow Framework

Nested work is declared with `@Step` on collaborating beans:

```java
@Component
class DemoFlowsService {

    private final StockService stockService;

    DemoFlowsService(StockService stockService) {
        this.stockService = stockService;
    }

    @Step("demo.reserve")
    @Kind(SpanKind.INTERNAL)
    public void reserveInventory(@PushAttribute("sku") String sku) {
        stockService.verifyStock(sku);
    }
}

@Component
class StockService {

    @Step("demo.reserve.stock")
    @Kind(SpanKind.INTERNAL)
    public void verifyStock(@PushAttribute("sku") String sku) {}
}
```

When `reserveInventory()` runs inside `demo.checkout`, the Mango4j Flow framework records nested steps automatically.

### Spring Observability

The equivalent nesting is manual unless another framework layer creates observations for you:

```java
class CheckoutService {

    private final ObservationRegistry registry;
    private final StockService stockService;

    CheckoutService(ObservationRegistry registry, StockService stockService) {
        this.registry = registry;
        this.stockService = stockService;
    }

    public void checkout(String sku) {
        Observation.createNotStarted("demo.checkout", registry)
                .observe(() -> {
                    Observation.createNotStarted("demo.reserve", registry)
                            .observe(() -> {
                                Observation.createNotStarted("demo.reserve.stock", registry)
                                        .observe(() -> stockService.verifyStock(sku));
                            });
                });
    }
}
```

## Metadata Model

### Mango4j Flow Framework

The Mango4j Flow framework distinguishes between:

- `@PushAttribute("...")` for flow attributes
- `@PushContextValue("...")` for event context

Example:

```java
class CheckoutController {

    @Flow(name = "demo.checkout")
    public Map<String, Object> checkout(
            @PushAttribute("user.id") String userId,
            @PushContextValue("cart.size") int items) {
        return Map.of();
    }
}
```

This same model is used later by sinks through pull annotations such as `@PullAttribute`, `@PullContextValue`, `@PullAllAttributes`, and `@PullAllContextValues`.

### Spring Observability

Micrometer exposes key/value pairs and an observation context, but it does not split them into the Mango4j Flow framework's attribute vs event-context model by annotation:

```java
class CheckoutService {

    private final ObservationRegistry registry;

    CheckoutService(ObservationRegistry registry) {
        this.registry = registry;
    }

    public void checkout(String userId, int items) {
        Observation.createNotStarted("demo.checkout", registry)
                .lowCardinalityKeyValue("user.id", userId)
                .highCardinalityKeyValue("cart.size", String.valueOf(items))
                .observe(this::process);
    }

    private void process() {}
}
```

## Operation Kind

### Mango4j Flow Framework

`@Kind` uses the OpenTelemetry `SpanKind` enum directly:

```java
class ClientController {

    @Flow(name = "demo.client.call")
    @Kind(SpanKind.CLIENT)
    public Map<String, Object> clientCall(
            @PushAttribute("client.target") String target) {
        return Map.of("target", target);
    }
}
```

The reference app also demonstrates `SERVER`, `INTERNAL`, and `PRODUCER`.

### Spring Observability

Spring Observability does not have a direct `@Kind` equivalent in the same annotation style. Span kind is usually implied by the instrumentation or written into context/conventions by the integration in use.

## Orphan Steps

### Mango4j Flow Framework

If a `@Step` runs without an active `@Flow`, it is auto-promoted into a flow. `@OrphanAlert` controls the log level for that promotion:

```java
class DemoController {

    @Step("demo.orphan.step")
    @OrphanAlert(OrphanAlert.Level.WARN)
    public String orphanStep(@PushAttribute("note") String note) {
        return "orphan step executed: " + note;
    }
}
```

This is an explicit part of the current Mango4j Flow framework API.

### Spring Observability

There is no direct equivalent. You either create an `Observation` explicitly or you do not get one.

## Lifecycle Extensions

### Mango4j Flow Framework

Lifecycle handling is annotation-driven. `@FlowSink` marks the sink type and now includes Spring's `@Component`, so an explicit `@Component` is not required.

The reference client's `DemoFlowSink` shows the sink API shape:

```java
@FlowSink
@OnFlowScope("demo.")
public class DemoFlowSink {

    @OnFlowStarted
    public void onStart(FlowEvent event, @PullAllAttributes Map<String, Object> attrs) {}

    @OnFlowCompleted
    @OnOutcome(Outcome.SUCCESS)
    public void onSuccess(@PullAllAttributes Map<String, Object> attrs) {}

    @OnFlowCompleted
    @OnOutcome(Outcome.FAILURE)
    public void onFailure(Throwable t, @PullAllAttributes Map<String, Object> attrs) {}

    @OnFlowFailure
    public void onIllegalArg(IllegalArgumentException ex, FlowEvent event) {}

    @OnFlowStarted
    @RequiredAttributes({"user.id", "cart.size"})
    public void guarded(
            @PullAttribute("user.id") String uid,
            @PullContextValue("cart.size") Integer items) {}

    @OnFlowNotMatched
    public void notMatched(FlowEvent event) {}
}
```

Key extension features present in the current API:

- lifecycle selection with `@OnFlowStarted`, `@OnFlowCompleted`, `@OnFlowFailure`, and `@OnFlowLifecycle`
- outcome filtering with `@OnOutcome`
- name scoping with `@OnFlowScope`
- parameter binding from event data with `@Pull*`
- preconditions with `@RequiredAttributes` and `@RequiredEventContext`
- sink-local fallback with `@OnFlowNotMatched`

### Spring Observability

The equivalent customization point is an `ObservationHandler`:

```java
class AuditHandler implements ObservationHandler<Observation.Context> {

    @Override
    public void onStart(Observation.Context context) {}

    @Override
    public void onStop(Observation.Context context) {}

    @Override
    public void onError(Observation.Context context) {}

    @Override
    public boolean supportsContext(Observation.Context context) {
        return true;
    }
}
```

Filtering and data extraction are usually implemented imperatively inside the handler instead of declaratively on individual methods.

## Practical Difference

The Mango4j Flow framework is closer to an annotation-first event pipeline:

- instrument methods with `@Flow` and `@Step`
- enrich them with `@PushAttribute`, `@PushContextValue`, and `@Kind`
- react to emitted lifecycle events with `@FlowSink` methods

Spring Observability is closer to a programmatic observation API:

- create or receive an `Observation`
- attach key values and context data
- implement `ObservationHandler` logic for downstream behavior

## Summary

| Area | Mango4j Flow framework | Spring Observability |
| --- | --- | --- |
| Root instrumentation | `@Flow` | `Observation` |
| Nested instrumentation | `@Step` | nested `Observation` |
| Metadata input | `@PushAttribute`, `@PushContextValue` | key values + context |
| Role / span kind | `@Kind(SpanKind.*)` | integration-specific |
| Missing parent behavior | orphan `@Step` can auto-promote to flow | no direct equivalent |
| Lifecycle extension model | declarative sink methods on `@FlowSink` beans | imperative `ObservationHandler` |
| Sink data binding | `@Pull*`, `@Required*`, throwable injection | manual extraction from context |
| Typical style | annotation-first | API / handler-first |
