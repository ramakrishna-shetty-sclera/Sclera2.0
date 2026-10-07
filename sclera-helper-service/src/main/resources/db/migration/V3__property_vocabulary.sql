-- The property vocabulary tables, so hibernate's validate finds them in the
-- default (public) schema. Tenant data lives in the per-tenant schemas
-- (db/tenant/V2__property_vocabulary.sql), which is also where the seed is: this
-- copy deliberately holds no rows. See that file for what the tables are.
--
-- DDL is identical to db/tenant/V2__property_vocabulary.sql; keep the two in step.
CREATE TABLE hierarchy_level (
    key           VARCHAR(50)  PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    display_order INTEGER      NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_hierarchy_level_key CHECK (key ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE TABLE location_type (
    key           VARCHAR(50)  PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    display_order INTEGER      NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_location_type_key CHECK (key ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE TABLE asset_class (
    key           VARCHAR(50)  PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    display_order INTEGER      NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_asset_class_key CHECK (key ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE TABLE asset_tag (
    key           VARCHAR(50)  PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    display_order INTEGER      NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_asset_tag_key CHECK (key ~ '^[A-Z][A-Z0-9_]*$')
);
