-- Reference documents now store their bytes directly in this row, the same
-- way ai_document already does (V17/V12) — one Postgres table, no second
-- service, no Dapr call. The helper-service storage port this replaces is
-- left in place for sclera-helper-service's own future use; this service
-- simply stops calling it.
--
-- property_id and its row-level-security policy are kept even though
-- ai_document has neither: a reference document is cited across property
-- boundaries by a procedure's own definition_json, which ai_document never
-- is, so the isolation guarantee this table already had must not be lost
-- just because the storage pattern being borrowed happens to lack it.
--
-- No backfill: any row from before this migration points at a helper-service
-- location this service can no longer read bytes from, so it cannot be
-- carried forward — this is dev/demo data only.
--
-- DDL is identical to db/migration/V20__procedure_document_bytes.sql; keep
-- the two in step.

ALTER TABLE procedure_document DROP COLUMN location;
ALTER TABLE procedure_document ADD COLUMN content BYTEA NOT NULL DEFAULT ''::bytea;
ALTER TABLE procedure_document ALTER COLUMN content DROP DEFAULT;
