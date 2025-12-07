# Why Hibernate Entities in Flow Context are Problematic

## Executive Summary

**DO NOT** pass Hibernate/JPA entities, database connections, or any session-bound objects to `@PushAttribute`/`@PushContextValue` annotations or `FlowContext.putAttr()`/`FlowContext.putContext()` methods. These are functionally equivalent—the annotations are merely syntactic sugar for the manual API calls. The asynchronous, multi-threaded nature of the telemetry dispatch system makes this extremely dangerous and will cause runtime failures.

## The Core Problem: Async Dispatch Across Thread Boundaries

### How Obsinity Processes Telemetry

```
Request Thread (Web/API)          Async Dispatch Thread Pool
─────────────────────              ────────────────────────────
│
├─ @Flow method executes
│  └─ @PushAttribute(user)  ──┐
│     (Hibernate entity)      │
│                              │
├─ Flow completes              │
│  FlowEvent created           │
│  Entity reference stored ────┤
│                              │
└─ Request completes           │
   HTTP response sent          │
   Hibernate session closes ◄──┼─── SESSION CLOSED
                               │
                               ▼
                          [Event Queue]
                               │
                               ▼
                          Dispatcher picks up event
                               │
                               ▼
                          Different thread executes
                               │
                               ▼
                          Tries to access entity
                               │
                               ▼
                          💥 LazyInitializationException
                          💥 Session closed
                          💥 Proxy cannot initialize
```

### The Asynchronous Pipeline

Obsinity's telemetry system uses **asynchronous dispatch** for performance:

1. **Capture Phase** (Request Thread):
   - `@Flow` method executes on web request thread
   - Attributes/context extracted from parameters
   - FlowEvent created with references

2. **Queue Phase** (Lock-free):
   - FlowEvent added to concurrent dispatch queue
   - Immediate return to business logic
   - Zero blocking on telemetry

3. **Dispatch Phase** (Different Thread):
   - Background thread pool picks up events
   - Events sent to sinks (logging, database, HTTP)
   - **Thread boundary crossed** - original session gone!

## Specific Problems with Hibernate Entities

### 1. LazyInitializationException

**Most Common Failure Mode**

```java
// ❌ WRONG - Will fail with LazyInitializationException
@Flow(name = "user.update")
public void updateUser(@PushAttribute("user") User user) {
    userRepository.save(user);
} // Session closes here

// Later, in async dispatch thread:
sink.handle(event) {
    User user = event.attr("user", User.class);
    log.info("User: {}", user.getName());        // ✅ Works - eagerly loaded
    log.info("Orders: {}", user.getOrders());    // 💥 LazyInitializationException
    // Hibernate session closed - cannot load lazy collections
}
```

**Why it fails:**
- Hibernate entities use lazy loading for relationships
- Lazy proxies require an active Hibernate session
- Request thread's session is closed before async dispatch
- Dispatch thread has no session - proxy initialization fails

### 2. Detached Entity State

```java
// ❌ WRONG - Entity becomes detached
@Flow(name = "order.process")
public Order processOrder(@PushAttribute("order") Order order) {
    return orderService.process(order);
} // Session closes, entity detached

// Async dispatch (seconds/minutes later):
sink.handle(event) {
    Order order = event.attr("order", Order.class);
    order.getCustomer().getName();  // 💥 Could fail if lazy
    order.getItems().size();        // 💥 Collection not initialized
}
```

**Why it fails:**
- Entity transitions from **managed** to **detached** state
- No persistence context in dispatch thread
- Cannot traverse relationships
- Cannot reload or refresh entity

### 3. Proxy Serialization Issues

```java
// ❌ WRONG - Hibernate proxies don't serialize well
@Flow(name = "customer.export")
public void exportCustomer(@PushAttribute("customer") Customer customer) {
    exportService.export(customer);
}

// If sink serializes to JSON:
sink.handle(event) {
    Customer customer = event.attr("customer", Customer.class);
    String json = objectMapper.writeValueAsString(customer);
    // 💥 JsonMappingException: No serializer for Hibernate proxy
    // or infinite recursion on bidirectional relationships
}
```

