package com.obsinity.collection.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.collection.spring.webflux.TraceContextWebFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class TraceContextWebFilterTest {

    private final TraceContextWebFilter filter = new TraceContextWebFilter(null);

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void populates_from_traceparent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test")
                .header("traceparent", "00-4a3f1b5e2f9d4c1aa0b2c3d4e5f60718-7b2c3d4e5f607182-01")
                .build());
        final String[] vals = new String[2];
        WebFilterChain chain = e -> Mono.fromRunnable(() -> {
            vals[0] = MDC.get("traceId");
            vals[1] = MDC.get("spanId");
        });

        filter.filter(exchange, chain).block();

        assertThat(vals[0]).isEqualTo("4a3f1b5e2f9d4c1aa0b2c3d4e5f60718");
        assertThat(vals[1]).isEqualTo("7b2c3d4e5f607182");
    }

    @Test
    void populates_from_b3_single() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test")
                .header("b3", "4a3f1b5e2f9d4c1aa0b2c3d4e5f60718-7b2c3d4e5f607182-1")
                .build());
        final String[] vals = new String[2];
        WebFilterChain chain = e -> Mono.fromRunnable(() -> {
            vals[0] = MDC.get("traceId");
            vals[1] = MDC.get("spanId");
        });

        filter.filter(exchange, chain).block();

        assertThat(vals[0]).isEqualTo("4a3f1b5e2f9d4c1aa0b2c3d4e5f60718");
        assertThat(vals[1]).isEqualTo("7b2c3d4e5f607182");
    }
}
