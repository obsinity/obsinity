package com.obsinity.service.core.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.StateExtractorDefinition;
import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.repo.StateSnapshotRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StateDetectionServiceTest {

    @Test
    void detectMatchesResolvesNestedAttributes() {
        StateDetectionService service = new StateDetectionService(null, null);
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
        StateDetectionService service = new StateDetectionService(null, null);
        Map<String, Object> attributes = Map.of("http", Map.of("status", "200"));

        StateExtractorDefinition extractor =
                new StateExtractorDefinition("http_request", "ApiRoute", "api.name", List.of("http.status"));

        assertThat(service.detectMatches(List.of(extractor), attributes, "event-1"))
                .isEmpty();
    }

    @Test
    void processPersistsSnapshotsForMatches() {
        ConfigLookup lookup = mock(ConfigLookup.class);
        StateSnapshotRepository repo = mock(StateSnapshotRepository.class);
        StateDetectionService service = new StateDetectionService(lookup, repo);

        StateExtractorDefinition extractor = new StateExtractorDefinition(
                "user_profile.updated", "UserProfile", "user.profile_id", List.of("user.status"));
        UUID serviceId = UUID.randomUUID();

        when(lookup.stateExtractors(serviceId, "user_profile.updated")).thenReturn(List.of(extractor));

        EventEnvelope envelope = EventEnvelope.builder()
                .serviceId("payments")
                .eventType("user_profile.updated")
                .eventId(UUID.randomUUID().toString())
                .timestamp(Instant.now())
                .ingestedAt(Instant.now())
                .attributes(Map.of("user", Map.of("profile_id", "profile-123", "status", "ACTIVE")))
                .build();

        service.process(serviceId, envelope);

        verify(repo).upsert(serviceId, "UserProfile", "profile-123", "user.status", "ACTIVE");
    }
}
