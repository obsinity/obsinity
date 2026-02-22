package com.obsinity.service.core.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.StateExtractorDefinition;
import com.obsinity.service.core.counter.CounterGranularity;
import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.repo.ObjectStateCountRepository;
import com.obsinity.service.core.repo.StateSnapshotRepository;
import com.obsinity.service.core.state.transition.StateTransitionBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StateDetectionServiceTest {

    @Test
    void detectMatchesResolvesNestedAttributes() {
        StateDetectionService service = new StateDetectionService(null, null, null, null);
        Map<String, Object> attributes = Map.of(
                "api", Map.of("name", "checkout"),
                "http", Map.of("status", "500", "phase", "retry"));

        StateExtractorDefinition extractor = new StateExtractorDefinition(
                "http_request", "ApiRoute", "api.name", List.of("http.status", "http.phase", "missing"), List.of("?"));

        var matches = service.detectMatches(List.of(extractor), attributes, "event-1");

        assertThat(matches).hasSize(1);
        var match = matches.get(0);
        assertThat(match.objectId()).isEqualTo("checkout");
        assertThat(match.stateValues()).containsEntry("http.status", "500").containsEntry("http.phase", "retry");
    }

    @Test
    void detectMatchesSkipsWhenObjectIdMissing() {
        StateDetectionService service = new StateDetectionService(null, null, null, null);
        Map<String, Object> attributes = Map.of("http", Map.of("status", "200"));

        StateExtractorDefinition extractor = new StateExtractorDefinition(
                "http_request", "ApiRoute", "api.name", List.of("http.status"), List.of("?"));

        assertThat(service.detectMatches(List.of(extractor), attributes, "event-1"))
                .isEmpty();
    }

    @Test
    void processPersistsSnapshotsForMatches() {
        ConfigLookup lookup = mock(ConfigLookup.class);
        StateSnapshotRepository snapshotRepository = mock(StateSnapshotRepository.class);
        ObjectStateCountRepository countRepository = mock(ObjectStateCountRepository.class);
        StateTransitionBuffer transitionBuffer = mock(StateTransitionBuffer.class);
        StateDetectionService service =
                new StateDetectionService(lookup, snapshotRepository, countRepository, transitionBuffer);

        StateExtractorDefinition extractor = new StateExtractorDefinition(
                "user_profile.updated", "UserProfile", "user.profile_id", List.of("user.status"), List.of("?"));
        UUID serviceId = UUID.randomUUID();

        when(lookup.stateExtractors(serviceId, "user_profile.updated")).thenReturn(List.of(extractor));
        when(snapshotRepository.findLatest(serviceId, "UserProfile", "profile-123", "user.status"))
                .thenReturn(null);

        Instant now = Instant.now();
        EventEnvelope envelope = EventEnvelope.builder()
                .serviceId("payments")
                .eventType("user_profile.updated")
                .name("user_profile.updated")
                .eventId(UUID.randomUUID().toString())
                .timestamp(now)
                .ingestedAt(now)
                .attributes(Map.of("user", Map.of("profile_id", "profile-123", "status", "ACTIVE")))
                .build();

        service.process(serviceId, envelope);

        verify(snapshotRepository).upsert(serviceId, "UserProfile", "profile-123", "user.status", "ACTIVE", now);
        verify(countRepository).increment(serviceId, "UserProfile", "user.status", "ACTIVE");
        verify(countRepository, never()).decrement(serviceId, "UserProfile", "user.status", "ACTIVE");
        long expectedEpoch = CounterGranularity.S5.baseBucket().align(now).getEpochSecond();
        verify(transitionBuffer)
                .increment(
                        CounterGranularity.S5,
                        expectedEpoch,
                        serviceId,
                        "UserProfile",
                        "user.status",
                        "(none)",
                        "ACTIVE");
    }

    @Test
    void processSkipsWhenStateUnchanged() {
        ConfigLookup lookup = mock(ConfigLookup.class);
        StateSnapshotRepository snapshotRepository = mock(StateSnapshotRepository.class);
        ObjectStateCountRepository countRepository = mock(ObjectStateCountRepository.class);
        StateTransitionBuffer transitionBuffer = mock(StateTransitionBuffer.class);
        StateDetectionService service =
                new StateDetectionService(lookup, snapshotRepository, countRepository, transitionBuffer);

        StateExtractorDefinition extractor = new StateExtractorDefinition(
                "user_profile.updated", "UserProfile", "user.profile_id", List.of("user.status"), List.of("?"));
        UUID serviceId = UUID.randomUUID();

        when(lookup.stateExtractors(serviceId, "user_profile.updated")).thenReturn(List.of(extractor));
        when(snapshotRepository.findLatest(serviceId, "UserProfile", "profile-123", "user.status"))
                .thenReturn("ACTIVE");

        Instant now = Instant.now();
        EventEnvelope envelope = EventEnvelope.builder()
                .serviceId("payments")
                .eventType("user_profile.updated")
                .name("user_profile.updated")
                .eventId(UUID.randomUUID().toString())
                .timestamp(now)
                .ingestedAt(now)
                .attributes(Map.of("user", Map.of("profile_id", "profile-123", "status", "ACTIVE")))
                .build();

        service.process(serviceId, envelope);

        verify(snapshotRepository, never())
                .upsert(serviceId, "UserProfile", "profile-123", "user.status", "ACTIVE", now);
        verify(countRepository, never()).increment(serviceId, "UserProfile", "user.status", "ACTIVE");
        verify(countRepository, never()).decrement(serviceId, "UserProfile", "user.status", "ACTIVE");
        verify(transitionBuffer, never())
                .increment(
                        org.mockito.Mockito.any(),
                        org.mockito.Mockito.anyLong(),
                        org.mockito.Mockito.any(),
                        org.mockito.Mockito.any(),
                        org.mockito.Mockito.any(),
                        org.mockito.Mockito.any(),
                        org.mockito.Mockito.any());
    }

    @Test
    void processRecordsTransitionWhenPriorStateExists() {
        ConfigLookup lookup = mock(ConfigLookup.class);
        StateSnapshotRepository snapshotRepository = mock(StateSnapshotRepository.class);
        ObjectStateCountRepository countRepository = mock(ObjectStateCountRepository.class);
        StateTransitionBuffer transitionBuffer = mock(StateTransitionBuffer.class);
        StateDetectionService service =
                new StateDetectionService(lookup, snapshotRepository, countRepository, transitionBuffer);

        StateExtractorDefinition extractor = new StateExtractorDefinition(
                "user_profile.updated", "UserProfile", "user.profile_id", List.of("user.status"), List.of("?"));
        UUID serviceId = UUID.randomUUID();
        when(lookup.stateExtractors(serviceId, "user_profile.updated")).thenReturn(List.of(extractor));
        when(snapshotRepository.findLatest(serviceId, "UserProfile", "profile-123", "user.status"))
                .thenReturn("ACTIVE");

        Instant now = Instant.parse("2025-01-01T00:00:02Z");
        EventEnvelope envelope = EventEnvelope.builder()
                .serviceId("payments")
                .eventType("user_profile.updated")
                .name("user_profile.updated")
                .eventId(UUID.randomUUID().toString())
                .timestamp(now)
                .ingestedAt(now)
                .attributes(Map.of("user", Map.of("profile_id", "profile-123", "status", "BLOCKED")))
                .build();

        service.process(serviceId, envelope);

        long expectedEpoch = CounterGranularity.S5.baseBucket().align(now).getEpochSecond();
        verify(transitionBuffer)
                .increment(
                        CounterGranularity.S5,
                        expectedEpoch,
                        serviceId,
                        "UserProfile",
                        "user.status",
                        "ACTIVE",
                        "BLOCKED");
        verify(countRepository).decrement(serviceId, "UserProfile", "user.status", "ACTIVE");
        verify(countRepository).increment(serviceId, "UserProfile", "user.status", "BLOCKED");
    }

    @Test
    void processSupportsLatestPlusExplicitTransitionPolicy() {
        ConfigLookup lookup = mock(ConfigLookup.class);
        StateSnapshotRepository snapshotRepository = mock(StateSnapshotRepository.class);
        ObjectStateCountRepository countRepository = mock(ObjectStateCountRepository.class);
        StateTransitionBuffer transitionBuffer = mock(StateTransitionBuffer.class);
        StateDetectionService service =
                new StateDetectionService(lookup, snapshotRepository, countRepository, transitionBuffer);

        StateExtractorDefinition extractor = new StateExtractorDefinition(
                "user_profile.updated", "UserProfile", "user.profile_id", List.of("user.status"), List.of("?", "NEW"));
        UUID serviceId = UUID.randomUUID();
        when(lookup.stateExtractors(serviceId, "user_profile.updated")).thenReturn(List.of(extractor));
        when(snapshotRepository.findLatest(serviceId, "UserProfile", "profile-123", "user.status"))
                .thenReturn("ACTIVE");
        when(snapshotRepository.findStateHistoryValues(serviceId, "UserProfile", "profile-123", "user.status", 1000))
                .thenReturn(List.of("ACTIVE", "NEW"));

        Instant now = Instant.parse("2025-01-01T00:00:02Z");
        EventEnvelope envelope = EventEnvelope.builder()
                .serviceId("payments")
                .eventType("user_profile.updated")
                .name("user_profile.updated")
                .eventId(UUID.randomUUID().toString())
                .timestamp(now)
                .ingestedAt(now)
                .attributes(Map.of("user", Map.of("profile_id", "profile-123", "status", "SUSPENDED")))
                .build();

        service.process(serviceId, envelope);

        long expectedEpoch = CounterGranularity.S5.baseBucket().align(now).getEpochSecond();
        verify(transitionBuffer)
                .increment(
                        CounterGranularity.S5,
                        expectedEpoch,
                        serviceId,
                        "UserProfile",
                        "user.status",
                        "ACTIVE",
                        "SUSPENDED");
        verify(transitionBuffer)
                .increment(
                        CounterGranularity.S5,
                        expectedEpoch,
                        serviceId,
                        "UserProfile",
                        "user.status",
                        "NEW",
                        "SUSPENDED");
    }

    @Test
    void processSupportsAllTransitionPolicy() {
        ConfigLookup lookup = mock(ConfigLookup.class);
        StateSnapshotRepository snapshotRepository = mock(StateSnapshotRepository.class);
        ObjectStateCountRepository countRepository = mock(ObjectStateCountRepository.class);
        StateTransitionBuffer transitionBuffer = mock(StateTransitionBuffer.class);
        StateDetectionService service =
                new StateDetectionService(lookup, snapshotRepository, countRepository, transitionBuffer);

        StateExtractorDefinition extractor = new StateExtractorDefinition(
                "user_profile.updated", "UserProfile", "user.profile_id", List.of("user.status"), List.of("*", "NEW"));
        UUID serviceId = UUID.randomUUID();
        when(lookup.stateExtractors(serviceId, "user_profile.updated")).thenReturn(List.of(extractor));
        when(snapshotRepository.findLatest(serviceId, "UserProfile", "profile-123", "user.status"))
                .thenReturn("ACTIVE");
        when(snapshotRepository.findStateHistoryValues(serviceId, "UserProfile", "profile-123", "user.status", 1000))
                .thenReturn(List.of("ACTIVE", "NEW", "BLOCKED"));

        Instant now = Instant.parse("2025-01-01T00:00:02Z");
        EventEnvelope envelope = EventEnvelope.builder()
                .serviceId("payments")
                .eventType("user_profile.updated")
                .name("user_profile.updated")
                .eventId(UUID.randomUUID().toString())
                .timestamp(now)
                .ingestedAt(now)
                .attributes(Map.of("user", Map.of("profile_id", "profile-123", "status", "INACTIVE")))
                .build();

        service.process(serviceId, envelope);

        long expectedEpoch = CounterGranularity.S5.baseBucket().align(now).getEpochSecond();
        verify(transitionBuffer)
                .increment(
                        CounterGranularity.S5,
                        expectedEpoch,
                        serviceId,
                        "UserProfile",
                        "user.status",
                        "ACTIVE",
                        "INACTIVE");
        verify(transitionBuffer)
                .increment(
                        CounterGranularity.S5,
                        expectedEpoch,
                        serviceId,
                        "UserProfile",
                        "user.status",
                        "NEW",
                        "INACTIVE");
        verify(transitionBuffer)
                .increment(
                        CounterGranularity.S5,
                        expectedEpoch,
                        serviceId,
                        "UserProfile",
                        "user.status",
                        "BLOCKED",
                        "INACTIVE");
    }

    @Test
    void processTreatsConfiguredFromStatesAsAdditiveByDefault() {
        ConfigLookup lookup = mock(ConfigLookup.class);
        StateSnapshotRepository snapshotRepository = mock(StateSnapshotRepository.class);
        ObjectStateCountRepository countRepository = mock(ObjectStateCountRepository.class);
        StateTransitionBuffer transitionBuffer = mock(StateTransitionBuffer.class);
        StateDetectionService service =
                new StateDetectionService(lookup, snapshotRepository, countRepository, transitionBuffer);

        StateExtractorDefinition extractor = new StateExtractorDefinition(
                "user_profile.updated", "UserProfile", "user.profile_id", List.of("user.status"), List.of("NEW"));
        UUID serviceId = UUID.randomUUID();
        when(lookup.stateExtractors(serviceId, "user_profile.updated")).thenReturn(List.of(extractor));
        when(snapshotRepository.findLatest(serviceId, "UserProfile", "profile-123", "user.status"))
                .thenReturn("PENDING_EMAIL");
        when(snapshotRepository.findStateHistoryValues(serviceId, "UserProfile", "profile-123", "user.status", 1000))
                .thenReturn(List.of("PENDING_EMAIL", "ACTIVE"));

        Instant now = Instant.parse("2025-01-01T00:00:02Z");
        EventEnvelope envelope = EventEnvelope.builder()
                .serviceId("payments")
                .eventType("user_profile.updated")
                .name("user_profile.updated")
                .eventId(UUID.randomUUID().toString())
                .timestamp(now)
                .ingestedAt(now)
                .attributes(Map.of("user", Map.of("profile_id", "profile-123", "status", "SUSPENDED")))
                .build();

        service.process(serviceId, envelope);

        long expectedEpoch = CounterGranularity.S5.baseBucket().align(now).getEpochSecond();
        verify(transitionBuffer)
                .increment(
                        CounterGranularity.S5,
                        expectedEpoch,
                        serviceId,
                        "UserProfile",
                        "user.status",
                        "PENDING_EMAIL",
                        "SUSPENDED");
    }

    @Test
    void processSupportsRestrictiveOnlyTransitionPolicy() {
        ConfigLookup lookup = mock(ConfigLookup.class);
        StateSnapshotRepository snapshotRepository = mock(StateSnapshotRepository.class);
        ObjectStateCountRepository countRepository = mock(ObjectStateCountRepository.class);
        StateTransitionBuffer transitionBuffer = mock(StateTransitionBuffer.class);
        StateDetectionService service =
                new StateDetectionService(lookup, snapshotRepository, countRepository, transitionBuffer);

        StateExtractorDefinition extractor = new StateExtractorDefinition(
                "user_profile.updated",
                "UserProfile",
                "user.profile_id",
                List.of("user.status"),
                List.of("NEW"),
                List.of());
        UUID serviceId = UUID.randomUUID();
        when(lookup.stateExtractors(serviceId, "user_profile.updated")).thenReturn(List.of(extractor));
        when(snapshotRepository.findLatest(serviceId, "UserProfile", "profile-123", "user.status"))
                .thenReturn("PENDING_EMAIL");
        when(snapshotRepository.findStateHistoryValues(serviceId, "UserProfile", "profile-123", "user.status", 1000))
                .thenReturn(List.of("PENDING_EMAIL", "ACTIVE"));

        Instant now = Instant.parse("2025-01-01T00:00:02Z");
        EventEnvelope envelope = EventEnvelope.builder()
                .serviceId("payments")
                .eventType("user_profile.updated")
                .name("user_profile.updated")
                .eventId(UUID.randomUUID().toString())
                .timestamp(now)
                .ingestedAt(now)
                .attributes(Map.of("user", Map.of("profile_id", "profile-123", "status", "SUSPENDED")))
                .build();

        service.process(serviceId, envelope);

        verify(transitionBuffer, never())
                .increment(
                        org.mockito.Mockito.any(),
                        org.mockito.Mockito.anyLong(),
                        org.mockito.Mockito.eq(serviceId),
                        org.mockito.Mockito.eq("UserProfile"),
                        org.mockito.Mockito.eq("user.status"),
                        org.mockito.Mockito.anyString(),
                        org.mockito.Mockito.eq("SUSPENDED"));
    }
}
