-- =========================
-- Baseline schema for Obsinity service core
-- =========================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- ===========================================
-- Canonical raw inbox aligned to EventEnvelope
-- ===========================================
CREATE SCHEMA IF NOT EXISTS obsinity;

CREATE TABLE IF NOT EXISTS events_raw (
  event_id         UUID NOT NULL,
  event_type_id    UUID NOT NULL,
  parent_event_id  UUID,

  service_partition_key TEXT        NOT NULL,
  event_type       TEXT        NOT NULL,
  kind             TEXT,
  attributes       JSONB       NOT NULL DEFAULT '{}'::jsonb,
  started_at       TIMESTAMPTZ NOT NULL,
  completed_at     TIMESTAMPTZ,
  duration_nanos   BIGINT,
  received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

  trace_id         TEXT,
  span_id          TEXT,
  parent_span_id   TEXT,
  correlation_id   TEXT,
  status           TEXT,

  PRIMARY KEY (service_partition_key, started_at, event_id)
)
PARTITION BY LIST (service_partition_key);

CREATE TABLE IF NOT EXISTS events_raw_default
  PARTITION OF events_raw DEFAULT;

-- ==============================================================
-- DATA index (per-event attribute rows) — PARTITIONED TABLE
-- ==============================================================
CREATE TABLE IF NOT EXISTS event_attr_index (
  service_partition_key  TEXT        NOT NULL,
  started_at    TIMESTAMPTZ NOT NULL,
  service_id     UUID        NOT NULL,
  event_type_id  UUID        NOT NULL,
  event_id       UUID        NOT NULL,
  attr_name      TEXT        NOT NULL,
  attr_value     TEXT        NOT NULL,
  PRIMARY KEY (service_partition_key, started_at, event_id, attr_name, attr_value)
)
PARTITION BY LIST (service_partition_key);

CREATE TABLE IF NOT EXISTS event_attr_index_default
  PARTITION OF event_attr_index DEFAULT;

-- ==============================================================
-- Unconfigured event storage for rejected events
-- ==============================================================
CREATE TABLE IF NOT EXISTS event_unconfigured_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  service_key TEXT,
  event_type  TEXT,
  event_id    TEXT,
  reason      TEXT        NOT NULL,
  error       TEXT,
  payload     JSONB       NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_event_unconfigured_events_service
  ON event_unconfigured_events(service_key);

-- ==============================================================
-- Dead-letter storage for payloads that cannot be parsed
-- ==============================================================
CREATE TABLE IF NOT EXISTS event_ingest_dead_letters (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source TEXT,
  reason TEXT NOT NULL,
  error TEXT,
  payload TEXT NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_event_ingest_dead_letters_received
  ON event_ingest_dead_letters(received_at);

-- ================================================
-- Attribute distinct values (per service + path)
-- ================================================
CREATE TABLE IF NOT EXISTS attribute_distinct_values (
  service_partition_key  TEXT        NOT NULL,
  attr_name      TEXT        NOT NULL,
  attr_value     TEXT        NOT NULL,
  first_seen     TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen      TIMESTAMPTZ NOT NULL DEFAULT now(),
  seen_count     BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (service_partition_key, attr_name, attr_value)
);

CREATE INDEX IF NOT EXISTS ix_attr_distinct_service_attr
  ON attribute_distinct_values(service_partition_key, attr_name);

-- ================================================
-- Services Catalog
-- ================================================
CREATE TABLE IF NOT EXISTS service_registry (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  service_key          TEXT NOT NULL UNIQUE,
  service_partition_key TEXT NOT NULL UNIQUE,
  description          TEXT,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ================================================
-- Counter materialisation tables (multi-bucket)
-- ================================================
CREATE TABLE IF NOT EXISTS obsinity.event_counts (
  ts                TIMESTAMPTZ NOT NULL,
  bucket            VARCHAR(10) NOT NULL,
  counter_config_id UUID        NOT NULL,
  event_type_id     UUID        NOT NULL,
  key_hash          CHAR(64)    NOT NULL,
  key_data          JSONB       NOT NULL,
  counter           BIGINT      NOT NULL,
  PRIMARY KEY (ts, bucket, counter_config_id, key_hash)
)
PARTITION BY LIST (bucket);

DO $$
DECLARE
    bucket_name TEXT;
    parent_table TEXT;
BEGIN
    FOR bucket_name IN SELECT unnested FROM unnest(ARRAY['S5','M1','M5','H1','D1','D7']) AS unnested LOOP
        parent_table := format('event_counts_%s', lower(bucket_name));
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS obsinity.%I
             PARTITION OF obsinity.event_counts
             FOR VALUES IN (''%s'')
             PARTITION BY RANGE (ts);',
            parent_table,
            bucket_name
        );
    END LOOP;
END $$;

-- ================================================
-- Histogram materialisation tables (multi-bucket)
-- ================================================
CREATE TABLE IF NOT EXISTS obsinity.event_histograms (
  ts                TIMESTAMPTZ NOT NULL,
  bucket            VARCHAR(10) NOT NULL,
  histogram_config_id UUID      NOT NULL,
  event_type_id     UUID        NOT NULL,
  key_hash          CHAR(64)    NOT NULL,
  key_data          JSONB       NOT NULL,
  sketch_cfg        JSONB       NOT NULL,
  sketch_payload    BYTEA       NOT NULL,
  sample_count      BIGINT      NOT NULL,
  sample_sum        DOUBLE PRECISION NOT NULL,
  PRIMARY KEY (ts, bucket, histogram_config_id, key_hash)
)
PARTITION BY LIST (bucket);

DO $$
DECLARE
    bucket_name TEXT;
    parent_table TEXT;
BEGIN
    FOR bucket_name IN SELECT unnested FROM unnest(ARRAY['S5','M1','M5','H1','D1','D7']) AS unnested LOOP
        parent_table := format('event_histograms_%s', lower(bucket_name));
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS obsinity.%I
             PARTITION OF obsinity.event_histograms
             FOR VALUES IN (''%s'')
             PARTITION BY RANGE (ts);',
            parent_table,
            bucket_name
        );
    END LOOP;