**Why it fails:**
- Hibernate proxies (javassist, ByteBuddy) aren't serializable
- CGLIB proxies contain session references
- Jackson/JSON serializers don't handle proxies
- Bidirectional relationships cause infinite loops

### 4. Memory Leaks

```java
// ❌ WRONG - Massive memory leak
@Flow(name = "bulk.import")
public void importUsers(@PushAttribute("users") List<User> users) {
    // users list contains 100,000 entities
    userService.bulkImport(users);
} // List stays in memory in dispatch queue

// Async dispatch queue builds up:
// - 100,000 entities × N requests
// - All held in memory until dispatched
// - Persistence context held open
// - Eventually: OutOfMemoryError
```

**Why it fails:**
- Entities hold entire object graph in memory
- Collections not released until event dispatched
- Persistence context references kept alive
- Queue backlog amplifies memory usage

### 5. Thread Safety Issues

```java
// ❌ WRONG - Entity not thread-safe
@Flow(name = "account.update")
public void updateAccount(@PushAttribute("account") Account account) {
    account.setBalance(newBalance);  // Modified on request thread
    accountRepository.save(account);
} 

// Async dispatch (different thread):
sink.handle(event) {
    Account account = event.attr("account", Account.class);
    // Account object shared between threads
    // No synchronization - race condition possible
    // Could see partial updates or stale data
}
```

**Why it fails:**
- Hibernate entities are **NOT thread-safe**
- No synchronization between request and dispatch threads
- Entity state can be inconsistent
- Concurrent modification possible

## The Async Deque and Future Pluggable Mechanisms

### Current Architecture: AsyncDispatchBus

```java
public class AsyncDispatchBus {
    // Asynchronous, thread-safe queue
    private final BlockingQueue<FlowEvent> eventQueue;
    
    // Background thread pool
    private final ExecutorService dispatchExecutor;
    
    public void dispatch(FlowEvent event) {
        // Non-blocking enqueue
        eventQueue.offer(event);  // ← Entity reference stored here
        
        // Event sits in queue until dispatcher picks it up
        // Could be milliseconds or minutes later
        // Original thread continues, session closes
    }
}
```

### Thread Boundary Crossing

```
Thread Boundary
═══════════════════════════════════════════════════════════
Request Thread          │     Dispatch Thread
(Has Hibernate Session) │     (NO Session)
                        │
FlowEvent created ──────┼────→ Event queued
Entity captured         │
Business logic done     │
Session closed ◄────────┼───── Session GONE
Response sent           │
Thread returns to pool  │
                        │      Event picked up
                        │      Entity accessed ← 💥 BOOM
```

### Future Pluggable Mechanisms Make This Worse

The dispatch mechanism is designed to be **pluggable**, meaning future implementations could:

#### 1. Remote Queues (Kafka, RabbitMQ)
```java
// Future pluggable sink
public class KafkaSink implements FlowSinkHandler {
    public void handle(FlowEvent event) {
        // Serialize event to JSON
        String json = serialize(event);  // 💥 Cannot serialize Hibernate proxy
        kafka.send("telemetry", json);
    }
}
```

**Problem amplified:**
- Event must serialize to send to Kafka
- Hibernate proxies cannot serialize
- Lazy collections not initialized
- Cross-JVM boundary - session impossible to maintain

#### 2. Database Persistence
```java
// Future pluggable sink
public class DatabaseSink implements FlowSinkHandler {
    public void handle(FlowEvent event) {
        // Store event in database
        TelemetryRecord record = new TelemetryRecord();
        record.setAttributes(event.attributes().map());  // 💥 Hibernate entities in map
        recordRepository.save(record);  // 💥 Tries to cascade save detached entities
    }
}
```

**Problem amplified:**
- Nested Hibernate entities cause cascade issues
- Detached entities cannot be saved
- Duplicate key violations possible
- Transaction scope mismatched

#### 3. HTTP Export to External Systems
```java
// Future pluggable sink
public class HttpExportSink implements FlowSinkHandler {
    public void handle(FlowEvent event) {
        // Send to external monitoring system
        Map<String, Object> attrs = event.attributes().map();
        httpClient.post("https://telemetry.example.com", attrs);  // 💥 Entity in payload
    }
}
```

