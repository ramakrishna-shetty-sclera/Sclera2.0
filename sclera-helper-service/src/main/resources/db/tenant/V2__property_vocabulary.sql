-- The property vocabulary: the four lists a procedure's target types are drawn
-- from. A procedure that applies to "asset class EXTINGUISHER" names a key from
-- one of these; the procedure service asks this service whether the key exists
-- and does not copy the lists.
--
--   hierarchy_level  where in the property tree  BUILDING, FLOOR, ...
--   location_type    what kind of place          ROOM, CORRIDOR, ...
--   asset_class      what kind of thing          EXTINGUISHER, ...
--   asset_tag        a free grouping an org uses
--
-- One schema per organization is the organization boundary, so there is no
-- org_id column: each organization has its own rows and extends its own lists.
--
-- key is the stable identity other services store, so it is never reused and
-- never renamed; name is what a person reads and may change. active hides a
-- key from new use without breaking a procedure that already names it, the same
-- way a deactivated result type behaves.
--
-- Seeded here, per organization: the two lists the product defines.
-- hierarchy_level and location_type are fixed vocabulary; asset_class and
-- asset_tag are whatever the organization uses, so they start empty.
--
-- DDL is identical to db/migration/V3__property_vocabulary.sql (which has no
-- seed: public holds no tenant data and exists only so hibernate's validate
-- finds the tables); keep the two in step.

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

INSERT INTO hierarchy_level (key, name, display_order) VALUES
    ('BUILDING',  'Building',  1),
    ('FLOOR',     'Floor',     2),
    ('LOCATION',  'Location',  3),
    ('ASSET',     'Asset',     4),
    ('SUB_ASSET', 'Sub-asset', 5);

INSERT INTO location_type (key, name, display_order) VALUES
    ('ROOM',       'Room',       1),
    ('CORRIDOR',   'Corridor',   2),
    ('OPEN_AREA',  'Open area',  3),
    ('PLANT_ROOM', 'Plant room', 4);
