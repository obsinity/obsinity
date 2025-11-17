-- ================================================
-- Object state count time series snapshots (M1)
-- ================================================
CREATE TABLE IF NOT EXISTS obsinity.object_state_count_timeseries (
  ts TIMESTAMPTZ NOT NULL,
  bucket VARCHAR(10) NOT NULL,
  service_id UUID NOT NULL,
  object_type TEXT NOT NULL,
  attribute TEXT NOT NULL,
  state_value TEXT NOT NULL,
  state_count BIGINT NOT NULL,
  PRIMARY KEY (ts, bucket, service_id, object_type, attribute, state_value)
)
PARTITION BY LIST (bucket);

DO $$
DECLARE
    bucket_name TEXT;
    parent_table TEXT;
BEGIN
    FOR bucket_name IN SELECT unnested FROM unnest(ARRAY['M1']) AS unnested LOOP
        parent_table := format('object_state_count_timeseries_%s', lower(bucket_name));
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS obsinity.%I
             PARTITION OF obsinity.object_state_count_timeseries
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
    parent_table TEXT := 'object_state_count_timeseries_m1';
    partition_name TEXT;
BEGIN
    week_start := start_date;
    WHILE week_start < end_date LOOP
        week_end := week_start + INTERVAL '7 days';

        partition_name := format(
            'object_state_count_timeseries_%s_m1',
            to_char(week_start, 'IYYY_IW')
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
            'CREATE INDEX IF NOT EXISTS %I ON obsinity.%I(service_id, object_type, attribute);',
            partition_name || '_service_idx',
            partition_name
        );

        week_start := week_end;
    END LOOP;
END $$;
