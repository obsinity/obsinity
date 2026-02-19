package com.obsinity.service.core.repo;

import com.obsinity.service.core.counter.CounterBucket;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StateCountTimeseriesPartitionManager {

    private static final Duration WEEK_DURATION = Duration.ofDays(7);
    private static final DateTimeFormatter PARTITION_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Set<CounterBucket> SUPPORTED_BUCKETS = Set.of(CounterBucket.M1);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public StateCountTimeseriesPartitionManager(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ensureCoverage();
    }

    @Scheduled(cron = "${obsinity.stateCounts.timeseries.partitionMaintenanceCron:0 0 * * * *}")
    public void scheduledMaintenance() {
        ensureCoverage();
    }

    public void ensureCoverage() {
        Instant now = Instant.now(clock);
        for (CounterBucket bucket : SUPPORTED_BUCKETS) {
            ensureCoverage(bucket, now);
        }
    }

    private void ensureCoverage(CounterBucket bucket, Instant now) {
        String parentTable = "object_state_count_timeseries_" + bucket.label().toLowerCase();
        List<Range> ranges = findRanges(parentTable);
        Range current = coveringRange(ranges, now);

        if (current == null) {
            Instant start = findLatestUpperBoundAtOrBefore(ranges, now);
            if (start == null) {
                start = weekStartUtc(now).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            while (current == null) {
                Instant end = nextPartitionBoundary(start);
                createPartition(parentTable, bucket, start, end);
                start = end;
                ranges = findRanges(parentTable);
                current = coveringRange(ranges, now);
            }
        }

        Instant cursor = current.to();
        int futureCovered = 0;
        while (futureCovered < 4) {
            ranges = findRanges(parentTable);
            Range next = rangeAtCursor(ranges, cursor);
            if (next == null) {
                Instant end = nextPartitionBoundary(cursor);
                createPartition(parentTable, bucket, cursor, end);
                ranges = findRanges(parentTable);
                next = rangeAtCursor(ranges, cursor);
                if (next == null) {
                    cursor = end;
                    futureCovered++;
                    continue;
                }
            }
            cursor = next.to();
            futureCovered++;
        }
    }

    private List<Range> findRanges(String parentTable) {
        String sql =
                """
                SELECT
                  (regexp_match(pg_get_expr(c.relpartbound, c.oid, true), $$FROM \\('([^']+)'\\)$$))[1]::timestamptz AS range_from,
                  (regexp_match(pg_get_expr(c.relpartbound, c.oid, true), $$TO \\('([^']+)'\\)$$))[1]::timestamptz AS range_to
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                JOIN pg_namespace n ON n.oid = p.relnamespace
                WHERE n.nspname = 'obsinity'
                  AND p.relname = ?
                """;
        List<Range> ranges = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    Timestamp from = rs.getTimestamp("range_from");
                    Timestamp to = rs.getTimestamp("range_to");
                    if (from == null || to == null) {
                        return null;
                    }
                    return new Range(from.toInstant(), to.toInstant());
                },
                parentTable);
        List<Range> clean = new ArrayList<>();
        for (Range range : ranges) {
            if (range != null) {
                clean.add(range);
            }
        }
        clean.sort(Comparator.comparing(Range::from));
        return clean;
    }

    private Range coveringRange(List<Range> ranges, Instant ts) {
        for (Range range : ranges) {
            if (!ts.isBefore(range.from()) && ts.isBefore(range.to())) {
                return range;
            }
        }
        return null;
    }

    private Instant findLatestUpperBoundAtOrBefore(List<Range> ranges, Instant ts) {
        Instant latest = null;
        for (Range range : ranges) {
            if (!range.to().isAfter(ts) && (latest == null || range.to().isAfter(latest))) {
                latest = range.to();
            }
        }
        return latest;
    }

    private Range rangeAtCursor(List<Range> ranges, Instant cursor) {
        for (Range range : ranges) {
            if (!cursor.isBefore(range.from()) && cursor.isBefore(range.to())) {
                return range;
            }
            if (range.from().equals(cursor)) {
                return range;
            }
        }
        return null;
    }

    private void createPartition(String parentTable, CounterBucket bucket, Instant start, Instant end) {
        String partitionName = partitionName(bucket, start);
        try {
            jdbcTemplate.execute(String.format(
                    """
                    CREATE TABLE IF NOT EXISTS obsinity.%s
                    PARTITION OF obsinity.%s
                    FOR VALUES FROM (TIMESTAMPTZ '%s') TO (TIMESTAMPTZ '%s')
                    """,
                    partitionName, parentTable, start.toString(), end.toString()));
            jdbcTemplate.execute(String.format(
                    "CREATE INDEX IF NOT EXISTS %s ON obsinity.%s(service_id, object_type, attribute)",
                    partitionName + "_service_idx", partitionName));
        } catch (DataAccessException ex) {
            if (isOverlapError(ex)) {
                return;
            }
            throw ex;
        }
    }

    private String partitionName(CounterBucket bucket, Instant start) {
        String ts = PARTITION_TS.format(start.atZone(ZoneOffset.UTC));
        return "object_state_count_timeseries_" + ts + "_" + bucket.label().toLowerCase();
    }

    private LocalDate weekStartUtc(Instant timestamp) {
        return timestamp.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private Instant nextPartitionBoundary(Instant start) {
        if (isUtcMondayMidnight(start)) {
            return start.plus(WEEK_DURATION);
        }
        return nextUtcMonday(start);
    }

    private boolean isUtcMondayMidnight(Instant instant) {
        ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
        return zdt.getDayOfWeek() == DayOfWeek.MONDAY
                && zdt.getHour() == 0
                && zdt.getMinute() == 0
                && zdt.getSecond() == 0
                && zdt.getNano() == 0;
    }

    private Instant nextUtcMonday(Instant instant) {
        ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
        ZonedDateTime monday =
                zdt.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(ZoneOffset.UTC);
        return monday.toInstant();
    }

    private boolean isOverlapError(DataAccessException ex) {
        String message = ex.getMessage();
        return message != null && message.contains("would overlap partition");
    }

    private record Range(Instant from, Instant to) {}
}
