package com.obsinity.collection.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.collection.core.context.TelemetryContext;
import com.obsinity.collection.spring.web.TraceContextFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceContextFilterTest {

    private final TraceContextFilter filter = new TraceContextFilter();

    @AfterEach
    void clear() { TelemetryContext.clear(); }

    @Test
    void populates_from_traceparent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("traceparent", "00-4a3f1b5e2f9d4c1aa0b2c3d4e5f60718-7b2c3d4e5f607182-01");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain((ServletRequest r, ServletResponse s) -> {});

        filter.doFilter(req, resp, chain);

        var ctx = TelemetryContext.snapshotContext();
        assertThat(ctx.get("traceId")).isEqualTo("4a3f1b5e2f9d4c1aa0b2c3d4e5f60718");
        assertThat(ctx.get("spanId")).isEqualTo("7b2c3d4e5f607182");
    }

    @Test
    void populates_from_b3_single() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("b3", "4a3f1b5e2f9d4c1aa0b2c3d4e5f60718-7b2c3d4e5f607182-1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain((ServletRequest r, ServletResponse s) -> {});

        filter.doFilter(req, resp, chain);

        var ctx = TelemetryContext.snapshotContext();
        assertThat(ctx.get("traceId")).isEqualTo("4a3f1b5e2f9d4c1aa0b2c3d4e5f60718");
        assertThat(ctx.get("spanId")).isEqualTo("7b2c3d4e5f607182");
    }
}
