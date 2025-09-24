package com.obsinity.collection.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.collection.core.context.TelemetryContext;
import com.obsinity.collection.spring.web.TraceContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceContextFilterTest {

    private final TraceContextFilter filter = new TraceContextFilter();

    @AfterEach
    void clear() {
        TelemetryContext.clear();
    }

    @Test
    void populates_from_traceparent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("traceparent", "00-4a3f1b5e2f9d4c1aa0b2c3d4e5f60718-7b2c3d4e5f607182-01");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        final String[] vals = new String[2];
        filter.doFilter(req, resp, (r, s2) -> {
            var ctx = TelemetryContext.snapshotContext();
            vals[0] = (String) ctx.get("traceId");
            vals[1] = (String) ctx.get("spanId");
        });

        assertThat(vals[0]).isEqualTo("4a3f1b5e2f9d4c1aa0b2c3d4e5f60718");
        assertThat(vals[1]).isEqualTo("7b2c3d4e5f607182");
    }

    @Test
    void populates_from_b3_single() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("b3", "4a3f1b5e2f9d4c1aa0b2c3d4e5f60718-7b2c3d4e5f607182-1");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        final String[] vals = new String[2];
        filter.doFilter(req, resp, (r, s2) -> {
            var ctx = TelemetryContext.snapshotContext();
            vals[0] = (String) ctx.get("traceId");
            vals[1] = (String) ctx.get("spanId");
        });

        assertThat(vals[0]).isEqualTo("4a3f1b5e2f9d4c1aa0b2c3d4e5f60718");
        assertThat(vals[1]).isEqualTo("7b2c3d4e5f607182");
    }
}
