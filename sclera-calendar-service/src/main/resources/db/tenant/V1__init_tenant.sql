-- sclera-calendar-service: per-property date/time rules — settings, working
-- hours (JSONB shifts), downtime windows (one-off + recurring), holidays
-- (fixed / floating / one-off + per-property overrides), feature opt-in
-- policies and a calendar audit trail.
-- Identical copy lives in db/migration (public schema) to keep hbm2ddl
-- validate green; tenant data lives in the per-tenant schemas.

CREATE TABLE calendar_settings (
    id                      UUID PRIMARY KEY,
    org_id                  UUID         NOT NULL,
    property_id             UUID,                      -- NULL = org-wide default
    timezone                VARCHAR(64)  NOT NULL,     -- IANA, e.g. America/New_York
    use_24_hour_clock       BOOLEAN      NOT NULL DEFAULT TRUE,
    date_format_pattern     VARCHAR(32)  NOT NULL DEFAULT 'yyyy-MM-dd',
    first_day_of_week       VARCHAR(9)   NOT NULL DEFAULT 'MONDAY',
    week_numbering          VARCHAR(8)   NOT NULL DEFAULT 'ISO',
    calendar_system         VARCHAR(16)  NOT NULL DEFAULT 'ISO8601',  -- reserved; ISO8601 only
    fiscal_year_start_month INTEGER     NOT NULL DEFAULT 1,
    fiscal_year_start_day   INTEGER     NOT NULL DEFAULT 1,
    -- { "MONDAY": [ { "start": "09:00", "end": "17:00" }, ... ], ... }
    -- missing/empty day = non-working; end <= start = crosses midnight
    working_hours           JSONB        NOT NULL,
    created_by              UUID,
    created_at              TIMESTAMPTZ  NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_settings_week_numbering CHECK (week_numbering IN ('ISO', 'US')),
    CONSTRAINT chk_settings_first_dow CHECK (first_day_of_week IN
        ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY')),
    CONSTRAINT chk_settings_fiscal CHECK (
        fiscal_year_start_month BETWEEN 1 AND 12 AND fiscal_year_start_day BETWEEN 1 AND 31)
);
CREATE INDEX idx_settings_org ON calendar_settings (org_id);
-- exactly one settings row per property, and one org-wide default
CREATE UNIQUE INDEX uq_settings_property ON calendar_settings (property_id) WHERE property_id IS NOT NULL;
CREATE UNIQUE INDEX uq_settings_org_default ON calendar_settings ((1)) WHERE property_id IS NULL;

CREATE TABLE downtime_window (
    id               UUID PRIMARY KEY,
    org_id           UUID         NOT NULL,
    property_id      UUID,                       -- NULL = whole org
    scope_type       VARCHAR(16)  NOT NULL DEFAULT 'PROPERTY',
    scope_id         UUID,                       -- sub-resource (location/asset), NULL = whole scope
    downtime_type    VARCHAR(16)  NOT NULL,
    reason           VARCHAR(500),
    kind             VARCHAR(12)  NOT NULL,
    -- ONE_OFF: absolute instants
    start_at         TIMESTAMPTZ,
    end_at           TIMESTAMPTZ,
    -- RECURRING: wall-clock in the property timezone, expanded at query time
    recurrence       JSONB,
    series_start     DATE,
    local_start_time TIME,
    duration_minutes INTEGER,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by       UUID,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_downtime_type CHECK (downtime_type IN ('MAINTENANCE','BLACKOUT','CLOSURE','OTHER')),
    CONSTRAINT chk_downtime_kind CHECK (kind IN ('ONE_OFF','RECURRING')),
    CONSTRAINT chk_downtime_scope CHECK (scope_type IN ('PROPERTY','LOCATION','ASSET')),
    CONSTRAINT chk_downtime_one_off CHECK (
        kind <> 'ONE_OFF' OR (start_at IS NOT NULL AND end_at IS NOT NULL AND start_at < end_at)),
    CONSTRAINT chk_downtime_recurring CHECK (
        kind <> 'RECURRING' OR (recurrence IS NOT NULL AND series_start IS NOT NULL
            AND local_start_time IS NOT NULL AND duration_minutes > 0))
);
CREATE INDEX idx_downtime_org ON downtime_window (org_id);
CREATE INDEX idx_downtime_property ON downtime_window (property_id);
CREATE INDEX idx_downtime_one_off_range ON downtime_window (start_at, end_at) WHERE kind = 'ONE_OFF';

CREATE TABLE holiday (
    id           UUID PRIMARY KEY,
    org_id       UUID         NOT NULL,
    property_id  UUID,                       -- NULL = org-wide
    name         VARCHAR(200) NOT NULL,
    category     VARCHAR(32)  NOT NULL DEFAULT 'PUBLIC',
    date_type    VARCHAR(12)  NOT NULL,
    month        INTEGER,                   -- FIXED / FLOATING
    day_of_month INTEGER,                   -- FIXED
    week_ordinal INTEGER,                   -- FLOATING: 1..4, -1 = last
    day_of_week  VARCHAR(9),                 -- FLOATING
    on_date      DATE,                       -- ONE_OFF
    source       VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',  -- MANUAL or region code
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by   UUID,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_holiday_date_type CHECK (date_type IN ('FIXED','FLOATING','ONE_OFF')),
    CONSTRAINT chk_holiday_fixed CHECK (
        date_type <> 'FIXED' OR (month BETWEEN 1 AND 12 AND day_of_month BETWEEN 1 AND 31)),
    CONSTRAINT chk_holiday_floating CHECK (
        date_type <> 'FLOATING' OR (month BETWEEN 1 AND 12
            AND week_ordinal IN (-1, 1, 2, 3, 4) AND day_of_week IS NOT NULL)),
    CONSTRAINT chk_holiday_one_off CHECK (date_type <> 'ONE_OFF' OR on_date IS NOT NULL)
);
CREATE INDEX idx_holiday_org ON holiday (org_id);
CREATE INDEX idx_holiday_property ON holiday (property_id);

CREATE TABLE holiday_override (
    id          UUID PRIMARY KEY,
    org_id      UUID        NOT NULL,
    property_id UUID        NOT NULL,
    holiday_id  UUID        NOT NULL REFERENCES holiday (id) ON DELETE CASCADE,
    action      VARCHAR(8)  NOT NULL,
    year        INTEGER,                    -- NULL = every year
    moved_to    DATE,                        -- MOVE only
    created_by  UUID,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_override_action CHECK (action IN ('DISABLE','MOVE')),
    CONSTRAINT chk_override_move CHECK (action <> 'MOVE' OR moved_to IS NOT NULL)
);
CREATE INDEX idx_override_holiday ON holiday_override (holiday_id);
CREATE UNIQUE INDEX uq_override ON holiday_override (property_id, holiday_id, COALESCE(year, 0));

CREATE TABLE feature_calendar_policy (
    id                    UUID PRIMARY KEY,
    org_id                UUID         NOT NULL,
    feature_code          VARCHAR(100) NOT NULL,
    property_id           UUID,                   -- NULL = org-wide default for the feature
    respect_working_hours BOOLEAN      NOT NULL DEFAULT TRUE,
    respect_downtime      BOOLEAN      NOT NULL DEFAULT TRUE,
    respect_holidays      BOOLEAN      NOT NULL DEFAULT TRUE,
    downtime_types        VARCHAR(200),           -- CSV, NULL/empty = all types
    holiday_categories    VARCHAR(200),           -- CSV, NULL/empty = all categories
    created_by            UUID,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_policy_org ON feature_calendar_policy (org_id);
CREATE UNIQUE INDEX uq_policy ON feature_calendar_policy
    (feature_code, COALESCE(property_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- Config changes here silently change downstream scheduling behaviour, so every
-- mutation is recorded with a snapshot of the row after the change.
CREATE TABLE calendar_audit (
    id          UUID PRIMARY KEY,
    org_id      UUID        NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id   UUID        NOT NULL,
    action      VARCHAR(16) NOT NULL,     -- CREATED / UPDATED / DELETED / IMPORTED
    actor       UUID,
    at          TIMESTAMPTZ NOT NULL,
    snapshot    JSONB
);
CREATE INDEX idx_audit_entity ON calendar_audit (entity_type, entity_id);
CREATE INDEX idx_audit_at ON calendar_audit (at);