END $$;

DO $$
DECLARE
    start_date DATE := (CURRENT_DATE - INTERVAL '28 days')::DATE;
    end_date   DATE := (CURRENT_DATE + INTERVAL '28 days')::DATE;
    week_start DATE;
    week_end   DATE;
    bucket_name TEXT;
    parent_table TEXT;
    partition_name TEXT;
BEGIN
    week_start := start_date;
    WHILE week_start < end_date LOOP
        week_end := week_start + INTERVAL '7 days';

        FOR bucket_name IN SELECT unnested FROM unnest(ARRAY['S5','M1','M5','H1','D1','D7']) AS unnested LOOP
            parent_table := format('event_histograms_%s', lower(bucket_name));
            partition_name := format(
                'event_histograms_%s_%s',
                to_char(week_start, 'IYYY_IW'),
                lower(bucket_name)
            );

            EXECUTE format(
                'CREATE UNLOGGED TABLE IF NOT EXISTS obsinity.%I
                 PARTITION OF obsinity.%I
                 FOR VALUES FROM (TIMESTAMPTZ %L) TO (TIMESTAMPTZ %L);',
                partition_name,
                parent_table,
                week_start,
                week_end
            );

            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS %I ON obsinity.%I(event_type_id);',
                partition_name || '_event_type_idx',
                partition_name
            );
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS %I ON obsinity.%I(histogram_config_id);',
                partition_name || '_histogram_config_idx',
                partition_name
            );
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS %I ON obsinity.%I(key_hash);',
                partition_name || '_key_hash_idx',
                partition_name
            );
END LOOP;

week_start := week_end;
END LOOP;
END $$;

-- ================================================
-- Object state storage for state extractor feature
-- ================================================
CREATE TABLE IF NOT EXISTS obsinity.object_state (
  ts TIMESTAMPTZ NOT NULL,
  bucket VARCHAR(10) NOT NULL,
  service_id UUID NOT NULL,
  object_type TEXT NOT NULL,
  object_id TEXT NOT NULL,
  attribute TEXT NOT NULL,
  state_value TEXT,
  PRIMARY KEY (ts, bucket, service_id, object_type, object_id, attribute)
)
PARTITION BY LIST (bucket);

DO $$
DECLARE
    bucket_name TEXT;
    parent_table TEXT;
BEGIN
    FOR bucket_name IN SELECT unnested FROM unnest(ARRAY['S5','M1','M5','H1','D1','D7']) AS unnested LOOP
        parent_table := format('object_state_%s', lower(bucket_name));
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS obsinity.%I
             PARTITION OF obsinity.object_state
             FOR VALUES IN (''%s'')
             PARTITION BY RANGE (ts);',
            parent_table,
            bucket_name
        );
    END LOOP;
END $$;

-- ================================================
-- Object state counts (current snapshot per state)
-- ================================================
CREATE TABLE IF NOT EXISTS obsinity.object_state_counts (
  ts TIMESTAMPTZ NOT NULL,
  bucket VARCHAR(10) NOT NULL,
  service_id UUID NOT NULL,
  object_type TEXT NOT NULL,
  attribute TEXT NOT NULL,
  state_value TEXT NOT NULL,
  count BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (ts, bucket, service_id, object_type, attribute, state_value)
)
PARTITION BY LIST (bucket);

DO $$
DECLARE
    bucket_name TEXT;
    parent_table TEXT;
