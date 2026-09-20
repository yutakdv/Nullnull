-- One durable cadence claim per Seoul area. Every API replica may attempt the schedule, but this
-- single statement's conflict update admits at most one every two minutes. It holds no database
-- connection while the provider is called and a crashed replica cannot strand a lock.
CREATE TABLE seoul_live_refresh_claims (
    source_code varchar(64) NOT NULL REFERENCES source_registry(code),
    area_name varchar(200) NOT NULL,
    next_due_at timestamptz NOT NULL,
    PRIMARY KEY (source_code, area_name),
    CONSTRAINT seoul_live_refresh_source_check CHECK (source_code = 'SEOUL_CITYDATA'),
    CONSTRAINT seoul_live_refresh_area_check CHECK (btrim(area_name) <> '')
);
