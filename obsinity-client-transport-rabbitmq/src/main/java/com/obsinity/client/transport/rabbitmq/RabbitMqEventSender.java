package com.obsinity.client.transport.rabbitmq;

import com.obsinity.client.transport.EventSender;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ-based {@link EventSender} that publishes Obsinity payloads to a configured exchange/routing key.
 */
public class RabbitMqEventSender implements EventSender, Closeable {

    private final ConnectionFactory factory;
    private final String exchange;
    private final String routingKey;
    private final boolean mandatoryPublish;

    private volatile Connection connection;
    private volatile Channel channel;

    public RabbitMqEventSender() {
        this(buildFactoryFromEnv(), resolve("obsinity.rmq.exchange", "OBSINITY_RMQ_EXCHANGE", "obsinity.events"), resolve(
                "obsinity.rmq.routing-key", "OBSINITY_RMQ_ROUTING_KEY", "flows"), Boolean.parseBoolean(resolve(
                "obsinity.rmq.mandatory", "OBSINITY_RMQ_MANDATORY", "false")));
    }

    RabbitMqEventSender(ConnectionFactory factory, String exchange, String routingKey, boolean mandatoryPublish) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.routingKey = Objects.requireNonNull(routingKey, "routingKey");
        this.mandatoryPublish = mandatoryPublish;
    }

    @Override
    public synchronized void send(byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            throw new IOException("Payload is empty");
        }

        try {
            Channel ch = obtainChannel();
            ch.basicPublish(
                    exchange,
                    routingKey,
                    mandatoryPublish,
                    AMQP.BasicProperties.Builder.newInstance()
                            .contentType("application/json")
                            .deliveryMode(2)
                            .build(),
                    body);
        } catch (TimeoutException e) {
            throw new IOException("Failed to publish to RabbitMQ", e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        IOException suppressed = null;
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (Exception ex) {
            suppressed = new IOException("Failed to close RabbitMQ channel", ex);
        } finally {
            channel = null;
        }

        try {
            if (connection != null) {
                connection.close();
            }
        } catch (Exception ex) {
            if (suppressed == null) {
                suppressed = new IOException("Failed to close RabbitMQ connection", ex);
            } else {
                suppressed.addSuppressed(ex);
            }
        } finally {
            connection = null;
        }

        if (suppressed != null) throw suppressed;
    }

    private Channel obtainChannel() throws IOException, TimeoutException {
        if (connection == null || !connection.isOpen()) {
            connection = factory.newConnection("obsinity-collection");
        }
        if (channel == null || !channel.isOpen()) {
            channel = connection.createChannel();
        }
        return channel;
    }

    private static ConnectionFactory buildFactoryFromEnv() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(resolve("obsinity.rmq.host", "OBSINITY_RMQ_HOST", "localhost"));
        factory.setPort(Integer.parseInt(resolve("obsinity.rmq.port", "OBSINITY_RMQ_PORT", "5672")));
        factory.setUsername(resolve("obsinity.rmq.username", "OBSINITY_RMQ_USERNAME", "guest"));
        factory.setPassword(resolve("obsinity.rmq.password", "OBSINITY_RMQ_PASSWORD", "guest"));
        factory.setVirtualHost(resolve("obsinity.rmq.vhost", "OBSINITY_RMQ_VHOST", "/"));
        factory.setConnectionTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setAutomaticRecoveryEnabled(true);
        return factory;
    }

    private static String resolve(String property, String env, String fallback) {
        String sys = System.getProperty(property);
        if (sys != null && !sys.isBlank()) return sys.trim();
        String envValue = System.getenv(env);
        if (envValue != null && !envValue.isBlank()) return envValue.trim();
        return fallback;
    }

    @Override
    public String toString() {
        return String.format(
                Locale.ROOT,
                "RabbitMqEventSender[%s -> %s/%s]",
                factory.getHost(),
                exchange,
                routingKey);
    }
}
