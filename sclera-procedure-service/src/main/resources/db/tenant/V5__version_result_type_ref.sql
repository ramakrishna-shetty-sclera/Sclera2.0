-- Which result types each published version uses.
--
-- definition_json is TEXT, kept byte for byte for its hash, so "does any
-- published version name the result type AMBER?" cannot be asked of the
-- documents themselves. This index answers it in one query, and that is what
-- lets a result type be refused deletion while a version still depends on it:
-- delete it anyway and every version mapped to it becomes unreadable.
--
-- Written at publish, inside the publish transaction, from the document being
-- frozen — one row per distinct result-type key it names, however many answers
-- name it. Never for a draft: a draft commits to nothing, and a result type
-- someone is only trying out should stay deletable. A published version is
-- never deleted, so rows only accumulate.
--
-- No backfill. Versions published before this table existed hold test data
-- only; they are simply not counted.
--
-- No row-level security, deliberately. The delete guard has to see every
-- property's usage: an organization admin deleting a result type at
-- organization level must not be told it is unused because the version using
-- it belongs to a property. The table holds keys, never content.
--
-- DDL is identical to db/migration/V7__version_result_type_ref.sql; keep the two in step.

CREATE TABLE version_result_type_ref (
    version_id      UUID         NOT NULL REFERENCES procedure_template_version (id),
    -- Same length as result_type.key, which it names. No foreign key to it:
    -- the whole point is that the row outlives a decision to retire the type.
    result_type_key VARCHAR(50)  NOT NULL,
    CONSTRAINT pk_version_result_type_ref PRIMARY KEY (version_id, result_type_key)
);

-- The guard asks by key. The primary key already serves lookups by version.
CREATE INDEX idx_vrtr_result_type_key ON version_result_type_ref (result_type_key);
