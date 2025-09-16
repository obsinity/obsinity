-- Distinct attribute values per service and attribute path
CREATE TABLE IF NOT EXISTS attribute_distinct_values (
  service_short  TEXT        NOT NULL,
  attr_name      TEXT        NOT NULL,
  attr_value     TEXT        NOT NULL,
  first_seen     TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen      TIMESTAMPTZ NOT NULL DEFAULT now(),
  seen_count     BIGINT      NOT NULL DEFAULT 0,

  PRIMARY KEY (service_short, attr_name, attr_value)
);

CREATE INDEX IF NOT EXISTS ix_attr_distinct_service_attr ON attribute_distinct_values(service_short, attr_name);

