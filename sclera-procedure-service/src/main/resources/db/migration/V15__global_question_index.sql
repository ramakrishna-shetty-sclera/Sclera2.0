-- A search index over the questions of every published global version, so the
-- Sclera-wide library can be searched by what it asks and not only by what a
-- template is called.
--
-- Deliberately NOT written twice, for the same reason as the three global
-- tables in V11: this is Sclera's own shared content, visible to every
-- organization, so there is no tenant copy and no db/tenant file. Do not add one.
--
-- Entities mapped onto it must be schema-qualified (@Table(schema = "public")),
-- because a per-request connection's search_path is pinned to the caller's own
-- tenant schema with no public fallback.
--
-- definition_json is TEXT and kept byte for byte for the hash, so it cannot be
-- queried. This table is the "question_index side table" the schema plan (A4)
-- said could be added later without breaking anything. It is a copy of what is
-- already in the version and can always be rebuilt from it; it is written only
-- by the listener on the global-template-events topic, never by an endpoint.
--
-- section_text is the heading the question sits under, taken from the document
-- when the row is written, so a search result can say "under: Fire safety"
-- without a second query. It is null for a question above the first heading.
-- Follow-up questions are indexed under their parent's heading.

CREATE TABLE global_question_index (
    id                 UUID          PRIMARY KEY,
    global_version_id  UUID          NOT NULL REFERENCES global_procedure_template_version (id),
    global_template_id UUID          NOT NULL REFERENCES global_procedure_template (id),
    question_key       VARCHAR(20)   NOT NULL,
    text               VARCHAR(1000) NOT NULL,
    standard           VARCHAR(50),
    section_text       VARCHAR(1000),
    CONSTRAINT uq_global_question_index UNIQUE (global_version_id, question_key)
);

CREATE INDEX idx_global_question_index_template ON global_question_index (global_template_id);

CREATE INDEX idx_global_question_index_text
    ON global_question_index USING gin (to_tsvector('english', text));
