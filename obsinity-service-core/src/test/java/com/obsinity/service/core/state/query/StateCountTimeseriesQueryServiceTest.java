package com.obsinity.service.core.state.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

class StateCountTimeseriesQueryServiceTest {

    @Mock
    private ServicesCatalogRepository servicesCatalogRepository;

    @Mock
    private StateCountTimeseriesQueryRepository repository;

    private StateCountTimeseriesQueryService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new StateCountTimeseriesQueryService(servicesCatalogRepository, repository);
    }

    @Test
    void returnsEmptyWhenNoSnapshotExistsInRequestedRange() {
        UUID serviceId = UUID.randomUUID();
        when(servicesCatalogRepository.findIdByServiceKey("payments")).thenReturn(serviceId);
        when(repository.findEarliestTimestamp(serviceId, "UserProfile", "user.status", CounterBucket.M1))
                .thenReturn(Instant.parse("2026-02-18T15:00:00Z"));
        when(repository.findLatestTimestamp(serviceId, "UserProfile", "user.status", CounterBucket.M1))
                .thenReturn(Instant.parse("2026-02-18T20:00:00Z"));
        when(repository.findEarliestTimestampInRange(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        eq(List.of("ACTIVE")),
                        eq(CounterBucket.M1),
                        eq(Instant.parse("2026-02-18T16:00:00Z")),
                        eq(Instant.parse("2026-02-18T17:00:00Z"))))
                .thenReturn(null);

        StateCountTimeseriesQueryResult result = service.runQuery(new StateCountTimeseriesQueryRequest(
                "payments",
                "UserProfile",
                "user.status",
                List.of("ACTIVE"),
                "1m",
                "2026-02-18T16:00:00Z",
                "2026-02-18T17:00:00Z",
                null,
                null));

        assertTrue(result.windows().isEmpty());
        verify(repository, never())
                .fetchWindow(any(UUID.class), any(String.class), any(String.class), any(), any(), any());
    }

    @Test
    void skipsMissingIntervalsInsteadOfReturningSyntheticZeroWindows() {
        UUID serviceId = UUID.randomUUID();
        when(servicesCatalogRepository.findIdByServiceKey("payments")).thenReturn(serviceId);
        when(repository.findEarliestTimestamp(serviceId, "UserProfile", "user.status", CounterBucket.M1))
                .thenReturn(Instant.parse("2026-02-18T15:00:00Z"));
        when(repository.findLatestTimestamp(serviceId, "UserProfile", "user.status", CounterBucket.M1))
                .thenReturn(Instant.parse("2026-02-18T20:00:00Z"));
        when(repository.findEarliestTimestampInRange(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        eq(List.of("ACTIVE")),
                        eq(CounterBucket.M1),
                        eq(Instant.parse("2026-02-18T16:00:00Z")),
                        eq(Instant.parse("2026-02-18T16:06:00Z"))))
                .thenReturn(Instant.parse("2026-02-18T16:02:00Z"));

        when(repository.fetchWindow(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        eq(List.of("ACTIVE")),
                        eq(CounterBucket.M1),
                        any(Instant.class)))
                .thenAnswer(invocation -> {
                    Instant ts = invocation.getArgument(5, Instant.class);
                    if (ts.equals(Instant.parse("2026-02-18T16:02:00Z"))) {
                        return List.of(new StateCountTimeseriesQueryRepository.Row("ACTIVE", 101L));
                    }
                    if (ts.equals(Instant.parse("2026-02-18T16:05:00Z"))) {
                        return List.of(new StateCountTimeseriesQueryRepository.Row("ACTIVE", 105L));
                    }
                    return List.of();
                });

        StateCountTimeseriesQueryResult result = service.runQuery(new StateCountTimeseriesQueryRequest(
                "payments",
                "UserProfile",
                "user.status",
                List.of("ACTIVE"),
                "1m",
                "2026-02-18T16:00:00Z",
                "2026-02-18T16:06:00Z",
                null,
                null));

        assertEquals(2, result.windows().size());
        assertEquals("2026-02-18T16:02:00Z", result.windows().get(0).start());
        assertEquals(101L, result.windows().get(0).states().get(0).count());
        assertEquals("2026-02-18T16:05:00Z", result.windows().get(1).start());
        assertEquals(105L, result.windows().get(1).states().get(0).count());

        verify(repository, Mockito.times(4))
                .fetchWindow(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        eq(List.of("ACTIVE")),
                        eq(CounterBucket.M1),
                        any(Instant.class));
    }
}
