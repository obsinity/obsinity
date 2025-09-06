create table if not exists events_raw (
  event_id       uuid primary key,              -- always UUID
  ts             timestamptz not null,
  type           text not null,                 -- == event name
  service_id     text,                          -- tenant == service
  trace_id       text,
  span_id        text,
  correlation_id text,
  attributes     jsonb not null default '{}'::jsonb
);

create index if not exists idx_events_raw_ts         on events_raw (ts);
create index if not exists idx_events_raw_type_ts    on events_raw (type, ts);
create index if not exists idx_events_raw_service_ts on events_raw (service_id, ts);
create index if not exists idx_events_raw_attr_gin   on events_raw using gin (attributes);

create table if not exists event_type_catalog (
  type text primary key,
  description text,
  created_at timestamptz not null default now()
);

create table if not exists event_index_catalog (
  type text not null,
  path text not null,
  created_at timestamptz not null default now(),
  primary key (type, path),
  constraint fk_idx_type foreign key (type) references event_type_catalog(type) on delete cascade
);
