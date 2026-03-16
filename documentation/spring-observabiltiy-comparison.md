# Generic Flow Framework vs Spring Observability

## Concept Mapping

| Flow Framework | Spring Observability | Purpose             |
| -------------- | -------------------- | ------------------- |
| Flow           | Observation          | Unit of work        |
| Step           | Nested Observation   | Sub-operation       |
| Flow context   | Observation.Context  | Metadata            |
| Flow sink      | ObservationHandler   | Lifecycle callbacks |

---

# 1. Root Unit of Work

### Flow Framework

```java
@Flow("checkout")
void checkout(String userId) {
	// logic
}
```

### Spring Observability

```java
Observation.createNotStarted("checkout", registry)
        .observe(() -> {
	// logic
	});
```

---

## Important Spring AOP note

When using annotation mode with Spring AOP, calls from one `@Flow` or `@Step` method to another `@Flow` or `@Step` method **in the same class** will not be intercepted if called through `this`.

Use the Spring AOP proxy instead, for example via `AopContext.currentProxy()`, self-injection, or by moving the stepped methods to another bean.

Example:

```java
@Component
class CheckoutService {

    @Flow("checkout")
    void checkout() {
        ((CheckoutService) AopContext.currentProxy()).validate();
        ((CheckoutService) AopContext.currentProxy()).charge();
    }

    @Step("validate")
    void validate() {}

    @Step("charge")
    void charge() {}
}
```

This requires proxy exposure to be enabled, for example with `@EnableAspectJAutoProxy(exposeProxy = true)` when not already handled by your setup.

---

# 2. Nested Work

### Flow Framework

```java
@Flow("checkout")
void checkout() {
	validate();
	charge();
}

@Step("validate")
void validate() {}

@Step("charge")
void charge() {}
```

### Spring Observability

```java
Observation.createNotStarted("checkout", registry)
        .observe(() -> {
	Observation.createNotStarted("validate", registry)
                    .observe(() -> validate());

	Observation.createNotStarted("charge", registry)
                    .observe(() -> charge());
	});
```

---

# 3. Attaching Metadata

### Flow Framework

```java
@Flow("checkout")
void checkout(@PushAttribute("user.id") String userId,
			  @PushContextValue("session.id") String session) {
}
```

### Spring Observability

```java
Observation.createNotStarted("checkout", registry)
        .highCardinalityKeyValue("user.id", userId)
        .lowCardinalityKeyValue("session.id", session)
        .observe(() -> process());
```

---

# 4. Lifecycle Handling

### Flow Framework

```java
@FlowSink
class AuditSink {

	@OnFlowStarted
	void started(FlowEvent e) {}

	@OnFlowCompleted
	void completed(FlowEvent e) {}

	@OnFlowFailure
	void failed(Throwable error) {}
}
```

### Spring Observability

```java
class AuditHandler
	implements ObservationHandler<Observation.Context> {

	public void onStart(Context ctx) {}

	public void onStop(Context ctx) {}

	public void onError(Context ctx) {}
}
```

---

# 5. Programmatic (Code-First) Style

### Flow Framework

```java
void checkout() {
	validate();
	charge();
}
```

### Spring Observability

```java
void checkout() {
	Observation.createNotStarted("checkout", registry)
		.highCardinalityKeyValue("user.id", userId)
		.observe(() -> {
			Observation.createNotStarted("validate", registry)
				.observe(() -> validate());

			Observation.createNotStarted("charge", registry)
				.observe(() -> charge());
		});
}
```

---

# 6. Extension Points

### Flow Framework

```java
interface FlowSink {
	void onEvent(FlowLifecycleRecord event);
}
```

### Spring Observability

```java
interface ObservationHandler<C extends Observation.Context> {
	void onStart(C ctx);
	void onStop(C ctx);
	void onError(C ctx);
}
```

---

# Summary

| Area                | Flow Framework         | Spring Observability           |
| ------------------- | ---------------------- | ------------------------------ |
| Primary abstraction | Flow                   | Observation                    |
| Nested work         | Step                   | Nested observation             |
| Metadata            | Attributes + context   | Key/value pairs                |
| Lifecycle hooks     | Flow sinks             | Observation handlers           |
| Code-first API      | flowRuntime.flow()     | Observation.createNotStarted() |
| Typical output      | Structured flow events | Metrics / traces / logs        |

---

This document compares only the **core programming model**. The flow framework is transport-agnostic and can emit events to multiple systems such as messaging systems, event streams, or observability pipelines.
