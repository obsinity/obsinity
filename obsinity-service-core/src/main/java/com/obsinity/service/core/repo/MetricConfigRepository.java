package com.obsinity.service.core.repo;

import com.obsinity.service.core.entities.MetricConfigEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MetricConfigRepository extends JpaRepository<MetricConfigEntity, UUID> {

    @Modifying
    @Query(
            value =
                    """
        INSERT INTO metric_registry (
          id, event_id, name, type, spec_json, spec_hash,
          keyed_keys, rollups, bucket_layout_hash, filters_json,
          backfill_window, cutover_at, grace_until, state,
          metric_key
        ) VALUES (
          :id, :eventId, :name, :type, cast(:specJson as jsonb), :specHash,
          cast(:keyedKeys as text[]), cast(:rollups as text[]), :bucketLayoutHash, cast(:filtersJson as jsonb),
          cast(:backfillWindow as interval), :cutoverAt, :graceUntil, :state,
          :metricKey
        )
        ON CONFLICT (event_id, metric_key) DO UPDATE SET
          name = EXCLUDED.name,
          type = EXCLUDED.type,
          spec_json = EXCLUDED.spec_json,
          spec_hash = EXCLUDED.spec_hash,
          keyed_keys = EXCLUDED.keyed_keys,
          rollups = EXCLUDED.rollups,
          bucket_layout_hash = EXCLUDED.bucket_layout_hash,
          filters_json = EXCLUDED.filters_json,
          backfill_window = EXCLUDED.backfill_window,
          cutover_at = EXCLUDED.cutover_at,
          grace_until = EXCLUDED.grace_until,
          state = EXCLUDED.state,
          updated_at = now()
        """,
            nativeQuery = true)
    int upsertMetric(
            @Param("id") UUID id,
            @Param("eventId") UUID eventId,
            @Param("name") String name,
            @Param("type") String type,
            @Param("specJson") String specJson,
            @Param("specHash") String specHash,
            @Param("keyedKeys") String keyedKeysArrayLiteral,
            @Param("rollups") String rollupsArrayLiteral,
            @Param("bucketLayoutHash") String bucketLayoutHash,
            @Param("filtersJson") String filtersJson,
            @Param("backfillWindow") String backfillWindow,
            @Param("cutoverAt") Instant cutoverAt,
            @Param("graceUntil") Instant graceUntil,
            @Param("state") String state,
            @Param("metricKey") String metricKey);
}
