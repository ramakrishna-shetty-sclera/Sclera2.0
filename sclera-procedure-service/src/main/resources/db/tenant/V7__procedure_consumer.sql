-- Who a procedure is for: the consumers a procedure_template.consumer_key may name.
--
-- consumer_key has existed since the first template migration, NOT NULL DEFAULT
-- 'INSPECTION', and was validated against nothing: any string up to fifty
-- characters was a consumer. This is the vocabulary it is checked against.
--
-- A table and not an enum, because the second consumer's name is not settled,
-- and an organization-level list is what lets it change without a release.
--
-- There is no foreign key from procedure_template.consumer_key to this table.
-- The key is validated when a procedure is created and never changes after, so a
-- consumer retired later must not make existing procedures unreadable.
--
-- Not property-scoped, so no row-level security: a consumer is an organization's
-- vocabulary, not one property's.
---- DDL is identical to db/migration/V9__procedure_consumer.sql (which has no seed: public holds
-- no tenant data and exists only so hibernate's validate finds the table); keep the two in step.

CREATE TABLE procedure_consumer (
    -- The stable identity procedure_template.consumer_key stores, so it is never
    -- reused or renamed. The same shape as a result-type key.
    key    VARCHAR(50)  PRIMARY KEY,
    name   VARCHAR(100) NOT NULL,
    -- Retires a consumer from new use without breaking a procedure that already
    -- belongs to it, the way a deactivated result type behaves.
    active BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_procedure_consumer_key CHECK (key ~ '^[A-Z][A-Z0-9_]*$')
);
-- INSPECTION first, and it must stay: every existing procedure carries it, from
-- the column default. A seed that left it out would invalidate every procedure
-- already authored. TASK is the second, with its name still unsettled.
INSERT INTO procedure_consumer (key, name) VALUES
    ('INSPECTION', 'Inspection'),
    ('TASK',       'Task');