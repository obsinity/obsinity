package com.obsinity.service.storage.impl;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;
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

        LocalDate start = LocalDate.now().minusDays(365);
        LocalDate end = LocalDate.now().plusDays(365);

        for (String shortKey : shortKeys) {
            if (shortKey == null || !SHORT_KEY_RE.matcher(shortKey).matches()) {
                log.warn("Skipping invalid service short_key: {}", shortKey);
                continue;
            }

            ensureServiceRoot(shortKey); // Level 1: LIST child that is RANGE(ts)

            LocalDate cursor = start;
            while (cursor.isBefore(end)) {
                ensureWeeklyPartition(shortKey, cursor); // Level 2: weekly range child
                cursor = cursor.plusWeeks(1);
            }
        }
    }

    /** Level 1: create events_raw_<shortKey> as a LIST child that is further partitioned by RANGE(ts). */
    private void ensureServiceRoot(String shortKey) {
        String rootName = "events_raw_" + shortKey;

        String sql = String.format(
                "CREATE TABLE IF NOT EXISTS %s "
                        + "PARTITION OF events_raw FOR VALUES IN ('%s') "
                        + "PARTITION BY RANGE (ts)",
                rootName, shortKey);

        log.debug("Ensuring service root partition: {}", rootName);
        jdbc.execute(sql);
    }

    /** Level 2: create weekly partition under events_raw_<shortKey>. */
    private void ensureWeeklyPartition(String shortKey, LocalDate startDate) {
        String rootName = "events_raw_" + shortKey;

        int week = startDate.get(WeekFields.of(Locale.ROOT).weekOfWeekBasedYear());
        int year = startDate.getYear();
        LocalDate endDate = startDate.plusWeeks(1);

        String partName = String.format("%s_y%04d_w%02d", rootName, year, week);

        String create = String.format(
                "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s "
                        + "FOR VALUES FROM (TIMESTAMPTZ '%s 00:00:00+00') "
                        + "TO   (TIMESTAMPTZ '%s 00:00:00+00')",
                partName, rootName, startDate, endDate);

        log.debug("Ensuring weekly partition: {}", partName);
        jdbc.execute(create);

        // Per-child indexes (service_short fixed by parent; focus on ts and type)
        jdbc.execute(String.format("CREATE INDEX IF NOT EXISTS %s_ts ON %s (ts)", partName, partName));
        jdbc.execute(String.format("CREATE INDEX IF NOT EXISTS %s_type_ts ON %s (type, ts)", partName, partName));
    }
}