**Problem amplified:**
- Must serialize to JSON/XML
- Hibernate proxies break serialization
- Bidirectional relationships cause infinite loops
- External system cannot reconstruct entities

#### 4. Batch Processing with Delays
```java
// Future pluggable mechanism
public class BatchDispatcher {
    // Batch events for efficiency
    private List<FlowEvent> batch = new ArrayList<>();
    
    public void dispatch(FlowEvent event) {
        batch.add(event);  // Entity held in memory
        
        // Flush every 5 minutes or 1000 events
        if (batch.size() >= 1000 || lastFlush > 5.minutes()) {
            processBatch(batch);  // 💥 Entities held for 5 minutes!
        }
    }
}
```

**Problem amplified:**
- Entities held in memory for extended periods
- Sessions long closed
- Memory pressure from accumulated entities
- Cannot access any lazy data after 5 minutes

## Real-World Failure Scenarios

### Scenario 1: Lazy Collection Access

```java
@Service
public class OrderService {
    
    @Flow(name = "order.fulfill")
    public void fulfillOrder(@PushAttribute("order") Order order) {
        // Order entity with lazy-loaded items
        order.getItems().forEach(item -> {
            inventory.reserve(item);
        });
        orderRepository.save(order);
    }
}

// Async sink (different thread, later):
@Component
public class AuditSink implements FlowSinkHandler {
    public void handle(FlowEvent event) {
        Order order = event.attr("order", Order.class);
        
        // ✅ Works - eagerly loaded
        log.info("Order ID: {}", order.getId());
        
        // 💥 LazyInitializationException - session closed
        log.info("Item count: {}", order.getItems().size());
    }
}
```

**Error:**
```
org.hibernate.LazyInitializationException: 
  failed to lazily initialize a collection of role: Order.items, 
  could not initialize proxy - no Session
```

### Scenario 2: Proxy Serialization to Kafka

```java
@Service
public class UserService {
    
    @Flow(name = "user.register")
    public User registerUser(@PushAttribute("user") User user) {
        return userRepository.save(user);
    }
}

// Kafka sink (future implementation):
@Component
public class KafkaTelemetrySink implements FlowSinkHandler {
    public void handle(FlowEvent event) {
        User user = event.attr("user", User.class);
        
        // 💥 Cannot serialize Hibernate proxy
        String json = objectMapper.writeValueAsString(user);
        kafka.send("user-events", json);
    }
}
```

**Error:**
```
com.fasterxml.jackson.databind.JsonMappingException:
  No serializer found for class org.hibernate.proxy.pojo.bytebuddy.ByteBuddyInterceptor
```

### Scenario 3: Memory Leak from Entity Graphs

```java
@Service
public class ReportService {
    
    @Flow(name = "report.generate")
    public void generateReport(@PushAttribute("customers") List<Customer> customers) {
        // customers list contains 50,000 entities
        // each with lazy relationships to orders, addresses, etc.
        reportGenerator.generate(customers);
    }
}

// Result:
// - 50,000 Customer entities in dispatch queue
// - Each holds proxy references to Orders (lazy)
// - Queue backs up during peak traffic
// - Memory usage grows unbounded
// - Eventually: java.lang.OutOfMemoryError: GC overhead limit exceeded
```

## The Correct Approach: Extract Primitives

### ✅ CORRECT: Pass Primitive Values

```java
@Service
public class OrderService {
    
    @Flow(name = "order.fulfill")
    public void fulfillOrder(
            @PushAttribute("orderId") Long orderId,
            @PushAttribute("customerId") Long customerId,
            @PushAttribute("totalAmount") BigDecimal totalAmount,
            @PushAttribute("itemCount") int itemCount) {
        
        Order order = orderRepository.findById(orderId).orElseThrow();
        // Process order...
    }
}

// Async sink - works perfectly:
sink.handle(event) {
    Long orderId = event.attr("orderId", Long.class);        // ✅ Primitive
    Long customerId = event.attr("customerId", Long.class);  // ✅ Primitive
    BigDecimal amount = event.attr("totalAmount", BigDecimal.class);  // ✅ Serializable
    int count = event.attr("itemCount", Integer.class);      // ✅ Primitive
    
    log.info("Order {} for customer {} - ${} ({} items)", 
             orderId, customerId, amount, count);
    // All values work across thread boundaries
    // No session required
    // Fully serializable
}
```

