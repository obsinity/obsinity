package com.obsinity.reference.client.spring;

import com.obsinity.collection.api.annotations.PushAttribute;
import org.springframework.stereotype.Component;

@Component
public class DemoFlowsService {

    @com.obsinity.collection.api.annotations.Step("demo.reserve")
    public void reserveInventory(@PushAttribute("sku") String sku) {
        // pretend to reserve inventory
    }
}

