-- Topic selection before generation (docs/procedures-v3.html DocsTab): the
-- topics the LLM found in a document, cached like the extracted text so a
-- later re-generation reuses them without another LLM call. topics_status
-- mirrors the text lifecycle: PENDING (never requested) -> EXTRACTING ->
-- READY | FAILED.
ALTER TABLE ai_document
    ADD COLUMN topics_json   TEXT,
    ADD COLUMN topics_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN topics_error  VARCHAR(2000);