### ✅ CORRECT: Use DTOs (Data Transfer Objects)

```java
// Immutable DTO - no Hibernate dependencies
public record UserDTO(
    Long id,
    String username,
    String email,
    LocalDateTime createdAt
) {
    // Convert from entity (in request thread, session active)
    public static UserDTO from(User entity) {
        return new UserDTO(
            entity.getId(),
            entity.getUsername(),
            entity.getEmail(),
            entity.getCreatedAt()
        );
    }
}

@Service
public class UserService {
    
    @Flow(name = "user.update")
    public void updateUser(@PushAttribute("user") UserDTO userDTO) {
        // ✅ DTO is serializable, immutable, thread-safe
        User entity = userRepository.findById(userDTO.id()).orElseThrow();
        entity.setEmail(userDTO.email());
        userRepository.save(entity);
    }
}

// Async sink - perfect:
sink.handle(event) {
    UserDTO user = event.attr("user", UserDTO.class);  // ✅ Works perfectly
    log.info("Updated user: {}", user.username());     // ✅ All data available
    kafka.send("user-updates", toJson(user));          // ✅ Serializes cleanly
}
```

### ✅ CORRECT: Extract Only What You Need

```java
@Service
public class CustomerService {
    
    @Flow(name = "customer.loyalty")
    public void updateLoyaltyPoints(
            @PushAttribute("customerId") Long customerId,
            @PushAttribute("customerName") String customerName,
            @PushAttribute("pointsAdded") int pointsAdded,
            @PushAttribute("newBalance") int newBalance) {
        
        Customer customer = customerRepository.findById(customerId).orElseThrow();
        customer.setLoyaltyPoints(newBalance);
        customerRepository.save(customer);
    }
}

// Benefits:
// ✅ Only 4 primitive values in telemetry
// ✅ No entity references
// ✅ No session dependencies
// ✅ Tiny memory footprint
// ✅ Fully serializable to any format
// ✅ Thread-safe across boundaries
// ✅ Works with any pluggable sink
```

## Manual FlowContext Operations Have the Same Issues

### Important: @PushAttribute is Just Syntactic Sugar

**Key Point:** `@PushAttribute("key")` and `FlowContext.putAttr("key", value)` are **functionally identical**. The annotation is merely syntactic sugar that extracts the parameter value and calls `FlowContext.putAttr()` internally. They both:
- Store data in the same `FlowEvent` object
- Go through the same async dispatch queue
- Cross the same thread boundary
- Face the same session closure issues

```java
// These two are EQUIVALENT:

// Option 1: Annotation (syntactic sugar)
@Flow(name = "user.update")
public void updateUser(@PushAttribute("user") User user) {
    // Internally calls: flowContext.putAttr("user", user)
}

// Option 2: Manual call (what the annotation does)
@Flow(name = "user.update")
public void updateUser(User user) {
    flowContext.putAttr("user", user);  // Same as annotation
}
```

### Using FlowContext Directly

Developers may think they can bypass entity detection by using `FlowContext` directly instead of `@PushAttribute`. **This is a misunderstanding**—they are the same operation, just with different syntax. Both suffer from the exact same async/threading issues.

```java
@Service
public class OrderService {
    
    @Autowired
    private FlowContext flowContext;
    
    @Flow(name = "order.process")
    public void processOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        
        // ❌ WRONG - Same problem as @PushAttribute!
        flowContext.putAttr("order", order);  // Entity stored in flow context
        
        // Process order...
        orderService.fulfill(order);
    } // Session closes here, entity becomes detached
}

// Later, in async dispatch:
sink.handle(event) {
    Order order = event.attr("order", Order.class);
    // 💥 Same LazyInitializationException
    // 💥 Same session boundary issue
    // 💥 Same thread boundary problem
    log.info("Items: {}", order.getItems().size());
}
```

