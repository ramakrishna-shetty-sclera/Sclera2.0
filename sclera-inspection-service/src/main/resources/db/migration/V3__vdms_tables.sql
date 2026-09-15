-- VDMS inspection-module tables (configs, tagging, checklists, reactive
-- services, tagged-procedure links) — previously in-memory, now persisted
-- per tenant schema. org_id everywhere is defense-in-depth only; isolation
-- comes from the schema.

CREATE TABLE inspection_config (
    id                       UUID PRIMARY KEY,
    org_id                   UUID         NOT NULL,
    name                     VARCHAR(200) NOT NULL,
    code                     VARCHAR(50),
    description              VARCHAR(2000),
    assignee_email           VARCHAR(255) NOT NULL,
    secondary_assignee_email VARCHAR(255),
    category                 VARCHAR(100),
    priority                 VARCHAR(20),
    frequency                VARCHAR(20)  NOT NULL,
    schedule_days            VARCHAR(100) NOT NULL DEFAULT '',
    bypass_scan              BOOLEAN      NOT NULL DEFAULT FALSE,
    enable_check_in_out      BOOLEAN      NOT NULL DEFAULT FALSE,
    enable_points            BOOLEAN      NOT NULL DEFAULT FALSE,
    merged_view              BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by               UUID,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_inspection_config_org ON inspection_config (org_id);

CREATE TABLE inspection_tagging (
    config_id UUID PRIMARY KEY
);

CREATE TABLE tagging_procedure (
    id             UUID PRIMARY KEY,
    config_id      UUID         NOT NULL REFERENCES inspection_tagging (config_id) ON DELETE CASCADE,
    procedure_id   UUID         NOT NULL,
    procedure_name VARCHAR(200) NOT NULL
);
CREATE INDEX idx_tagging_procedure_config ON tagging_procedure (config_id);

CREATE TABLE tagging_target (
    id                   UUID PRIMARY KEY,
    tagging_procedure_id UUID         NOT NULL REFERENCES tagging_procedure (id) ON DELETE CASCADE,
    target_type          VARCHAR(16)  NOT NULL,
    target_id            UUID         NOT NULL,
    target_name          VARCHAR(200) NOT NULL,
    target_condition     VARCHAR(1000),
    assignee_email       VARCHAR(255)
);
CREATE INDEX idx_tagging_target_procedure ON tagging_target (tagging_procedure_id);

CREATE TABLE tagging_outer_condition (
    id                UUID PRIMARY KEY,
    config_id         UUID          NOT NULL REFERENCES inspection_tagging (config_id) ON DELETE CASCADE,
    description       VARCHAR(1000) NOT NULL,
    email_alert       BOOLEAN       NOT NULL DEFAULT FALSE,
    create_work_order BOOLEAN       NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_tagging_outer_condition_config ON tagging_outer_condition (config_id);

CREATE TABLE checklist (
    id                  UUID PRIMARY KEY,
    org_id              UUID         NOT NULL,
    config_id           UUID,
    config_name         VARCHAR(220),
    tagged_procedure_id UUID,
    procedure_id        UUID         NOT NULL,
    procedure_name      VARCHAR(200) NOT NULL,
    target_type         VARCHAR(16),
    target_id           UUID,
    target_name         VARCHAR(200),
    assignee_email      VARCHAR(255) NOT NULL,
    status              VARCHAR(24)  NOT NULL,
    source              VARCHAR(24)  NOT NULL,
    due_date            TIMESTAMPTZ,
    check_in_required   BOOLEAN      NOT NULL DEFAULT FALSE,
    check_in_at         TIMESTAMPTZ,
    check_out_at        TIMESTAMPTZ,
    exception_reason    VARCHAR(1000),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_checklist_org ON checklist (org_id);
CREATE INDEX idx_checklist_org_status ON checklist (org_id, status);
CREATE INDEX idx_checklist_org_source ON checklist (org_id, source);
CREATE INDEX idx_checklist_config ON checklist (config_id);
CREATE INDEX idx_checklist_target ON checklist (target_type, target_id);

-- Deliberately NO unique(checklist_id, question_id): the save flow replaces the
-- whole collection and Hibernate flushes inserts before deletes.
CREATE TABLE checklist_answer (
    checklist_id UUID NOT NULL REFERENCES checklist (id) ON DELETE CASCADE,
    question_id  UUID NOT NULL,
    answer_value TEXT,
    failed       BOOLEAN NOT NULL DEFAULT FALSE,
    comment      VARCHAR(2000)
);
CREATE INDEX idx_checklist_answer_checklist ON checklist_answer (checklist_id);

CREATE TABLE checklist_history (
    checklist_id UUID        NOT NULL REFERENCES checklist (id) ON DELETE CASCADE,
    seq          INT         NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,
    action       VARCHAR(64) NOT NULL,
    detail       TEXT
);
CREATE INDEX idx_checklist_history_checklist ON checklist_history (checklist_id);

CREATE TABLE checklist_work_order (
    checklist_id  UUID NOT NULL REFERENCES checklist (id) ON DELETE CASCADE,
    work_order_id UUID NOT NULL
);
CREATE INDEX idx_checklist_work_order_checklist ON checklist_work_order (checklist_id);

CREATE TABLE reactive_service (
    id             UUID PRIMARY KEY,
    org_id         UUID         NOT NULL,
    name           VARCHAR(220) NOT NULL,
    procedure_id   UUID         NOT NULL,
    procedure_name VARCHAR(200) NOT NULL,
    location_id    UUID         NOT NULL,
    location_name  VARCHAR(200) NOT NULL,
    qr_token       VARCHAR(64)  NOT NULL UNIQUE,
    created_by     UUID,
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_reactive_service_org ON reactive_service (org_id);

CREATE TABLE tagged_procedure_link (
    id             UUID PRIMARY KEY,
    org_id         UUID         NOT NULL,
    procedure_id   UUID         NOT NULL,
    procedure_name VARCHAR(200) NOT NULL,
    target_type    VARCHAR(16)  NOT NULL,
    target_id      UUID         NOT NULL,
    target_name    VARCHAR(200) NOT NULL,
    created_by     UUID,
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_tagged_procedure_link_org ON tagged_procedure_link (org_id);
CREATE INDEX idx_tagged_procedure_link_target ON tagged_procedure_link (target_type, target_id);
