-- sclera-inspection-service: inspection execution schema

CREATE TABLE inspection (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            UUID         NOT NULL,
    template_id       UUID         NOT NULL,
    template_version  INT          NOT NULL,
    template_name     VARCHAR(200) NOT NULL,
    template_snapshot JSONB        NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    assignee_id       UUID,
    scheduled_for     TIMESTAMPTZ,
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    notes             VARCHAR(4000),
    created_by        UUID,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_inspection_status CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX idx_inspection_org ON inspection (org_id);
CREATE INDEX idx_inspection_org_status ON inspection (org_id, status);
CREATE INDEX idx_inspection_org_template ON inspection (org_id, template_id);
CREATE INDEX idx_inspection_assignee ON inspection (assignee_id);

CREATE TABLE inspection_answer (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inspection_id UUID NOT NULL REFERENCES inspection (id) ON DELETE CASCADE,
    question_id   UUID NOT NULL,
    answer_value  JSONB,
    score         INT,
    comment       VARCHAR(2000),
    CONSTRAINT uq_inspection_question UNIQUE (inspection_id, question_id)
);

CREATE INDEX idx_inspection_answer_inspection ON inspection_answer (inspection_id);
