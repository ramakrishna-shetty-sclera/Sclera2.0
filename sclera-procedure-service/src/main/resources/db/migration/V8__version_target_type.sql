-- What each published version applies to, as an index.
--
-- definition_json is TEXT, kept byte for byte for its hash, so "which published
-- procedures apply to asset class EXTINGUISHER?" cannot be asked of the
-- documents themselves. This index answers it in one query, which is what lets
-- the inspection configuration offer the right procedures instead of all of them.
--
-- Written at publish, inside the publish transaction, from the document being
-- frozen: one row per distinct (kind, key) it names. Never for a draft, which
-- commits to nothing. A published version is never deleted, so rows only
-- accumulate, and republishing unchanged content writes none.
--
-- A version that names no target types has no rows. That is not "applies to
-- nothing": it means it applies to anything, so discovery has to treat the
-- absence of rows as a match, never as a miss.
--
-- No backfill. Versions published before this table existed hold test data only
-- and are not counted.
--
-- No row-level security, deliberately. Discovery has to see every property's
-- versions: an organization-level caller must not be told nothing applies to an
-- asset class because the procedure that does belongs to a property. The table
-- holds keys, never content.
---- DDL is identical to db/tenant/V6__version_target_type.sql; keep the two in step.

CREATE TABLE version_target_type (
    version_id UUID        NOT NULL REFERENCES procedure_template_version (id),
    -- Which vocabulary list the key belongs to. The same four names the
    -- document's TargetKind and the property vocabulary use.
    kind       VARCHAR(20) NOT NULL,
    -- Same length as a document target type's key. No foreign key to the
    -- vocabulary: that lives in another service, and the row has to outlive a
    -- decision to retire the key.
    key        VARCHAR(50) NOT NULL,
    CONSTRAINT pk_version_target_type PRIMARY KEY (version_id, kind, key),
    CONSTRAINT chk_version_target_type_kind
        CHECK (kind IN ('HIERARCHY_LEVEL', 'LOCATION_TYPE', 'ASSET_CLASS', 'ASSET_TAG'))
);

-- Discovery asks "which published versions apply to asset class EXTINGUISHER?",
-- by kind and key. The primary key already serves lookups by version.
CREATE INDEX idx_vtt_kind_key ON version_target_type (kind, key);