BEGIN
    FOR bucket_name IN SELECT unnested FROM unnest(ARRAY['S5','M1','M5','H1','D1','D7']) AS unnested LOOP
        parent_table := format('object_state_counts_%s', lower(bucket_name));
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS obsinity.%I
             PARTITION OF obsinity.object_state_counts
             FOR VALUES IN (''%s'')
             PARTITION BY RANGE (ts);',
            parent_table,
            bucket_name
        );
    END LOOP;
END $$;

DO $$(
DECLARE
    start_date DATE := (CURRENT_DATE - INTERVAL '28 days')::DATE;
    end_date   DATE := (CURRENT_DATE + INTERVAL '28 days')::DATE;
    week_start DATE;
    week_end   DATE;
    bucket_name TEXT;
    parent_table TEXT;
    partition_name TEXT;
BEGIN
    week_start := start_date;
    WHILE week_start < end_date LOOP
        week_end := week_start + INTERVAL '7 days';

        FOR bucket_name IN SELECT unnested FROM unnest(ARRAY['S5','M1','M5','H1','D1','D7']) AS unnested LOOP
            parent_table := format('object_state_counts_%s', lower(bucket_name));
            partition_name := format(
                'object_state_counts_%s_%s',
                to_char(week_start, 'IYYY_IW'),
                lower(bucket_name)
            );

            EXECUTE format(
                'CREATE UNLOGGED TABLE IF NOT EXISTS obsinity.%I
                 PARTITION OF obsinity.%I
                 FOR VALUES FROM (TIMESTAMPTZ %L) TO (TIMESTAMPTZ %L);',
                partition_name,
                parent_table,
                week_start,
                week_end
            );

            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS %I ON obsinity.%I(object_type, attribute);',
                partition_name || '_object_idx',
                partition_name
            );
        END LOOP;

        week_start := week_end;
    END LOOP;
END $$;

DO $$
DECLARE
    start_date DATE := (CURRENT_DATE - INTERVAL '28 days')::DATE;
    end_date   DATE := (CURRENT_DATE + INTERVAL '28 days')::DATE;
    week_start DATE;
    week_end   DATE;
    bucket_name TEXT;
    parent_table TEXT;
    partition_name TEXT;
BEGIN
    week_start := start_date;
    WHILE week_start < end_date LOOP
        week_end := week_start + INTERVAL '7 days';

        FOR bucket_name IN SELECT unnested FROM unnest(ARRAY['S5','M1','M5','H1','D1','D7']) AS unnested LOOP
            parent_table := format('object_state_%s', lower(bucket_name));
            partition_name := format(
                'object_state_%s_%s',
                to_char(week_start, 'IYYY_IW'),
                lower(bucket_name)
            );

            EXECUTE format(
                'CREATE UNLOGGED TABLE IF NOT EXISTS obsinity.%I
                 PARTITION OF obsinity.%I
                 FOR VALUES FROM (TIMESTAMPTZ %L) TO (TIMESTAMPTZ %L);',
                partition_name,
                parent_table,
                week_start,
                week_end
            );

            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS %I ON obsinity.%I(object_type, object_id);',
                partition_name || '_object_idx',
                partition_name
            );
        END LOOP;

        week_start := week_end;
    END LOOP;
END $$;

DO $$
DECLARE
    start_date DATE := DATE '2025-01-01';
    end_date   DATE := DATE '2027-01-01';
    week_start DATE;
    week_end   DATE;
    bucket_name TEXT;
    parent_table TEXT;
    partition_name TEXT;
BEGIN
    week_start := start_date;
    WHILE week_start < end_date LOOP
        week_end := week_start + INTERVAL '7 days';

        FOR bucket_name IN SELECT unnested FROM unnest(ARRAY['S5','M1','M5','H1','D1','D7']) AS unnested LOOP
            parent_table := format('event_counts_%s', lower(bucket_name));
            partition_name := format(
                'event_counts_%s_%s',
                to_char(week_start, 'IYYY_IW'),
                lower(bucket_name)
            );

            EXECUTE format(
                'CREATE UNLOGGED TABLE IF NOT EXISTS obsinity.%I
                 PARTITION OF obsinity.%I
                 FOR VALUES FROM (TIMESTAMPTZ %L) TO (TIMESTAMPTZ %L);',
                partition_name,
                parent_table,
                week_start,
                week_end
            );

            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS %I ON obsinity.%I(event_type_id);',
                partition_name || '_event_type_idx',
                partition_name
            );
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS %I ON obsinity.%I(counter_config_id);',
                partition_name || '_counter_config_idx',
                partition_name
            );
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS %I ON obsinity.%I(key_hash);',
                partition_name || '_key_hash_idx',
                partition_name
            );
        END LOOP;

        week_start := week_end;
    END LOOP;
END $$;
