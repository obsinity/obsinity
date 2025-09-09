-- =========================
-- V1__init.sql (clean start)
-- =========================

-- Extensions (UUID & hashing helpers)
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- ===========================================
-- Canonical raw inbox aligned to EventEnvelope
-- ===========================================
-- Partitioning: LIST(service_id) -> RANGE(occurred_at weekly)
CREATE TABLE events_raw (
  event_id      UUID NOT NULL,
  parent_event_id   UUID,

  -- Core envelope
  service_short    TEXT        NOT NULL,
  event_type       TEXT        NOT NULL,
  attributes       JSONB       NOT NULL DEFAULT '{}'::jsonb,
  occurred_at      TIMESTAMPTZ NOT NULL,              -- producer time
  received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),-- ingest time

  -- OTEL-aligned trace/correlation
  trace_id         TEXT,
  span_id          TEXT,
  parent_span_id   TEXT,
  correlation_id   TEXT,

  primary key (service_short, occurred_at, event_id)
)
PARTITION BY LIST (service_short);

-- Default partition (catches events for unregistered/missing service short_key)
create table if not exists events_raw_default
  partition of events_raw default;

 -- ================================================
 -- Services Catalog
 -- ================================================

 create table if not exists services (
   id          uuid primary key default gen_random_uuid(),
   service_key text not null unique,         -- full name (e.g. "obsinity-reference-service")
   short_key   text not null unique,         -- short hash (8-char sha256 hex substring)
   description text,
   created_at  timestamptz not null default now()
 );

 -- Insert demo service (with sha256 8-char short key)
 insert into services (service_key, short_key, description)
 values (
   'obsinity-reference-service',
   substring(encode(digest('obsinity-reference-service', 'sha256'), 'hex') for 8),
   'Obsinity Reference Service (demo HTTP ingest server)'
 )
 on conflict (service_key) do nothing
 returning id, short_key;

 -- ================================================
 -- Event type + index catalogs
 -- ================================================
 create table if not exists event_type_catalog (
   type        text primary key,
   description text,
   created_at  timestamptz not null default now()
 );

 create table if not exists event_index_catalog (
   type        text not null,
   path        text not null,
   created_at  timestamptz not null default now(),
   primary key (type, path),
   constraint fk_idx_type
     foreign key (type) references event_type_catalog(type) on delete cascade
 );