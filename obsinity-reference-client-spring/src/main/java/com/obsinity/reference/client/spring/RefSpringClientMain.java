package com.obsinity.reference.client.spring;

import com.obsinity.client.core.ObsinityClient;
import com.obsinity.client.core.TelemetryContext;
import com.obsinity.client.transport.webclient.WebClientEventSender;

public class RefSpringClientMain {
    public static void main(String[] args) throws Exception {
        TelemetryContext.putAttr("app", "spring-webclient");
        try (var client = new ObsinityClient(new WebClientEventSender())) {
            client.recordFlow("demo.spring");
            System.out.println("Sent demo flow via Spring WebClient.");
        }
    }
}
