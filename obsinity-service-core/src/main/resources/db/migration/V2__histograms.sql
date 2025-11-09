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
