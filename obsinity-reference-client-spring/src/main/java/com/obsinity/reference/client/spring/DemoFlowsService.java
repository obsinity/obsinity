package com.obsinity.reference.client.spring;

import com.obsinity.collection.api.annotations.Domain;
import com.obsinity.collection.api.annotations.Kind;
import com.obsinity.collection.api.annotations.OrphanAlert;
import com.obsinity.collection.api.annotations.PushAttribute;
import com.obsinity.collection.api.annotations.Step;
import io.opentelemetry.api.trace.SpanKind;
import org.springframework.stereotype.Component;

/**
 * Service exposing @Step methods used by the demo controller.
 */
@Component
public class DemoFlowsService {

    /**
     * Nested step inside a flow.
     * Demonstrates: @Step with attribute enrichment.
     */
    @Step("demo.reserve")
    @Kind(SpanKind.INTERNAL)
    @Domain("internal")
    public void reserveInventory(@PushAttribute("sku") String sku) {
        // pretend to reserve inventory
    }

    /**
     * Orphan step that fails.
     * Demonstrates: @Step auto-promoted to Flow + failure path.
     */
    @Step("demo.orphan.fail")
    @Kind(SpanKind.INTERNAL)
    @Domain("internal")
    @OrphanAlert(OrphanAlert.Level.WARN)
    public void orphanFail(@PushAttribute("reason") String reason) {
        throw new IllegalArgumentException("orphan-fail: " + reason);
    }
}
