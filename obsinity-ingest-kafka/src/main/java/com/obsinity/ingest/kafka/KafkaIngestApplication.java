package com.obsinity.ingest.kafka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.obsinity"})
public class KafkaIngestApplication {
    public static void main(String[] args) {
        SpringApplication.run(KafkaIngestApplication.class, args);
    }
}
