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

@RestController
public class DemoController {
    private final DemoFlowsService flows;

    public DemoController(DemoFlowsService flows) {
        this.flows = flows;
    }

    // 1) Basic flow success
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

    // 2) Flow failure example
    @GetMapping(path = "/api/checkout/fail", produces = MediaType.APPLICATION_JSON_VALUE)
    @Flow(name = "demo.checkout")
    @Kind(SpanKind.SERVER)
    @Domain("http")
    public java.util.Map<String, Object> checkoutFail(
            @RequestParam("user") @PushAttribute("user.id") String userId,
            @RequestParam("items") @PushContextValue("cart.size") int items) {
        throw new IllegalStateException("simulated-failure for user " + userId);
    }

    // 3) Flow with nested step
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

    // 4) Orphan step (auto-promoted to a Flow)
    @GetMapping(path = "/api/orphan-step", produces = MediaType.TEXT_PLAIN_VALUE)
    @com.obsinity.collection.api.annotations.Step("demo.orphan.step")
    @OrphanAlert(OrphanAlert.Level.WARN)
    public String orphanStep(@RequestParam(value = "note", required = false, defaultValue = "hello")
            @PushAttribute("note") String note) {
        return "orphan step executed: " + note;
    }
}

