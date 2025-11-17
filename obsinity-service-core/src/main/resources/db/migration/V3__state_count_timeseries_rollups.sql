-- Extend state count timeseries to support rollup buckets (M5/H1/D1)
DO $$
DECLARE
    bucket_name TEXT;
    parent_table TEXT;
BEGIN
    FOR bucket_name IN SELECT unnested FROM unnest(ARRAY['M5','M30','H1','D1']) AS unnested LOOP
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
    bucket_name TEXT;
    parent_table TEXT;
    partition_name TEXT;
BEGIN
    week_start := start_date;
    WHILE week_start < end_date LOOP
        week_end := week_start + INTERVAL '7 days';

        FOR bucket_name IN SELECT unnested FROM unnest(ARRAY['M5','M30','H1','D1']) AS unnested LOOP
            parent_table := format('object_state_count_timeseries_%s', lower(bucket_name));
            partition_name := format(
                'object_state_count_timeseries_%s_%s',
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
                'CREATE INDEX IF NOT EXISTS %I ON obsinity.%I(service_id, object_type, attribute);',
                partition_name || '_service_idx',
                partition_name
            );
        END LOOP;

        week_start := week_end;
    END LOOP;
END $$;
