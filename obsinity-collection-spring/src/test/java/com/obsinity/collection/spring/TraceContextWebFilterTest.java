package com.obsinity.collection.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.collection.core.context.TelemetryContext;
import com.obsinity.collection.spring.webflux.TraceContextWebFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class TraceContextWebFilterTest {

    private final TraceContextWebFilter filter = new TraceContextWebFilter();

    @AfterEach
    void clear() { TelemetryContext.clear(); }

    @Test
    void populates_from_traceparent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header("traceparent", "00-4a3f1b5e2f9d4c1aa0b2c3d4e5f60718-7b2c3d4e5f607182-01")
                        .build());
        WebFilterChain chain = e -> Mono.empty();

        filter.filter(exchange, chain).block();

        var ctx = TelemetryContext.snapshotContext();
        assertThat(ctx.get("traceId")).isEqualTo("4a3f1b5e2f9d4c1aa0b2c3d4e5f60718");
        assertThat(ctx.get("spanId")).isEqualTo("7b2c3d4e5f607182");
    }

    @Test
    void populates_from_b3_single() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").header("b3", "4a3f1b5e2f9d4c1aa0b2c3d4e5f60718-7b2c3d4e5f607182-1").build());
        WebFilterChain chain = e -> Mono.empty();

        filter.filter(exchange, chain).block();

        var ctx = TelemetryContext.snapshotContext();
        assertThat(ctx.get("traceId")).isEqualTo("4a3f1b5e2f9d4c1aa0b2c3d4e5f60718");
        assertThat(ctx.get("spanId")).isEqualTo("7b2c3d4e5f607182");
    }
}
