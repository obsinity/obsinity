package com.obsinity.service.storage.impl;

import java.time.*;
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

/**
 * Maintains partition trees for:
 *  - events_raw                PARTITION BY LIST(service_partition_key) -> RANGE(started_at weekly)
 *  - event_attr_index          PARTITION BY LIST(service_partition_key) -> RANGE(started_at weekly)
 *
 * Rolling window: create weekly partitions from N weeks back to M weeks ahead.
 */
@Service
public class PartitionMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceService.class);
    private static final Pattern PARTITION_KEY_RE = Pattern.compile("^[0-9a-f]{16}$");

    private final JdbcTemplate jdbc;

    // Tune your window here
    private final int weeksBack = 52;
    private final int weeksAhead = 26;

    public PartitionMaintenanceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Ensuring partitions at startup...");
        ensurePartitions();
    }

    // e.g. run daily at 02:15
    @Scheduled(cron = "0 15 2 * * *")
    public void scheduled() {
        log.info("Ensuring partitions on schedule...");
        ensurePartitions();
    }

    public void ensurePartitions() {
        List<String> servicePartitionKeys = fetchAllServicePartitionKeys();
        if (servicePartitionKeys.isEmpty()) {
            log.info("No services found yet; skipping partition creation.");
            return;
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        WeekFields wf = WeekFields.of(Locale.UK); // ISO weeks; adapt if needed
        LocalDate start = today.minusWeeks(weeksBack).with(wf.dayOfWeek(), 1); // Monday (or your week start)
        LocalDate end = today.plusWeeks(weeksAhead).with(wf.dayOfWeek(), 1);

        for (String partitionKey : servicePartitionKeys) {
            if (!PARTITION_KEY_RE.matcher(partitionKey).matches()) {
                log.warn("Skipping non-partition key value: {}", partitionKey);
                continue;
            }

            // Ensure LIST partitions exist in both parents
            ensureServiceListPartition("events_raw", partitionKey);
            ensureServiceListPartition("event_attr_index", partitionKey);

            // Then weekly RANGE partitions under each LIST child
            LocalDate cursor = start;
            while (!cursor.isAfter(end)) {
                LocalDate next = cursor.plusWeeks(1);
                ensureWeeklyRangePartition("events_raw", partitionKey, cursor, next);
                ensureWeeklyRangePartition("event_attr_index", partitionKey, cursor, next);

                // For the attr index, ensure helpful local indexes on each weekly child
                ensureAttrIndexChildIndexes(partitionKey, weekName(cursor), "event_attr_index");
                cursor = next;
            }
        }
    }

    private List<String> fetchAllServicePartitionKeys() {
        // Source of truth: the services table (whatever you already upsert)
        return jdbc.queryForList("SELECT service_partition_key FROM service_registry", String.class);
    }

    private void ensureServiceListPartition(String parentTable, String servicePartitionKey) {
        String childTable = parentTable + "_s_" + servicePartitionKey;
        jdbc.execute(
                """
            DO $$
            BEGIN
              IF NOT EXISTS (
                SELECT 1 FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = %s
              ) THEN
                BEGIN
                  EXECUTE format(
                    'CREATE TABLE %%I PARTITION OF %%I FOR VALUES IN (%%L) PARTITION BY RANGE (started_at)',
                    %s, %s, %s
                  );
                EXCEPTION
                  WHEN duplicate_table THEN NULL;
                  WHEN duplicate_object THEN NULL;
                END;
              END IF;
            END
            $$;
            """
                        .formatted(
                                literal(childTable),
                                // params for format: child, parent, 'service_partition_key'
                                literal(childTable),
                                literal(parentTable),
                                literal(servicePartitionKey)));
    }

    private void ensureWeeklyRangePartition(
            String parentTable, String servicePartitionKey, LocalDate fromIncl, LocalDate toExcl) {
        String week = weekName(fromIncl);
        String listChild = parentTable + "_s_" + servicePartitionKey;
        String rangeChild = listChild + "_w_" + week;

        String fromTs = fromIncl.atStartOfDay().toString(); // UTC assumed for simplicity
        String toTs = toExcl.atStartOfDay().toString();

        jdbc.execute(
                """
            DO $$
            BEGIN
              IF NOT EXISTS (
                SELECT 1 FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = %s
              ) THEN
                BEGIN
                  EXECUTE format(
                    'CREATE TABLE %%I PARTITION OF %%I FOR VALUES FROM (%%L) TO (%%L)',
                    %s, %s, %s, %s
                  );
                EXCEPTION
                  WHEN duplicate_table THEN NULL;
                  WHEN duplicate_object THEN NULL;
                END;
              END IF;
            END
            $$;
            """
                        .formatted(
                                literal(rangeChild),
                                // format params: rangeChild, listChild, from, to
                                literal(rangeChild),
                                literal(listChild),
                                literal(fromTs),
                                literal(toTs)));
    }

    private void ensureAttrIndexChildIndexes(String servicePartitionKey, String week, String baseParent) {
        String child = baseParent + "_s_" + servicePartitionKey + "_w_" + week;

        // attr_name + attr_value composite
        jdbc.execute(
                """
            DO $$
            BEGIN
              IF NOT EXISTS (
                SELECT 1 FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = %s
              ) THEN
                BEGIN
                  EXECUTE format('CREATE INDEX %%I ON %%I(attr_name, attr_value)', %s, %s);
                EXCEPTION
                  WHEN duplicate_table THEN NULL;
                  WHEN duplicate_object THEN NULL;
                END;
              END IF;
            END
            $$;
            """
                        .formatted(
                                literal("eai_attr_name_val_" + servicePartitionKey + "_" + week),
                                literal("eai_attr_name_val_" + servicePartitionKey + "_" + week),
                                literal(child)));

        // started_at desc
        jdbc.execute(
                """
            DO $$
            BEGIN
              IF NOT EXISTS (
                SELECT 1 FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = %s
              ) THEN
                BEGIN
                  EXECUTE format('CREATE INDEX %%I ON %%I(started_at DESC)', %s, %s);
                EXCEPTION
                  WHEN duplicate_table THEN NULL;
                  WHEN duplicate_object THEN NULL;
                END;
              END IF;
            END
            $$;
            """
                        .formatted(
                                literal("eai_time_desc_" + servicePartitionKey + "_" + week),
                                literal("eai_time_desc_" + servicePartitionKey + "_" + week),
                                literal(child)));

        // service_id + event_type_id (handy for resolving types quickly)
        jdbc.execute(
                """
            DO $$
            BEGIN
              IF NOT EXISTS (
                SELECT 1 FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = %s
              ) THEN
                BEGIN
                  EXECUTE format('CREATE INDEX %%I ON %%I(service_id, event_type_id)', %s, %s);
                EXCEPTION
                  WHEN duplicate_table THEN NULL;
                  WHEN duplicate_object THEN NULL;
                END;
              END IF;
            END
            $$;
            """
                        .formatted(
                                literal("eai_svc_evt_" + servicePartitionKey + "_" + week),
                                literal("eai_svc_evt_" + servicePartitionKey + "_" + week),
                                literal(child)));
    }

    private static String weekName(LocalDate date) {
        WeekFields wf = WeekFields.of(Locale.UK);
        int week = date.get(wf.weekOfWeekBasedYear());
        return "%d_%02d".formatted(date.getYear(), week);
    }

    private static String quote(String ident) {
        // minimal identifier sanitization for embedding into SQL string literals
        return ident.replace("\"", "\"\"");
    }

    private static String literal(String s) {
        // single-quote and escape for safe SQL literal usage
        if (s == null) return "NULL";
        return "'" + s.replace("'", "''") + "'";
    }
}
