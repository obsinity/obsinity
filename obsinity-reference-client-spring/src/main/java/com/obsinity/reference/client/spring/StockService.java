package com.obsinity.reference.client.spring;

import com.obsinity.collection.api.annotations.Kind;
import com.obsinity.collection.api.annotations.PushAttribute;
import com.obsinity.collection.api.annotations.Step;
import io.opentelemetry.api.trace.SpanKind;
import org.springframework.stereotype.Component;

@Component
public class StockService {

    @Step("demo.reserve.stock")
    @Kind(SpanKind.INTERNAL)
    public void verifyStock(@PushAttribute("sku") String sku) {
        // nested step to demonstrate multi-level step recording
    }
}
