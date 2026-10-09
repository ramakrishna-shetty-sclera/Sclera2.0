-- The library's star (guide §9). A join table and nothing more: which
-- templates a user has starred, in this organization's own schema.
--
-- No row-level security: a favourite belongs to a user, not a property, and
-- a user's own favourites should follow them regardless of which property
-- they are currently standing in.
--
-- DDL is identical to db/migration/V13__procedure_favourite.sql; keep the two
-- in step.

CREATE TABLE procedure_favourite (
    user_id     UUID        NOT NULL,
    template_id UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_procedure_favourite PRIMARY KEY (user_id, template_id)
);

CREATE INDEX idx_procedure_favourite_template ON procedure_favourite (template_id);
