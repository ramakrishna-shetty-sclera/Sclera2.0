-- The Sclera-wide template library, and the link that records which
-- organizations have copied which global template.
--
-- Deliberately NOT written twice. Every other table in this service lives in
-- both db/migration (public, for ddl-auto=validate at boot) and db/tenant
-- (per-org schema, the one actually used at runtime), because every other
-- table is tenant data. These three are not: they are Sclera's own shared
-- library, visible to every organization, and the cross-org link table that
-- tracks who has copied what from it. There is no "tenant copy" to write,
-- because there is no tenant scoping here at all — one copy, in public,
-- is the correct and complete shape. Do not add a db/tenant file for this.
--
-- A per-request connection's search_path is pinned to exactly one schema —
-- the requesting organization's tenant schema, with no public fallback (see
-- SchemaMultiTenantConnectionProvider.setSearchPath). So every entity mapped
-- onto these three tables must be schema-qualified explicitly
-- (@Table(schema = "public", name = "...")) rather than relying on search
-- path to find them — an unqualified reference would not resolve during an
-- ordinary tenant-scoped request.

CREATE TABLE global_procedure_template (
    id                            UUID         PRIMARY KEY,
    name                          VARCHAR(200) NOT NULL,
    description                   VARCHAR(2000),
    consumer_key                  VARCHAR(50),
    current_published_version_id UUID,
    status                        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_by                    UUID,
    created_at                    TIMESTAMPTZ  NOT NULL,
    updated_at                    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE global_procedure_template_version (
    id                 UUID        PRIMARY KEY,
    global_template_id UUID        NOT NULL REFERENCES global_procedure_template (id),
    version_no         INTEGER     NOT NULL,
    state              VARCHAR(20) NOT NULL,
    -- TEXT, not jsonb, for the same reason procedure_template_version.definition_json
    -- is TEXT: the hash is taken over the exact bytes stored, and jsonb would
    -- re-order keys and strip whitespace on the way back out.
    definition_json    TEXT        NOT NULL,
    definition_hash    VARCHAR(64) NOT NULL,
    change_note        VARCHAR(2000),
    published_by       UUID,
    published_at       TIMESTAMPTZ,
    CONSTRAINT uq_global_version_no UNIQUE (global_template_id, version_no)
);

CREATE INDEX idx_gptv_global_template ON global_procedure_template_version (global_template_id);

-- Which organizations have copied which global template, and whether each
-- copy is still tracking updates.
--
--   link_state = LINKED      still tracking the global template's updates
--   link_state = DEFERRED    an update exists and was explicitly passed over
--                            (feature 11 writes this; this feature never does)
--   link_state = STANDALONE  forked — either by editing the copy, or by an
--                            explicit unlink — and no longer tracks updates
--
-- "Update available" is derived, never stored: applied_version_no compared
-- against the global template's current published version. deferred_version_no
-- and deferred_at are feature 11's fields; the columns are added now because
-- adding them once is cheap and a second migration later is not, but nothing
-- in this feature writes anything into them but null.
--
-- No row-level security: this is the one table in this service where the
-- schema boundary does not do the isolating, since every organization's
-- requests can reach this same public-schema table. Every query against it
-- must filter org_id explicitly in the repository layer — the only thing
-- protecting the boundary here, not a backstop under a schema wall.
CREATE TABLE global_template_org_copy (
    global_template_id  UUID         NOT NULL REFERENCES global_procedure_template (id),
    org_id              UUID         NOT NULL,
    template_id         UUID         NOT NULL,
    applied_version_no  INTEGER      NOT NULL,
    link_state          VARCHAR(20)  NOT NULL DEFAULT 'LINKED',
    deferred_version_no INTEGER,
    deferred_at         TIMESTAMPTZ,
    linked_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_global_template_org_copy PRIMARY KEY (global_template_id, org_id)
);

CREATE INDEX idx_gtoc_org ON global_template_org_copy (org_id);
