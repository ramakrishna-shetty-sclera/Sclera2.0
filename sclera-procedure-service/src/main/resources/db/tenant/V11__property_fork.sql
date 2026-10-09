-- A property's own fork of an organization-wide procedure — the same
-- fork-on-edit / update-notify-apply-defer pattern feature 10/11 already
-- built one level up (Sclera -> organization), one level down
-- (organization -> property).
--
-- All five columns are nullable, and all five are null on every row unless
-- that row is itself a property-level fork:
--
--   forked_from_template_id  which org-wide procedure_template row (same
--                             org, property_id IS NULL) this fork came from.
--                             Self-referencing within this same table, never
--                             a cross-schema or cross-service reference.
--   applied_version_no       the parent's version number this fork last
--                             applied — compared against the parent's own
--                             current_published_version_id to derive
--                             "update available", the same way
--                             global_template_org_copy.applied_version_no
--                             already does one level up.
--   link_state                LinkState: LINKED | DEFERRED | STANDALONE.
--                             Reused unmodified from the global link table —
--                             the three states mean the same thing here.
--   deferred_version_no, deferred_at   feature 11's defer-update, extended
--                             to this level, writes these; nothing else does.
--
-- Deliberately plain columns on this table, not a second link table like
-- global_template_org_copy. That table exists separately because
-- (a) it must be reachable from every organization's schema-less corner of
-- `public`, which has no bearing here, and (b) re-importing "as new" can
-- repoint which org template a (global template, org) pair tracks, which a
-- property's fork has no equivalent of: a fork IS its own row from the
-- moment it exists, and nothing re-points which row a given fork "is".
--
-- DDL is identical to db/migration/V15__property_fork.sql; keep the two in step.

ALTER TABLE procedure_template
    ADD COLUMN forked_from_template_id UUID,
    ADD COLUMN applied_version_no      INTEGER,
    ADD COLUMN link_state              VARCHAR(20),
    ADD COLUMN deferred_version_no     INTEGER,
    ADD COLUMN deferred_at             TIMESTAMPTZ;

COMMENT ON COLUMN procedure_template.forked_from_template_id IS
    'The organization-wide procedure_template row (same org) this property-level fork came from. NULL for everything else.';

-- Resolving "which forks track this org template" (the update-lifecycle's
-- linkedForkCount, and fork-on-edit) is the one query this column serves
-- besides direct lookup by this row's own id.
CREATE INDEX idx_procedure_template_forked_from
    ON procedure_template (forked_from_template_id) WHERE forked_from_template_id IS NOT NULL;

-- A real bug found while building fork, not a drive-by cleanup: the name
-- uniqueness index from V3/V4 was written before property_id existed and
-- never revisited, so it blocked the exact case forking requires -- a
-- property's fork keeping the org-wide source's own name. Widened to scope
-- uniqueness per (org, property, name) instead of per (org, name).
--
-- NULLS NOT DISTINCT is what keeps the org-wide behaviour correct: standard
-- SQL treats every NULL as distinct from every other NULL, which would let
-- two different org-wide templates share a name (both property_id IS NULL)
-- -- exactly the duplicate this index exists to prevent. With it, two
-- property_id IS NULL rows collide like any other equal value would; two
-- different non-null property ids never collide with each other regardless,
-- since actual UUIDs are simply unequal.
DROP INDEX uq_procedure_template_org_name_active;

CREATE UNIQUE INDEX uq_procedure_template_org_name_active
    ON procedure_template (org_id, property_id, lower(name))
    NULLS NOT DISTINCT
    WHERE status = 'ACTIVE';
