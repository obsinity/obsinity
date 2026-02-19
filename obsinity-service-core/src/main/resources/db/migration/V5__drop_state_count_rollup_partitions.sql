-- Stop materializing state-count rollup buckets and drop their partition trees.
-- Keep only M1 for object_state_count_timeseries.
DO $$
DECLARE
    bucket_name TEXT;
    parent_table TEXT;
BEGIN
    FOR bucket_name IN SELECT unnested FROM unnest(ARRAY['M5', 'H1', 'D1']) AS unnested LOOP
        parent_table := format('object_state_count_timeseries_%s', lower(bucket_name));
        EXECUTE format('DROP TABLE IF EXISTS obsinity.%I CASCADE;', parent_table);
    END LOOP;
END $$;
