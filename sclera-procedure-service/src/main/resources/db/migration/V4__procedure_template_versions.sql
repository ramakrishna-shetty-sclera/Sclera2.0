-- Procedure templates with immutable, content-addressed versions. Replaces the
-- old two-level model (question_template -> template_section -> question),
-- which kept one mutable row per template and re-minted every question id on
-- each save.
--
--   procedure_template          identity only: name, consumer, status, the
--                               moving pointer to the current published
--                               version, and the question-key counter.
--   procedure_template_version  the content: the whole form as one canonical
--                               JSON document (definition_json) plus its
--                               SHA-256 (definition_hash). A DRAFT is mutable;
--                               once PUBLISHED the row is never modified again.
--
-- definition_json is TEXT, not JSONB: JSONB reorders keys and strips
-- whitespace, so the stored bytes would no longer match the hash.
--
-- Mirrored in db/tenant/V3__procedure_template_versions.sql. The two must stay
-- column-for-column identical. This copy exists in public solely because
-- ddl-auto=validate checks every entity against the default schema at boot.

DROP TABLE IF EXISTS question;
DROP TABLE IF EXISTS template_section;
DROP TABLE IF EXISTS question_template;

CREATE TABLE procedure_template (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                       UUID          NOT NULL,
    name                         VARCHAR(200)  NOT NULL,
    description                  VARCHAR(2000),
    consumer_key                 VARCHAR(50)   NOT NULL DEFAULT 'INSPECTION',
    status                       VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    current_published_version_id UUID,
    -- Counter for minting category and question keys. Lives here, not on the
    -- version, because a key must never be reused across ANY version.
    key_seq                      INT           NOT NULL DEFAULT 0,
    -- Breadcrumb to the global master copy this was imported from (feature 10).
    global_template_id           UUID,
    -- Provenance for rows migrated from Sclera 1.0; makes the lift re-runnable.
    legacy_id                    VARCHAR(100),
    created_by                   UUID,
    created_at                   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_procedure_template_status  CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_procedure_template_key_seq CHECK (key_seq >= 0),
    CONSTRAINT uq_procedure_template_legacy   UNIQUE (org_id, legacy_id)
);

CREATE INDEX idx_procedure_template_org        ON procedure_template (org_id);
CREATE INDEX idx_procedure_template_org_status ON procedure_template (org_id, status);
-- Names are unique per organization among live templates; an archived
-- template frees its name.
CREATE UNIQUE INDEX uq_procedure_template_org_name_active
    ON procedure_template (org_id, lower(name)) WHERE status = 'ACTIVE';

CREATE TABLE procedure_template_version (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id     UUID          NOT NULL REFERENCES procedure_template (id),
    version_no      INT           NOT NULL,
    state           VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    origin          VARCHAR(20)   NOT NULL DEFAULT 'AUTHORED',
    definition_json TEXT          NOT NULL,
    definition_hash VARCHAR(64)   NOT NULL,
    first_seen_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    change_note     VARCHAR(2000),
    created_by      UUID,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_by    UUID,
    published_at    TIMESTAMPTZ,
    -- Optimistic lock on this row. Not a version of the form.
    row_version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_ptv_state      CHECK (state IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT chk_ptv_origin     CHECK (origin IN ('AUTHORED', 'MIGRATED', 'IMPORTED', 'GLOBAL_PUSH')),
    CONSTRAINT chk_ptv_version_no CHECK (version_no > 0),
    CONSTRAINT chk_ptv_hash       CHECK (definition_hash ~ '^[0-9a-f]{64}$'),
    -- A draft has never been published; anything else has.
    CONSTRAINT chk_ptv_published  CHECK ((state = 'DRAFT') = (published_at IS NULL)),
    CONSTRAINT uq_ptv_template_version_no UNIQUE (template_id, version_no)
);

-- At most one draft per template.
CREATE UNIQUE INDEX uq_ptv_one_draft
    ON procedure_template_version (template_id) WHERE state = 'DRAFT';
-- Publishing identical content twice is a no-op, never a second version.
CREATE UNIQUE INDEX uq_ptv_published_hash
    ON procedure_template_version (template_id, definition_hash) WHERE state = 'PUBLISHED';

ALTER TABLE procedure_template
    ADD CONSTRAINT fk_procedure_template_current_version
    FOREIGN KEY (current_published_version_id) REFERENCES procedure_template_version (id);
