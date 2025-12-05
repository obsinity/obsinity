# ThreadLocal Leak Fix - Summary

## Problem Identified
The application had **critical ThreadLocal memory leaks** in thread pool environments (common in Spring Boot applications).

### Root Causes
1. **`FlowProcessorSupport`** used `InheritableThreadLocal` for flow context and batch tracking
2. **`FlowAspect`** pushed events to ThreadLocal but had no guaranteed cleanup in exception paths
3. **No cleanup at request boundaries** - ThreadLocals persisted across multiple requests on pooled threads
4. **Orphan step auto-promotion** created flows without cleanup guarantees

## Changes Made

### 1. FlowProcessorSupport.java
**File:** `obsinity-collection-core/src/main/java/com/obsinity/flow/processor/FlowProcessorSupport.java`

#### Added `cleanupThreadLocals()` method:
```java
public void cleanupThreadLocals() {
    // Cleans up both ctx and batch ThreadLocals
    // Logs warnings if unpopped events detected (indicates bugs)
}
```

#### Improved `pop()` method:
- Now calls `ctx.remove()` when stack becomes empty
- Logs warnings and removes ThreadLocal on inconsistent nesting

#### Fixed `clearBatchAfterDispatch()`:
- Removed re-initialization after `batch.remove()`
- Lets `initialValue()` handle lazy initialization to prevent leaks

### 2. FlowAspect.java
**File:** `obsinity-collection-spring/src/main/java/com/obsinity/collection/spring/aspect/FlowAspect.java`

#### Updated `aroundFlow()` method:
- Detects if current flow is root-level (no parent)
- Added `finally` block that calls `support.cleanupThreadLocals()` for root flows
- Ensures cleanup even when exceptions occur

#### Updated `aroundStep()` orphan path:
- Added `finally` block for orphan steps (auto-promoted to flows)
- Ensures cleanup after orphan step execution

### 3. TraceContextFilter.java
**File:** `obsinity-collection-spring/src/main/java/com/obsinity/collection/spring/web/TraceContextFilter.java`

#### Added FlowProcessorSupport injection:
- Constructor now accepts `FlowProcessorSupport` parameter
- Added cleanup in `finally` block as safety net

#### Benefits:
- Catches cases where aspect fails or doesn't run
- Ensures cleanup at request boundaries in servlet-based apps

### 4. TraceContextWebFilter.java
**File:** `obsinity-collection-spring/src/main/java/com/obsinity/collection/spring/webflux/TraceContextWebFilter.java`

#### Added FlowProcessorSupport injection:
- Constructor now accepts `FlowProcessorSupport` parameter
- Added cleanup in `doFinally()` operator

#### Benefits:
- Ensures cleanup in reactive WebFlux applications
- Handles async request processing properly

### 5. Auto-Configuration Updates

#### TraceAutoConfiguration.java
- Updated to inject `FlowProcessorSupport` into `TraceContextFilter`
- Uses `@Autowired(required = false)` for optional dependency

#### WebfluxTraceAutoConfiguration.java
- Updated to inject `FlowProcessorSupport` into `TraceContextWebFilter`
- Uses `@Autowired(required = false)` for optional dependency

### 6. Test Updates
- `TraceContextFilterTest.java` - Pass `null` FlowProcessorSupport (tests don't need it)
- `TraceContextWebFilterTest.java` - Pass `null` FlowProcessorSupport (tests don't need it)

## Impact & Benefits

### Memory Leak Prevention
✅ ThreadLocal state is now cleaned up at multiple levels:
1. **Aspect level** - When root flows complete
2. **Filter level** - At request boundaries (safety net)
3. **Pop level** - When flow stack becomes empty

### Defense in Depth
The fix uses multiple layers of protection:
- Aspect cleanup (primary)
- Filter cleanup (safety net)
- Improved pop() logic (opportunistic cleanup)

### Backwards Compatible
- Filters accept `null` FlowProcessorSupport (graceful degradation)
- Auto-configuration uses `required = false` for optional dependency
- Existing code continues to work

## Monitoring & Warnings

The fix includes logging to detect issues:

```java
log.warn("Cleaning up {} unpopped FlowEvent(s) from thread {}. 
         This may indicate improper flow nesting or missing exception handling.")
```

### What to Watch For
- **Warning logs about unpopped events** → Indicates bugs in flow nesting
- **Warning logs about inconsistent nesting** → Indicates aspect execution issues
- **No warnings** → System is operating correctly

## Testing Recommendations

### 1. Load Testing
Run load tests with thread pool monitoring to verify:
- Thread-local memory doesn't grow over time
- Pooled threads are properly cleaned between requests

### 2. Exception Path Testing
Test scenarios with exceptions to ensure:
- ThreadLocals are cleaned even when flows fail
- No memory accumulation on error paths

### 3. Async Processing
Test with `@Async` methods and scheduled tasks:
- Verify InheritableThreadLocal doesn't cause issues
- Consider switching to regular ThreadLocal + context propagation if needed

## Future Considerations

### Replace InheritableThreadLocal
Consider replacing `InheritableThreadLocal` with regular `ThreadLocal` plus explicit context propagation:
- Use Spring's `TaskDecorator` for `@Async` methods
- Use context propagation libraries for reactive flows
- Prevents issues with child threads inheriting stale context

### Thread Pool Metrics
Add monitoring for:
- ThreadLocal memory usage per thread
- Number of flows created vs. cleaned up
- Thread pool health metrics

## Files Modified

1. `obsinity-collection-core/src/main/java/com/obsinity/flow/processor/FlowProcessorSupport.java`
2. `obsinity-collection-spring/src/main/java/com/obsinity/collection/spring/aspect/FlowAspect.java`
3. `obsinity-collection-spring/src/main/java/com/obsinity/collection/spring/web/TraceContextFilter.java`
4. `obsinity-collection-spring/src/main/java/com/obsinity/collection/spring/webflux/TraceContextWebFilter.java`
5. `obsinity-collection-spring/src/main/java/com/obsinity/collection/spring/autoconfigure/TraceAutoConfiguration.java`
6. `obsinity-collection-spring/src/main/java/com/obsinity/collection/spring/autoconfigure/WebfluxTraceAutoConfiguration.java`
7. `obsinity-collection-spring/src/test/java/com/obsinity/collection/spring/TraceContextFilterTest.java`
8. `obsinity-collection-spring/src/test/java/com/obsinity/collection/spring/TraceContextWebFilterTest.java`

## Severity
**HIGH** - ThreadLocal leaks in production environments with thread pools can cause:
- OutOfMemoryError after extended operation
- Degraded performance as memory pressure increases
- Application crashes requiring restarts

