-- sclera-helper-service: locations (building → floor → location) and assets.
-- Public copy of the tenant DDL — keeps hbm2ddl validate green (validation
-- runs against the connection default schema). Tenant data lives in the
-- per-tenant schemas (db/tenant).

CREATE TABLE location (
    id         UUID PRIMARY KEY,
    org_id     UUID         NOT NULL,
    name       VARCHAR(200) NOT NULL,
    type       VARCHAR(16)  NOT NULL,
    parent_id  UUID,
    created_by UUID,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_location_type CHECK (type IN ('BUILDING', 'FLOOR', 'LOCATION'))
);
CREATE INDEX idx_location_org ON location (org_id);
CREATE INDEX idx_location_parent ON location (parent_id);

CREATE TABLE asset (
    id          UUID PRIMARY KEY,
    org_id      UUID         NOT NULL,
    name        VARCHAR(200) NOT NULL,
    asset_type  VARCHAR(16)  NOT NULL,
    ip_address  VARCHAR(45),
    location_id UUID,
    created_by  UUID,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_asset_type CHECK (asset_type IN ('IP', 'NON_IP'))
);
CREATE INDEX idx_asset_org ON asset (org_id);
CREATE INDEX idx_asset_location ON asset (location_id);
