package com.obsinity.reference.client.spring;

import com.obsinity.collection.api.annotations.Domain;
import com.obsinity.collection.api.annotations.Flow;
import com.obsinity.collection.api.annotations.Kind;
import com.obsinity.collection.api.annotations.OrphanAlert;
import com.obsinity.collection.api.annotations.PushAttribute;
import com.obsinity.collection.api.annotations.PushContextValue;
import io.opentelemetry.api.trace.SpanKind;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo REST controller exposing endpoints that demonstrate various annotation combinations.
 *
 * Endpoints:
 * - GET /api/checkout               → @Flow success with @PushAttribute/@PushContextValue
 * - GET /api/checkout/fail          → @Flow failure (triggers failure handlers)
 * - GET /api/checkout/with-step     → @Flow with nested @Step in a service
 * - GET /api/orphan-step            → Orphan @Step auto-promoted to a Flow
 * - GET /api/client-call            → CLIENT-kind flow (simulating an outbound call)
 * - GET /api/produce                → PRODUCER-kind flow (simulating enqueue)
 *
 * Each method’s Javadoc describes what’s demonstrated.
 */
@RestController
public class DemoController {
    private final DemoFlowsService flows;

    public DemoController(DemoFlowsService flows) {
        this.flows = flows;
    }

    /**
     * Basic flow success.
     * Demonstrates: @Flow, @Kind(SERVER), @Domain("http"), @PushAttribute, @PushContextValue.
     */
    @GetMapping(path = "/api/checkout", produces = MediaType.APPLICATION_JSON_VALUE)
    @Flow(name = "demo.checkout")
    @Kind(SpanKind.SERVER)
    @Domain("http")
    public java.util.Map<String, Object> checkout(
            @RequestParam("user") @PushAttribute("user.id") String userId,
            @RequestParam("items") @PushContextValue("cart.size") int items) {
        // business logic...
        return java.util.Map.of("status", "ok", "user", userId, "items", items);
    }

    /**
     * Flow failure example.
     * Demonstrates: FAILED lifecycle; receivers with @OnFlowFailure and @OnOutcome(FAILURE).
     */
    @GetMapping(path = "/api/checkout/fail", produces = MediaType.APPLICATION_JSON_VALUE)
    @Flow(name = "demo.checkout")
    @Kind(SpanKind.SERVER)
    @Domain("http")
    public java.util.Map<String, Object> checkoutFail(
            @RequestParam("user") @PushAttribute("user.id") String userId,
            @RequestParam("items") @PushContextValue("cart.size") int items) {
        throw new IllegalStateException("simulated-failure for user " + userId);
    }

    /**
     * Flow with nested step.
     * Demonstrates: @Flow wrapping a service method annotated with @Step.
     */
    @GetMapping(path = "/api/checkout/with-step", produces = MediaType.APPLICATION_JSON_VALUE)
    @Flow(name = "demo.checkout")
    @Kind(SpanKind.SERVER)
    @Domain("http")
    public java.util.Map<String, Object> checkoutWithStep(
            @RequestParam("user") @PushAttribute("user.id") String userId,
            @RequestParam("items") @PushContextValue("cart.size") int items,
            @RequestParam(value = "sku", required = false, defaultValue = "sku-1") String sku) {
        flows.reserveInventory(sku);
        return java.util.Map.of("status", "ok", "user", userId, "items", items, "reservedSku", sku);
    }

    /**
     * Orphan step (auto-promoted to a Flow).
     * Demonstrates: @Step executed without active Flow, @OrphanAlert controlling log level.
     */
    @GetMapping(path = "/api/orphan-step", produces = MediaType.TEXT_PLAIN_VALUE)
    @com.obsinity.collection.api.annotations.Step("demo.orphan.step")
    @OrphanAlert(level = OrphanAlert.Level.WARN)
    public String orphanStep(@RequestParam(value = "note", required = false, defaultValue = "hello")
            @PushAttribute("note") String note) {
        return "orphan step executed: " + note;
    }
    /**
     * CLIENT-kind flow.
     * Demonstrates: @Kind(CLIENT) for outbound operations; attach custom attribute.
     */
    @GetMapping(path = "/api/client-call", produces = MediaType.APPLICATION_JSON_VALUE)
    @Flow(name = "demo.client.call")
    @Kind(SpanKind.CLIENT)
    @Domain("http")
    public java.util.Map<String, Object> clientCall(@RequestParam(value = "target", defaultValue = "service-x")
            @PushAttribute("client.target") String target) {
        // pretend to call an external service
        return java.util.Map.of("status", "ok", "target", target);
    }

    /**
     * PRODUCER-kind flow.
     * Demonstrates: @Kind(PRODUCER) for message production; attribute binding.
     */
    @GetMapping(path = "/api/produce", produces = MediaType.APPLICATION_JSON_VALUE)
    @Flow(name = "demo.produce")
    @Kind(SpanKind.PRODUCER)
    @Domain("messaging")
    public java.util.Map<String, Object> produce(@RequestParam(value = "topic", defaultValue = "demo-topic")
            @PushAttribute("messaging.topic") String topic) {
        // pretend to publish to a topic
        return java.util.Map.of("status", "queued", "topic", topic);
    }
}
