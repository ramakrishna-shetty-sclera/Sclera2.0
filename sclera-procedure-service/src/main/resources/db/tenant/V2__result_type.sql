-- Result types: the per-organization vocabulary of outcomes an answer,
-- question, category or whole inspection can produce (Pass, Fail, Amber,
-- Required, Not applicable, ...). Organizations define their own; Pass and
-- Fail are seeded below as system types that cannot be deleted or deactivated.
--
-- severity_order ranks them with 1 as MOST severe, so rolling several results
-- into one takes the MINIMUM — the worst result wins. Ranks are dense (1..n)
-- and rewritten wholesale on reorder, which is safe because nothing outside
-- this table ever stores the number; records store `key`.
--
-- DDL is identical to db/migration/V3__result_type.sql; keep the two in step.

CREATE TABLE result_type (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         UUID         NOT NULL,
    key            VARCHAR(50)  NOT NULL,
    name           VARCHAR(100) NOT NULL,
    color          VARCHAR(7)   NOT NULL,
    description    VARCHAR(500),
    severity_order INT          NOT NULL,
    is_system      BOOLEAN      NOT NULL DEFAULT FALSE,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_result_type_org_key UNIQUE (org_id, key),
    CONSTRAINT chk_result_type_color  CHECK (color ~ '^#[0-9A-Fa-f]{6}$')
);

CREATE INDEX idx_result_type_org        ON result_type (org_id);
CREATE INDEX idx_result_type_org_active ON result_type (org_id, active);

-- Pass and Fail exist from the moment a tenant schema is created, so no
-- organization ever faces an empty vocabulary with nothing to map answers to.
--
-- Flyway has no parameter for the org id, so it is derived from the schema
-- name: tenant schemas are t_<32 hex chars> (see TenantSchemas.schemaFor) and
-- Postgres casts the unhyphenated form straight to uuid. The regex guard means
-- any schema that is not a tenant schema simply gets the table and no rows.
DO $$
DECLARE
    tenant_org UUID;
BEGIN
    IF current_schema() ~ '^t_[0-9a-f]{32}$' THEN
        tenant_org := substring(current_schema() FROM 3)::uuid;

        INSERT INTO result_type (org_id, key, name, color, description, severity_order, is_system)
        VALUES (tenant_org, 'FAIL', 'Fail', '#e74c3c', 'Does not meet criteria', 1, TRUE),
               (tenant_org, 'PASS', 'Pass', '#2ecc71', 'Meets criteria',         2, TRUE);
    END IF;
END $$;
