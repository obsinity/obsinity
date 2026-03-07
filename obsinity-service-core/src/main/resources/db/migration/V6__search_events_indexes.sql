-- Improve /api/search/events performance for service+event+time windows.
-- 1) Base scan: avoid filtering event_type after a broad service/time index walk.
-- 2) Final fetch: speed JOIN page(event_id) -> events_raw_default with direct lookup.

CREATE INDEX IF NOT EXISTS ix_events_raw_default_search_service_event_started_event_id
    ON obsinity.events_raw_default (service_partition_key, event_type, started_at DESC, event_id);

CREATE INDEX IF NOT EXISTS ix_events_raw_default_search_service_event_id
    ON obsinity.events_raw_default (service_partition_key, event_id);
