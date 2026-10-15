-- A separate role for serving requests. `sclera` keeps owning the tables and
-- running migrations; the application connects as this one.
--
-- Why this has to exist before row-level security is worth anything: Postgres
-- ignores RLS for a table's owner and for superusers, and `sclera` is both
-- (verified: rolsuper=true, rolbypassrls=true, and it owns every table). A
-- policy written while the application connects as `sclera` is inert — it sits
-- on the table doing nothing, and every isolation test passes while proving
-- nothing at all. Property isolation starts working the moment requests arrive
-- on a role that is neither owner nor superuser, and not before.
--
-- Created here rather than by hand so every environment gets it the same way:
-- an existing dev database, a fresh volume, a Testcontainers instance, and
-- production — where infrastructure normally creates the role first, which
-- makes the block below a no-op.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sclera_app') THEN
        -- Deliberately plain: no SUPERUSER, no BYPASSRLS, no CREATEDB, no
        -- CREATEROLE. Any of those would quietly undo the point of the role.
        CREATE ROLE sclera_app LOGIN PASSWORD 'sclera_app';
    END IF;
END $$;

-- The database name differs per environment (SCLERA_DB_NAME), so ask rather
-- than assume.
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO sclera_app', current_database());
END $$;

GRANT USAGE ON SCHEMA public TO sclera_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO sclera_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO sclera_app;

-- ON ALL TABLES above covers what exists now; this covers what later migrations
-- add. Without it, every new table is invisible to the application until
-- someone remembers to grant it by hand — which they will not.
--
-- No FOR ROLE clause, so this applies to objects created by the role running
-- this migration, which is the owner. That is exactly who creates them.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sclera_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO sclera_app;

-- Tenant schemas are created at runtime, so their grants cannot live in a
-- migration. TenantRegistryService applies the same set after provisioning and
-- after every migration pass — see grantAppRole there.
