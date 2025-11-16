package com.obsinity.ingest.rabbitmq;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableRabbit
@ComponentScan(basePackageClasses = RabbitMqIngestListener.class)
@ConditionalOnProperty(prefix = "obsinity.ingest.rmq", name = "enabled", havingValue = "true")
public class RabbitMqIngestConfiguration {}
