package com.obsinity.ingest.rabbitmq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.obsinity.service.core", "com.obsinity.ingest.rabbitmq"})
public class RabbitMqIngestApplication {

    public static void main(String[] args) {
        SpringApplication.run(RabbitMqIngestApplication.class, args);
    }
}
