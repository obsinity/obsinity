package com.obsinity.service.core.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.service.core.config.StateExtractorDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StateDetectionServiceTest {

    @Test
    void detectMatchesResolvesNestedAttributes() {
        StateDetectionService service = new StateDetectionService(null);
        Map<String, Object> attributes = Map.of(
                "api", Map.of("name", "checkout"),
                "http", Map.of("status", "500", "phase", "retry"));

        StateExtractorDefinition extractor = new StateExtractorDefinition(
                "http_request", "ApiRoute", "api.name", List.of("http.status", "http.phase", "missing"));

        var matches = service.detectMatches(List.of(extractor), attributes, "event-1");

        assertThat(matches).hasSize(1);
        var match = matches.get(0);
        assertThat(match.objectId()).isEqualTo("checkout");
        assertThat(match.stateValues()).containsEntry("http.status", "500").containsEntry("http.phase", "retry");
    }

    @Test
    void detectMatchesSkipsWhenObjectIdMissing() {
        StateDetectionService service = new StateDetectionService(null);
        Map<String, Object> attributes = Map.of("http", Map.of("status", "200"));

        StateExtractorDefinition extractor =
                new StateExtractorDefinition("http_request", "ApiRoute", "api.name", List.of("http.status"));

        assertThat(service.detectMatches(List.of(extractor), attributes, "event-1"))
                .isEmpty();
    }
}
