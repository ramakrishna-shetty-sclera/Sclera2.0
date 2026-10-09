-- A global template mints and tracks question keys the same way an
-- organization's own template does (procedure_template.key_seq), so an
-- imported copy can carry the counter forward exactly as cloneTemplate
-- already does for an organization-to-organization copy.
ALTER TABLE global_procedure_template ADD COLUMN key_seq INTEGER NOT NULL DEFAULT 0;
