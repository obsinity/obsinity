package com.obsinity.service.core.state.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

        assertEquals(60, result.windows().size());
        assertEquals("2026-02-18T16:00:00Z", result.windows().get(0).start());
        assertEquals("ACTIVE", result.windows().get(0).states().get(0).state());
        assertEquals(0L, result.windows().get(0).states().get(0).count());
    }

    @Test
    void fillsMissingIntervalsUsingLastKnownCounts() {
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

        when(repository.fetchRowsInRange(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        isNull(),
                        eq(CounterBucket.M1),
                        any(Instant.class),
                        any(Instant.class)))
                .thenAnswer(invocation -> {
                    Instant start = invocation.getArgument(5, Instant.class);
                    if (start.equals(Instant.parse("2026-02-18T16:02:00Z"))) {
                        return List.of(new StateCountTimeseriesQueryRepository.Row(start, "ACTIVE", 101L));
                    }
                    if (start.equals(Instant.parse("2026-02-18T16:05:00Z"))) {
                        return List.of(new StateCountTimeseriesQueryRepository.Row(start, "ACTIVE", 105L));
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

        assertEquals(6, result.windows().size());
        assertEquals("2026-02-18T16:00:00Z", result.windows().get(0).start());
        assertEquals(0L, result.windows().get(0).states().get(0).count());
        assertEquals("2026-02-18T16:01:00Z", result.windows().get(1).start());
        assertEquals(0L, result.windows().get(1).states().get(0).count());
        assertEquals("2026-02-18T16:02:00Z", result.windows().get(2).start());
        assertEquals(101L, result.windows().get(2).states().get(0).count());
        assertEquals("2026-02-18T16:03:00Z", result.windows().get(3).start());
        assertEquals(101L, result.windows().get(3).states().get(0).count());
        assertEquals("2026-02-18T16:04:00Z", result.windows().get(4).start());
        assertEquals(101L, result.windows().get(4).states().get(0).count());
        assertEquals("2026-02-18T16:05:00Z", result.windows().get(5).start());
        assertEquals(105L, result.windows().get(5).states().get(0).count());

        verify(repository, Mockito.times(6))
                .fetchRowsInRange(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        isNull(),
                        eq(CounterBucket.M1),
                        any(Instant.class),
                        any(Instant.class));
    }

    @Test
    void returnsZeroForRequestedStateMissingFromSnapshotWindow() {
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
                        eq(List.of("ACTIVE", "SUSPENDED")),
                        eq(CounterBucket.M1),
                        eq(Instant.parse("2026-02-18T16:00:00Z")),
                        eq(Instant.parse("2026-02-18T16:03:00Z"))))
                .thenReturn(Instant.parse("2026-02-18T16:00:00Z"));

        when(repository.fetchRowsInRange(
                        eq(serviceId),
                        eq("UserProfile"),
                        eq("user.status"),
                        isNull(),
                        eq(CounterBucket.M1),
                        any(Instant.class),
                        any(Instant.class)))
                .thenAnswer(invocation -> {
                    Instant start = invocation.getArgument(5, Instant.class);
                    if (start.equals(Instant.parse("2026-02-18T16:00:00Z"))) {
                        return List.of(
                                new StateCountTimeseriesQueryRepository.Row(start, "ACTIVE", 10L),
                                new StateCountTimeseriesQueryRepository.Row(start, "SUSPENDED", 4L));
                    }
                    if (start.equals(Instant.parse("2026-02-18T16:01:00Z"))) {
                        return List.of(new StateCountTimeseriesQueryRepository.Row(start, "ACTIVE", 12L));
                    }
                    return List.of();
                });

        StateCountTimeseriesQueryResult result = service.runQuery(new StateCountTimeseriesQueryRequest(
                "payments",
                "UserProfile",
                "user.status",
                List.of("ACTIVE", "SUSPENDED"),
                "1m",
                "2026-02-18T16:00:00Z",
                "2026-02-18T16:03:00Z",
                null,
                null));

        assertEquals(3, result.windows().size());
        assertEquals(4L, result.windows().get(0).states().get(1).count());
        assertEquals(0L, result.windows().get(1).states().get(1).count());
        assertEquals(0L, result.windows().get(2).states().get(1).count());
    }
}
