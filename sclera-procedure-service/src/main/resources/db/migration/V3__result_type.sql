-- Result types: the per-organization vocabulary of outcomes an answer,
-- question, category or whole inspection can produce (Pass, Fail, Amber,
-- Required, Not applicable, ...). Organizations define their own; Pass and
-- Fail are seeded as system types that cannot be deleted or deactivated.
--
-- severity_order ranks them with 1 as MOST severe, so rolling several results
-- into one takes the MINIMUM — the worst result wins. Ranks are dense (1..n)
-- and rewritten wholesale on reorder, which is safe because nothing outside
-- this table ever stores the number; records store `key`.
--
-- Mirrored in db/tenant/V2__result_type.sql. The two must stay
-- column-for-column identical; only the tenant copy seeds rows, since the
-- public schema has no organization to seed for. This table exists in public
-- solely because ddl-auto=validate checks every entity against the default
-- schema at boot and refuses to start if one is missing.

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
