package com.obsinity.service.core.state.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.RatioQueryDefinition;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class RatioQueryServiceTest {

    @Mock
    private ServicesCatalogRepository servicesCatalogRepository;

    @Mock
    private ConfigLookup configLookup;

    @Mock
    private StateCountTimeseriesQueryRepository stateCountTimeseriesQueryRepository;

    @Mock
    private StateTransitionQueryRepository stateTransitionQueryRepository;

    private RatioQueryService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RatioQueryService(
                servicesCatalogRepository,
                configLookup,
                stateCountTimeseriesQueryRepository,
                stateTransitionQueryRepository);
    }

    @Test
    void computesStateRatiosAndPercents() {
        UUID serviceId = UUID.randomUUID();
        when(servicesCatalogRepository.findIdByServiceKey("payments")).thenReturn(serviceId);
        when(configLookup.ratioQuery(serviceId, "state_distribution"))
                .thenReturn(java.util.Optional.of(ratioStatesDefinition("state_distribution")));
        when(stateCountTimeseriesQueryRepository.fetchLatestCountsInRange(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        eq(List.of("ACTIVE", "INACTIVE")),
                        any(),
                        any(Instant.class),
                        any(Instant.class)))
                .thenReturn(Map.of("ACTIVE", 60L, "INACTIVE", 40L));

        RatioQueryResult result = service.runQuery(new RatioQueryRequest("payments", "state_distribution", null, null));

        assertEquals(100L, result.total());
        assertEquals(2, result.slices().size());
        assertEquals("ACTIVE", result.slices().get(0).label());
        assertEquals(60L, result.slices().get(0).value());
        assertEquals(60.0d, result.slices().get(0).percent());
        assertEquals(0.6d, result.slices().get(0).ratio());
    }

    @Test
    void returnsEmptyWhenZeroTotalAndConfiguredAsEmpty() {
        UUID serviceId = UUID.randomUUID();
        when(servicesCatalogRepository.findIdByServiceKey("payments")).thenReturn(serviceId);
        when(configLookup.ratioQuery(serviceId, "state_distribution"))
                .thenReturn(java.util.Optional.of(
                        ratioStatesDefinitionWithZeroBehavior(RatioQueryDefinition.ZeroTotalBehavior.EMPTY)));
        when(stateCountTimeseriesQueryRepository.fetchLatestCountsInRange(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        eq(List.of("ACTIVE", "INACTIVE")),
                        any(),
                        any(Instant.class),
                        any(Instant.class)))
                .thenReturn(Map.of());

        RatioQueryResult result = service.runQuery(new RatioQueryRequest("payments", "state_distribution", null, null));

        assertEquals(0, result.total());
        assertEquals(0, result.slices().size());
    }

    @Test
    void failsWhenMissingItemAndConfiguredAsError() {
        UUID serviceId = UUID.randomUUID();
        when(servicesCatalogRepository.findIdByServiceKey("payments")).thenReturn(serviceId);
        when(configLookup.ratioQuery(serviceId, "funnel_outcomes"))
                .thenReturn(java.util.Optional.of(
                        ratioTransitionDefinitionWithMissingBehavior(RatioQueryDefinition.MissingItemBehavior.ERROR)));
        when(stateTransitionQueryRepository.sumTransitions(
                        eq(serviceId), eq("UserProfile"), eq("user.status"), any(), any(), any(), any()))
                .thenReturn(Map.of(new StateTransitionQueryRepository.TransitionKey("NEW", "ACTIVE"), 12L));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.runQuery(new RatioQueryRequest("payments", "funnel_outcomes", null, null)));
        assertTrue(ex.getMessage().contains("Missing ratio query items"));
    }

    @Test
    void mixedSourceDispatchesToStateAndTransitionRepositories() {
        UUID serviceId = UUID.randomUUID();
        when(servicesCatalogRepository.findIdByServiceKey("payments")).thenReturn(serviceId);
        when(configLookup.ratioQuery(serviceId, "mixed_outcomes")).thenReturn(java.util.Optional.of(mixedDefinition()));
        when(stateCountTimeseriesQueryRepository.fetchLatestCountsInRange(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        eq(List.of("ACTIVE")),
                        any(),
                        any(Instant.class),
                        any(Instant.class)))
                .thenReturn(Map.of("ACTIVE", 50L));
        when(stateTransitionQueryRepository.sumTransitions(
                        eq(serviceId), eq("UserProfile"), eq("user.status"), any(), any(), any(), any()))
                .thenReturn(Map.of(new StateTransitionQueryRepository.TransitionKey("NEW", "ABANDONED"), 50L));

        RatioQueryResult result = service.runQuery(new RatioQueryRequest("payments", "mixed_outcomes", null, null));

        assertEquals(100L, result.total());
        assertEquals(2, result.slices().size());
        verify(stateCountTimeseriesQueryRepository)
                .fetchLatestCountsInRange(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        eq(List.of("ACTIVE")),
                        any(),
                        any(Instant.class),
                        any(Instant.class));
        verify(stateTransitionQueryRepository)
                .sumTransitions(eq(serviceId), eq("UserProfile"), eq("user.status"), any(), any(), any(), any());
    }

    @Test
    void latestMinuteTransitionsUsesLatestTimestampWithinRequestedRange() {
        UUID serviceId = UUID.randomUUID();
        when(servicesCatalogRepository.findIdByServiceKey("payments")).thenReturn(serviceId);
        Instant latestInRange = Instant.parse("2026-02-22T20:09:00Z");
        when(stateTransitionQueryRepository.findLatestTimestampInRange(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        any(),
                        any(Instant.class),
                        any(Instant.class)))
                .thenReturn(latestInRange);
        when(stateTransitionQueryRepository.sumTransitions(
                        eq(serviceId), eq("UserProfile"), eq("user.status"), any(), any(), any(), any()))
                .thenReturn(Map.of(
                        new StateTransitionQueryRepository.TransitionKey("NEW", "STANDARD"),
                        92L,
                        new StateTransitionQueryRepository.TransitionKey("NEW", "CANCELLED"),
                        8L));

        RatioQueryResult result = service.runAdHocQuery(new AdHocRatioQueryRequest(
                "payments",
                "conversion_latest",
                "transitions",
                "UserProfile",
                "user.status",
                List.of(
                        new AdHocRatioQueryRequest.Item(null, "NEW->STANDARD", "NEW -> STANDARD"),
                        new AdHocRatioQueryRequest.Item(null, "NEW->CANCELLED", "NEW -> CANCELLED")),
                "2026-02-22T19:00:00Z",
                "2026-02-22T21:00:00Z",
                "count",
                true,
                true,
                true,
                2,
                true,
                "zeros",
                "zero"));

        assertEquals(100L, result.total());
        assertEquals(2, result.slices().size());
        assertEquals("NEW -> CANCELLED", result.slices().get(1).label());
        assertEquals(8.0d, result.slices().get(1).percent());
        verify(stateTransitionQueryRepository)
                .findLatestTimestampInRange(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        any(),
                        any(Instant.class),
                        any(Instant.class));
        verify(stateTransitionQueryRepository)
                .sumTransitions(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        any(),
                        eq(latestInRange),
                        eq(latestInRange.plusSeconds(60)),
                        any());
    }

    @Test
    void statesSourceDoesNotHitTransitionRepo() {
        UUID serviceId = UUID.randomUUID();
        when(servicesCatalogRepository.findIdByServiceKey("payments")).thenReturn(serviceId);
        when(configLookup.ratioQuery(serviceId, "state_distribution"))
                .thenReturn(java.util.Optional.of(ratioStatesDefinition("state_distribution")));
        when(stateCountTimeseriesQueryRepository.fetchLatestCountsInRange(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        eq(List.of("ACTIVE", "INACTIVE")),
                        any(),
                        any(Instant.class),
                        any(Instant.class)))
                .thenReturn(Map.of("ACTIVE", 1L, "INACTIVE", 1L));

        service.runQuery(new RatioQueryRequest("payments", "state_distribution", null, null));

        verify(stateTransitionQueryRepository, never()).sumTransitions(any(), any(), any(), any(), any(), any(), any());
    }

    private RatioQueryDefinition ratioStatesDefinition(String name) {
        return ratioStatesDefinitionWithZeroBehavior(RatioQueryDefinition.ZeroTotalBehavior.ZEROS, name);
    }

    private RatioQueryDefinition ratioStatesDefinitionWithZeroBehavior(RatioQueryDefinition.ZeroTotalBehavior zero) {
        return ratioStatesDefinitionWithZeroBehavior(zero, "state_distribution");
    }

    private RatioQueryDefinition ratioStatesDefinitionWithZeroBehavior(
            RatioQueryDefinition.ZeroTotalBehavior zero, String name) {
        return new RatioQueryDefinition(
                name,
                RatioQueryDefinition.Source.STATES,
                "UserProfile",
                "user.status",
                new RatioQueryDefinition.Window("-15m", "now"),
                List.of(
                        new RatioQueryDefinition.Item("ACTIVE", null, "ACTIVE"),
                        new RatioQueryDefinition.Item("INACTIVE", null, "INACTIVE")),
                new RatioQueryDefinition.Output(
                        RatioQueryDefinition.OutputFormat.GRAFANA_PIE,
                        RatioQueryDefinition.ValueMode.COUNT,
                        true,
                        true,
                        true,
                        2),
                new RatioQueryDefinition.Behavior(zero, RatioQueryDefinition.MissingItemBehavior.ZERO));
    }

    private RatioQueryDefinition ratioTransitionDefinitionWithMissingBehavior(
            RatioQueryDefinition.MissingItemBehavior missingItemBehavior) {
        return new RatioQueryDefinition(
                "funnel_outcomes",
                RatioQueryDefinition.Source.TRANSITIONS,
                "UserProfile",
                "user.status",
                new RatioQueryDefinition.Window("-15m", "now"),
                List.of(
                        new RatioQueryDefinition.Item(null, "NEW->ACTIVE", "Success"),
                        new RatioQueryDefinition.Item(null, "NEW->FAILED", "Failed")),
                new RatioQueryDefinition.Output(
                        RatioQueryDefinition.OutputFormat.GRAFANA_PIE,
                        RatioQueryDefinition.ValueMode.COUNT,
                        true,
                        true,
                        true,
                        2),
                new RatioQueryDefinition.Behavior(RatioQueryDefinition.ZeroTotalBehavior.ZEROS, missingItemBehavior));
    }

    private RatioQueryDefinition mixedDefinition() {
        return new RatioQueryDefinition(
                "mixed_outcomes",
                RatioQueryDefinition.Source.MIXED,
                "UserProfile",
                "user.status",
                new RatioQueryDefinition.Window("-15m", "now"),
                List.of(
                        new RatioQueryDefinition.Item("ACTIVE", null, "Active"),
                        new RatioQueryDefinition.Item(null, "NEW->ABANDONED", "Abandoned")),
                new RatioQueryDefinition.Output(
                        RatioQueryDefinition.OutputFormat.GRAFANA_PIE,
                        RatioQueryDefinition.ValueMode.COUNT,
                        true,
                        true,
                        true,
                        2),
                new RatioQueryDefinition.Behavior(
                        RatioQueryDefinition.ZeroTotalBehavior.ZEROS, RatioQueryDefinition.MissingItemBehavior.ZERO));
    }
}
