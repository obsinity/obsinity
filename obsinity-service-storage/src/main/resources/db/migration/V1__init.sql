-- ================================================
-- Root events table (partitioned by service short_key → list, ts → range)
-- ================================================
create table if not exists events_raw (
  event_id       uuid not null,
  ts             timestamptz not null,
  type           text not null,
  service_short  text not null,  -- 8-char SHA-256 short key
  trace_id       text,
  span_id        text,
  correlation_id text,
  attributes     jsonb not null default '{}'::jsonb,
  primary key (service_short, ts, event_id)
) partition by list (service_short);

-- Default partition (catches events for unregistered/missing service short_key)
create table if not exists events_raw_default
  partition of events_raw default;

-- ================================================
-- Services Catalog
-- ================================================
create extension if not exists pgcrypto;

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
