-- AI-assisted question generation: uploaded source documents, generation jobs,
-- chat sessions, and the generated candidate questions awaiting review.
--
-- A candidate (generated_question) holds ONE question in a key-less JSON shape
-- of its own (CandidateQuestion): question keys are minted by the template's
-- key_seq only when the author imports the candidate into the draft, never by
-- the LLM. question_json is TEXT like definition_json, and stores only content
-- the structure validator has already accepted — unvalidated LLM output is
-- never persisted.
--
-- Candidates reference their origin twice over: source says which feature made
-- them (DOCUMENT or CHAT), source_ref points at the generation_job or
-- chat_session, and source_span/turn_no pin down the text span or conversation
-- turn they came from.
--
-- Import is idempotent: an IMPORTED candidate keeps the key it was given
-- (imported_key) and re-importing it is a no-op, so retrying a failed request
-- cannot add the same question twice.
--
-- No row-level security: rows are reached only through org-scoped queries (and
-- live in the org's tenant schema); property scoping follows the template.
--
-- DDL is identical to db/tenant/V12__ai_question_generation.sql; keep the two in step.

CREATE TABLE ai_document (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         UUID          NOT NULL,
    filename       VARCHAR(255)  NOT NULL,
    media_type     VARCHAR(100)  NOT NULL,
    size_bytes     BIGINT        NOT NULL,
    content        BYTEA         NOT NULL,
    -- Extracted once; generation re-runs read this, never re-extract.
    extracted_text TEXT,
    status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    error          VARCHAR(2000),
    created_by     UUID,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_document_status CHECK (status IN ('PENDING', 'EXTRACTING', 'READY', 'FAILED'))
);

CREATE INDEX idx_ai_document_org ON ai_document (org_id);

CREATE TABLE generation_job (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID         NOT NULL,
    template_id   UUID         NOT NULL REFERENCES procedure_template (id),
    document_id   UUID         NOT NULL REFERENCES ai_document (id),
    status        VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    error         VARCHAR(2000),
    -- Which model served it and what it cost; the model is per-job on purpose.
    model         VARCHAR(100),
    input_tokens  BIGINT       NOT NULL DEFAULT 0,
    output_tokens BIGINT       NOT NULL DEFAULT 0,
    created_by    UUID,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    CONSTRAINT chk_generation_job_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_generation_job_org      ON generation_job (org_id);
CREATE INDEX idx_generation_job_template ON generation_job (template_id);

CREATE TABLE chat_session (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID         NOT NULL,
    template_id UUID         NOT NULL REFERENCES procedure_template (id),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_by  UUID,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_chat_session_status CHECK (status IN ('ACTIVE', 'CLOSED'))
);

CREATE INDEX idx_chat_session_org      ON chat_session (org_id);
CREATE INDEX idx_chat_session_template ON chat_session (template_id);

CREATE TABLE chat_message (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     UUID         NOT NULL,
    session_id UUID         NOT NULL REFERENCES chat_session (id),
    role       VARCHAR(20)  NOT NULL,
    content    TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_chat_message_role CHECK (role IN ('USER', 'ASSISTANT'))
);

CREATE INDEX idx_chat_message_session ON chat_message (session_id, created_at);

CREATE TABLE generated_question (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           UUID         NOT NULL,
    template_id      UUID         NOT NULL REFERENCES procedure_template (id),
    source           VARCHAR(20)  NOT NULL,
    -- generation_job.id or chat_session.id, depending on source. No FK: the
    -- two sources live in different tables and the row must survive either.
    source_ref       UUID         NOT NULL,
    -- The sentence/phrase of the document this came from, or the chat ask.
    source_span      VARCHAR(500),
    -- Which conversation turn produced it (chat only).
    turn_no          INT,
    question_json    TEXT         NOT NULL,
    confidence       DOUBLE PRECISION,
    -- A similar question already in this organization's templates, as JSON
    -- ({templateId, templateName, questionKey, text}), so the reviewer can
    -- reuse instead of duplicate. Null when nothing similar was found.
    similar_existing VARCHAR(2000),
    status           VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    -- The question key minted at import; proof of (and pointer to) the import.
    imported_key     VARCHAR(20),
    imported_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_generated_question_source CHECK (source IN ('DOCUMENT', 'CHAT')),
    CONSTRAINT chk_generated_question_status CHECK (status IN ('DRAFT', 'IMPORTED', 'DISCARDED')),
    CONSTRAINT chk_generated_question_imported CHECK ((status = 'IMPORTED') = (imported_at IS NOT NULL))
);

CREATE INDEX idx_generated_question_template ON generated_question (template_id, status);
CREATE INDEX idx_generated_question_source   ON generated_question (source_ref);
