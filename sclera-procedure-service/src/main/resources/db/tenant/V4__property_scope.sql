-- The property (VDMS) dimension, and the rule that enforces it.
--
-- A procedure either belongs to one property or to the whole organization:
--
--   property_id NULL      org-wide — every property in this organization sees it
--   property_id set       that property only
--
-- Existing rows become org-wide, which is the right default: they were authored
-- before properties existed and were visible to the whole organization.
--
-- Organizations are already separated by the schema this runs in. This adds the
-- second boundary, inside it, between properties of the same organization.
--
-- DDL is identical to db/migration/V6__property_scope.sql; keep the two in step.

ALTER TABLE procedure_template ADD COLUMN property_id UUID;

COMMENT ON COLUMN procedure_template.property_id IS
    'Owning property (VDMS). NULL means the procedure belongs to the whole organization.';

-- The policy predicate runs on every read of this table, so it wants an index.
-- Partial, because org-wide rows are matched by IS NULL rather than by value
-- and indexing those entries would cost storage for nothing.
CREATE INDEX idx_procedure_template_property
    ON procedure_template (property_id) WHERE property_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------
-- Postgres attaches the predicate below to the table, so it applies to every
-- query that touches it — including the ones nobody wrote carefully. A query
-- that forgets to filter by property still cannot see another property's rows,
-- because the filter was never the query's to leave out.
--
-- This only does anything because requests arrive on sclera_app, which is
-- neither the owner nor a superuser (see V5__app_role.sql). The owner keeps
-- bypassing it, which is what lets migrations and backfills touch every row.
--
-- sclera.property_ids is set on the connection when it is checked out of the
-- pool and cleared when it goes back. Unset, current_setting(...,true) returns
-- NULL, string_to_array(NULL,',') is NULL, and = ANY(NULL) is NULL — so no
-- property row matches and the table looks empty. Missing context hides data
-- rather than leaking it, which is the direction worth failing in.

ALTER TABLE procedure_template ENABLE ROW LEVEL SECURITY;

CREATE POLICY property_isolation ON procedure_template
    USING (
        property_id IS NULL
        OR property_id = ANY (
             string_to_array(current_setting('sclera.property_ids', true), ',')::uuid[]
           )
    )
    -- Without WITH CHECK, a caller could write a row tagged to a property they
    -- cannot read: visible to nobody who should see it, invisible to its owner.
    WITH CHECK (
        property_id IS NULL
        OR property_id = ANY (
             string_to_array(current_setting('sclera.property_ids', true), ',')::uuid[]
           )
    );
