package com.obsinity.reference.client;

import com.obsinity.client.core.ObsinityClient;
import com.obsinity.client.core.TelemetryContext;
import com.obsinity.client.transport.jdkhttp.JdkHttpEventSender;

public class RefClientMain {
    public static void main(String[] args) throws Exception {
        TelemetryContext.putAttr("user.id", "demo");
        try (var client = new ObsinityClient(new JdkHttpEventSender())) {
            client.recordFlow("demo.hello");
            System.out.println(
                    "Sent demo flow via JDK HttpClient → " + System.getProperty("obsinity.ingest.url", "(default)"));
        }
    }
}