### Why FlowContext Doesn't Help

Since `@PushAttribute` is just syntactic sugar for `FlowContext.putAttr()`, using the manual API changes nothing:

1. **Identical Operation** - `@PushAttribute("user")` literally calls `FlowContext.putAttr("user", value)` internally
2. **Same Event Queue** - Both store data in the same `FlowEvent` that goes into the async queue
3. **Same Thread Boundary** - Event is still processed on a different thread without the session
4. **Same Timing Issue** - Session closes before async dispatch, regardless of syntax used
5. **Less Validation** - Manual calls bypass entity detection, making the problem worse (harder to detect)

### The Illusion of Control

```java
@Flow(name = "customer.update")
public void updateCustomer(Long customerId) {
    Customer customer = customerRepository.findById(customerId).orElseThrow();
    
    // Developer thinks: "I'm manually adding it, so I have control"
    flowContext.putAttr("customer", customer);
    flowContext.putAttr("customerId", customer.getId());  // Also add ID
    
    // Reality: The entity reference is STILL in the async queue
    // The session STILL closes after this method returns
    // The dispatch thread STILL has no session
} // 💥 Session closed, entity detached, problem persists
```

### FlowContext Doesn't Change Anything

Since `@PushAttribute` is syntactic sugar for `FlowContext.putAttr()`, switching between them is like:
- Writing `i++` vs `i = i + 1`—same operation, different syntax
- Calling `list.isEmpty()` vs `list.size() == 0`—same result, different expression
- Using method reference vs lambda—same behavior, different style

**The async dispatch architecture** is the fundamental issue. Whether you write the annotation or call the method directly, the entity still ends up in the async queue with no session.

### Even Worse: No Validation

When you use `FlowContext` manually, you bypass the entity detection validation:

```java
// With @PushAttribute:
@Flow(name = "user.create")
public void createUser(@PushAttribute("user") User user) {
    // 💥 Caught immediately: "Hibernate entity detected"
}

// With FlowContext:
@Flow(name = "user.create")
public void createUser(User user) {
    flowContext.putAttr("user", user);  // ⚠️ No validation - silent failure
    // Entity stored, no warning, problem discovered in production
}
```

**Result:** The error happens later in production during async dispatch instead of immediately during development.

### The Same Solution Applies

Whether using `@PushAttribute` or `FlowContext`, the solution is identical:

**❌ WRONG - FlowContext with entity:**
```java
@Flow(name = "order.ship")
public void shipOrder(Long orderId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    flowContext.putAttr("order", order);  // 💥 Entity - will fail later
    // ...
}
```

**✅ CORRECT - FlowContext with primitives:**
```java
@Flow(name = "order.ship")
public void shipOrder(Long orderId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    
    // Extract what you need while session is active
    flowContext.putAttr("orderId", order.getId());
    flowContext.putAttr("customerId", order.getCustomer().getId());
    flowContext.putAttr("itemCount", order.getItems().size());
    flowContext.putAttr("totalAmount", order.getTotalAmount());
    // ✅ All primitives/immutables - safe across thread boundaries
    // ...
}
```

**✅ CORRECT - FlowContext with DTO:**
```java
@Flow(name = "order.ship")
public void shipOrder(Long orderId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    
    // Convert to DTO while session is active
    OrderDTO dto = OrderDTO.from(order);
    flowContext.putAttr("order", dto);
    // ✅ DTO is serializable, immutable, thread-safe
    // ...
}
```

### FlowContext in Event Context (Ephemeral)

The same applies to `putContext()` for ephemeral event context:

```java
// ❌ WRONG - Same problem with event context
@Flow(name = "audit.action")
public void auditAction(Long userId) {
    User user = userRepository.findById(userId).orElseThrow();
    flowContext.putContext("user", user);  // 💥 Entity - same async issue
    // ...
}

// ✅ CORRECT - Primitives in event context
@Flow(name = "audit.action")
public void auditAction(Long userId) {
    User user = userRepository.findById(userId).orElseThrow();
    flowContext.putContext("userId", user.getId());
    flowContext.putContext("username", user.getUsername());
    // ✅ Safe across thread boundaries
    // ...
}
```

