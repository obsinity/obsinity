package com.obsinity.service.storage.impl;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PartitionMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceService.class);
    private static final Pattern SHORT_KEY_RE = Pattern.compile("^[0-9a-f]{8}$");

    private static final WeekFields ISO = WeekFields.ISO; // Monday start, ISO week-based-year

    private final JdbcTemplate jdbc;

    public PartitionMaintenanceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs at application startup to pre-create partitions. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Ensuring partitions at startup...");
        ensurePartitions();
    }

    /**
     * Ensure partitions exist for 1 year back and 1 year forward
     * for every registered service (by short_key).
     * Runs daily at 01:00 UTC.
     */
    @Scheduled(cron = "0 0 1 * * *", zone = "UTC")
    public void ensurePartitions() {
        List<String> shortKeys = jdbc.query("select short_key from services", (rs, rowNum) -> rs.getString(1));

        // Use UTC "today" to be deterministic
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);

        // Window: ±365 days, then align to ISO week boundaries so we iterate without overlaps
        LocalDate rawStart = todayUtc.minusDays(365);
        LocalDate rawEnd = todayUtc.plusDays(365);

        LocalDate start = alignToIsoWeekStart(rawStart); // Monday 00:00
        LocalDate end = alignToIsoWeekStart(rawEnd); // iterate up to but excluding this

        for (String shortKey : shortKeys) {
            if (shortKey == null || !SHORT_KEY_RE.matcher(shortKey).matches()) {
                log.warn("Skipping invalid service short_key: {}", shortKey);
                continue;
            }

            ensureServiceRoot(shortKey); // Level 1: LIST child that is RANGE(occurred_at)

            for (LocalDate wk = start; wk.isBefore(end); wk = wk.plusWeeks(1)) {
                ensureWeeklyPartition(shortKey, wk); // Level 2: weekly range child (wk is week-start)
            }
        }
    }

    /** Level 1: create events_raw_<shortKey> as a LIST child that is further partitioned by RANGE(occurred_at). */
    private void ensureServiceRoot(String shortKey) {
        String rootName = "events_raw_" + shortKey;

        String sql = String.format(
                "CREATE TABLE IF NOT EXISTS %s "
                        + "PARTITION OF events_raw FOR VALUES IN ('%s') "
                        + "PARTITION BY RANGE (occurred_at)",
                rootName, shortKey);

        if (log.isDebugEnabled()) log.debug("Ensuring service root partition: {}", rootName);
        jdbc.execute(sql);
    }

    /** Level 2: create weekly partition under events_raw_<shortKey>. `weekStart` MUST be an ISO week start (Monday). */
    private void ensureWeeklyPartition(String shortKey, LocalDate weekStart) {
        String rootName = "events_raw_" + shortKey;

        // ISO week and ISO week-based year for naming to match the actual range
        int isoWeek = weekStart.get(ISO.weekOfWeekBasedYear());
        int isoYear = weekStart.get(ISO.weekBasedYear());

        LocalDate weekEnd = weekStart.plusWeeks(1); // TO is exclusive

        String partName = String.format("%s_y%04d_w%02d", rootName, isoYear, isoWeek);

        // Use explicit TIMESTAMPTZ literals in UTC at midnight; bounds are [FROM inclusive, TO exclusive]
        String create = String.format(
                "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s "
                        + "FOR VALUES FROM (TIMESTAMPTZ '%s 00:00:00+00') "
                        + "TO   (TIMESTAMPTZ '%s 00:00:00+00')",
                partName, rootName, weekStart, weekEnd);

        if (log.isDebugEnabled()) {
            log.debug("Ensuring weekly partition: {} (FROM {} TO {})", partName, weekStart, weekEnd);
        }
        jdbc.execute(create);

        // Per-child indexes (service_short fixed by parent; focus on occurred_at and type)
        jdbc.execute(
                String.format("CREATE INDEX IF NOT EXISTS %s_occurred_at ON %s (occurred_at)", partName, partName));
        jdbc.execute(String.format(
                "CREATE INDEX IF NOT EXISTS %s_type_occurred_at ON %s (event_type, occurred_at)", partName, partName));
    }

    /** Align any LocalDate to ISO week start (Monday). */
    private static LocalDate alignToIsoWeekStart(LocalDate date) {
        // WeekFields.ISO uses Monday as first day of week
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
