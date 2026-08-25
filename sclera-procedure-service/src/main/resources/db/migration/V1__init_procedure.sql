-- sclera-procedure-service: question template schema

CREATE TABLE question_template (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID         NOT NULL,
    name          VARCHAR(200) NOT NULL,
    description   VARCHAR(2000),
    category      VARCHAR(100),
    status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    version       INT          NOT NULL DEFAULT 0,
    created_by    UUID,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_template_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);

CREATE INDEX idx_question_template_org ON question_template (org_id);
CREATE INDEX idx_question_template_org_status ON question_template (org_id, status);

CREATE TABLE template_section (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id   UUID         NOT NULL REFERENCES question_template (id) ON DELETE CASCADE,
    title         VARCHAR(200) NOT NULL,
    display_order INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_template_section_template ON template_section (template_id);

CREATE TABLE question (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    section_id    UUID          NOT NULL REFERENCES template_section (id) ON DELETE CASCADE,
    text          VARCHAR(1000) NOT NULL,
    help_text     VARCHAR(1000),
    type          VARCHAR(20)   NOT NULL,
    required      BOOLEAN       NOT NULL DEFAULT FALSE,
    display_order INT           NOT NULL DEFAULT 0,
    options       JSONB,
    score_weight  INT,
    CONSTRAINT chk_question_type CHECK (type IN
        ('TEXT', 'NUMBER', 'BOOLEAN', 'SINGLE_CHOICE', 'MULTI_CHOICE', 'DATE', 'PHOTO', 'SIGNATURE'))
);

CREATE INDEX idx_question_section ON question (section_id);
