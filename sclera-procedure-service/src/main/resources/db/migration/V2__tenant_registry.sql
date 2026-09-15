-- Schema-per-tenant registry (lives in public; the ONLY shared table besides
-- flyway history). Tenant schemas are named t_<org-uuid-without-dashes> and
-- carry their own flyway history for the db/tenant migration set.

CREATE TABLE IF NOT EXISTS public.tenant_registry (
    org_id             UUID PRIMARY KEY,
    schema_name        VARCHAR(63) NOT NULL UNIQUE,
    status             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    copied_public_data BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
