-- A library of reference documents, and the index that protects one once a
-- published version cites it.
--
-- procedure_document is an organization/property-scoped library, not a
-- per-version attachment: a document is uploaded once and any number of
-- procedures cite it, by id, from inside definition_json. The row never
-- touches a file — location is an opaque key into the helper service's
-- storage port, generated there and never parsed here.
--
--   property_id NULL      org-wide — every property in this organization sees it
--   property_id set       that property only, fully isolated from every other
--
-- Same shape and the same row-level-security policy as procedure_template's:
-- an organization-wide procedure may cite only organization-wide documents,
-- because a property's document must never become reachable from outside
-- that property by way of a procedure that is visible everywhere.
--
-- DDL is identical to db/migration/V10__procedure_documents.sql; keep the two in step.

CREATE TABLE procedure_document (
    id          UUID         PRIMARY KEY,
    org_id      UUID         NOT NULL,
    property_id UUID,
    name        VARCHAR(255) NOT NULL,
    mime_type   VARCHAR(100),
    size_bytes  BIGINT,
    -- Opaque. Never a URL: a signed S3 URL expires, so storing one would store
    -- a dead link. A fresh one is generated on every request from this key.
    location    TEXT         NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    uploaded_by UUID,
    uploaded_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_procedure_document_org ON procedure_document (org_id);

-- Partial, same reasoning as procedure_template's: org-wide rows are matched
-- by IS NULL rather than by value, so indexing those would cost storage for
-- nothing.
CREATE INDEX idx_procedure_document_property
    ON procedure_document (property_id) WHERE property_id IS NOT NULL;

ALTER TABLE procedure_document ENABLE ROW LEVEL SECURITY;

CREATE POLICY property_isolation ON procedure_document
    USING (
        property_id IS NULL
        OR property_id = ANY (
             string_to_array(current_setting('sclera.property_ids', true), ',')::uuid[]
           )
    )
    WITH CHECK (
        property_id IS NULL
        OR property_id = ANY (
             string_to_array(current_setting('sclera.property_ids', true), ',')::uuid[]
           )
    );

-- Which published versions cite which documents.
--
-- definition_json is TEXT, kept byte for byte for its hash, so "is this
-- document still cited by a published version?" cannot be asked of the
-- documents themselves. This index answers it in one query, exactly as
-- version_result_type_ref does for result types — same reasoning, copied
-- deliberately rather than reinvented:
--
-- Written at publish, inside the publish transaction, from the document's own
-- documents[] array — one row per distinct document id it cites, however many
-- questions cite it. Never for a draft. A published version is never deleted,
-- so rows only accumulate.
--
-- No foreign key to procedure_document: the guard this table exists to serve
-- is what protects the document, not the database. No row-level security: an
-- organization admin deleting an organization-wide document must be able to
-- see that a property's procedure is citing it, and a property-filtered count
-- would hide exactly the usage that matters. No backfill: nothing published
-- before this table existed cited anything by this mechanism.
--
-- DDL is identical to db/migration/V10__procedure_documents.sql; keep the two in step.

CREATE TABLE version_document_ref (
    version_id  UUID NOT NULL REFERENCES procedure_template_version (id),
    document_id UUID NOT NULL,
    CONSTRAINT pk_version_document_ref PRIMARY KEY (version_id, document_id)
);

CREATE INDEX idx_vdr_document_id ON version_document_ref (document_id);
