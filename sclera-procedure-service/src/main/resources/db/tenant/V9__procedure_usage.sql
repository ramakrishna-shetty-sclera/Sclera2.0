-- Who is using which version of a procedure, as the consumers last reported it.
--
-- A report, not a pointer. A consumer (an inspection configuration, a future
-- task service) tells this service "I am configured against version V of
-- template T", and that fact sits here until the consumer says otherwise. It is
-- never kept in step automatically: the consumer owns when it reports, the same
-- as every other cross-service fact. This is what makes "before you publish v4,
-- here are the 12 configurations still on v3" answerable, so version_id is
-- whatever the consumer last reported and deliberately not the current version.
--
-- One row per (consumer, its own reference, template). Reporting the same fact
-- again is an update in place, not a new row, so a configuration that moves from
-- v3 to v4 reads as one row changing rather than two rows existing. The unique
-- constraint is the whole mechanism; the service upserts against it.
--
-- consumer_ref_id is the consumer's own id for whatever is using the procedure,
-- opaque to this service and kept as text because it is not ours to interpret.
--
-- No foreign keys: not to procedure_consumer, because this table outlives a
-- consumer type being retired, and not to the template or version, because the
-- ids arrive from another service and this table is a record of what was
-- reported. A published version is never deleted, so nothing here can dangle.
--
-- No row-level security, deliberately. Usage is reported by other services, not
-- by a property-scoped browser session, and an organization admin needs every
-- property's consumers to get an honest impact preview. The table holds ids and
-- keys, never content.
--
-- No backfill: nothing reported before the table existed is counted.
-- DDL is identical to db/migration/V11__procedure_usage.sql; keep the two in step.

CREATE TABLE procedure_usage (
    id              UUID         NOT NULL PRIMARY KEY,
    template_id     UUID         NOT NULL,
    version_id      UUID         NOT NULL,
    consumer_key    VARCHAR(50)  NOT NULL,
    consumer_ref_id VARCHAR(100) NOT NULL,
    -- Which target type the consumer bound the procedure to, if it bound one at
    -- all. The consumer already knows what kind of target it configured, so the
    -- key alone is enough here, unlike version_target_type's (kind, key).
    target_type_key VARCHAR(50),
    -- When the consumer last reported. Not when the row was first made: a
    -- re-report is an update.
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_procedure_usage UNIQUE (consumer_key, consumer_ref_id, template_id)
);

-- The impact preview asks "which consumers are on each version of this template?".
CREATE INDEX idx_procedure_usage_template ON procedure_usage (template_id, version_id);