### Summary: They're the Same Operation

Both approaches are functionally identical—`@PushAttribute` is just syntactic sugar:

| Aspect | @PushAttribute | FlowContext.putAttr() |
|--------|----------------|----------------------|
| **Async dispatch** | ✓ Yes - queued | ✓ Yes - queued |
| **Thread boundary** | ✓ Crossed | ✓ Crossed |
| **Session closes** | ✓ Before dispatch | ✓ Before dispatch |
| **LazyInit exception** | ✓ Will occur | ✓ Will occur |
| **Memory leaks** | ✓ Possible | ✓ Possible |
| **Entity detection** | ✓ Validated | ✗ **Not validated** |
| **Problem severity** | High | **Higher** (silent) |

**Key Takeaway:** The method of adding data to FlowEvent is irrelevant. The async dispatch architecture means **all** data must be session-independent, regardless of how it's added.

## Entity Detection and Prevention

### How Obsinity Detects Entities

The validation system checks for:

1. **Hibernate Proxy Patterns**
   - `javassist`, `cglib`, `ByteBuddy` in class name
   - `HibernateProxy` interface implementation

2. **JPA Annotations**
   - `@Entity` (jakarta.persistence or javax.persistence)
   - `@Table`, `@MappedSuperclass`

3. **Hibernate-Specific**
   - `org.hibernate.annotations.Entity`

### When Detection Occurs

```java
@Flow(name = "user.create")
public void createUser(@PushAttribute("user") User user) {  // ← Validated here
    // 💥 IllegalArgumentException thrown immediately
    // "Hibernate/JPA entity detected in @PushAttribute: User"
    // "Entities cannot be used in flow attributes due to session/threading issues"
}
```

### Configuration Options

```yaml
obsinity:
  collection:
    validation:
      hibernate-entity-check: true  # Default - throws exception
      # Set to false to log ERROR instead (not recommended)
```

**Recommendation:** Keep validation **enabled** (throws exception) to catch issues during development, not production.

## Summary: The Golden Rules

### ❌ NEVER Pass:
- Hibernate/JPA entities (via `@PushAttribute` OR `FlowContext.putAttr()`)
- Entity collections (List<User>, Set<Order>)
- Hibernate proxies
- Database connections
- EntityManager/Session references
- Transaction-bound objects
- Any object tied to a persistence context

**Important:** This applies to ALL methods of adding data:
- `@PushAttribute` annotations
- `@PushContextValue` annotations  
- `FlowContext.putAttr()` manual calls
- `FlowContext.putContext()` manual calls
- Any other mechanism that stores data in FlowEvent

### ✅ ALWAYS Pass:
- Primitive types (Long, Integer, String, Boolean)
- Immutable DTOs/Records
- Serializable value objects
- Extracted IDs and key fields
- Pre-computed aggregates
- Thread-safe, session-independent data

### Why This Matters:
1. **Async Dispatch** - Event processed on different thread, session closed
2. **Thread Boundaries** - No persistence context in dispatch thread
3. **Pluggable Sinks** - Future sinks may serialize, batch, or remote-send events
4. **Memory Safety** - Entities create memory leaks and hold large graphs
5. **Reliability** - LazyInitializationException crashes telemetry pipeline

## Conclusion

The asynchronous, multi-threaded nature of Obsinity's telemetry system makes it **fundamentally incompatible** with Hibernate entities or any session-bound objects. The thread boundary between capture (request thread with active session) and dispatch (background thread with no session) creates an insurmountable gap.

**Future pluggable mechanisms** (Kafka, databases, HTTP exports, batching) will only make this problem worse, as they add serialization, persistence, and timing constraints that Hibernate entities cannot satisfy.

**The solution is simple:** Extract primitive values or create immutable DTOs **before** the session closes. This ensures your telemetry data is thread-safe, serializable, and session-independent—ready for any current or future dispatch mechanism.

---

**Remember:** Telemetry capture happens in the **"now"** (request thread, active session), but telemetry dispatch happens in the **"later"** (different thread, no session). Design your attributes accordingly.

