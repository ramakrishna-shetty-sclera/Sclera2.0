package com.sclera.applicationplane.inspection.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Checklist record — one fillable instance generated from a tagged procedure
 * (procedure × asset/location) under an inspection config, a reactive service
 * or a tagged-procedure link. Questions are rendered from the live procedure
 * template; only answers are stored here (keyed by questionId). Persisted in
 * the tenant's schema.
 */
@Entity
@Table(name = "checklist")
public class Checklist {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "config_id")
    private UUID configId;

    @Column(name = "config_name", length = 220)
    private String configName;

    @Column(name = "tagged_procedure_id")
    private UUID taggedProcedureId;

    @Column(name = "procedure_id", nullable = false)
    private UUID procedureId;

    @Column(name = "procedure_name", nullable = false, length = 200)
    private String procedureName;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 16)
    private TargetType targetType;   // nullable — procedure with no target

    @Column(name = "target_id")
    private UUID targetId;

    @Column(name = "target_name", length = 200)
    private String targetName;

    @Column(name = "assignee_email", nullable = false, length = 255)
    private String assigneeEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ChecklistStatus status = ChecklistStatus.TODO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ChecklistSource source = ChecklistSource.INSPECTION;

    @Column(name = "due_date")
    private OffsetDateTime dueDate;

    @Column(name = "check_in_required", nullable = false)
    private boolean checkInRequired;

    @Column(name = "check_in_at")
    private OffsetDateTime checkInAt;

    @Column(name = "check_out_at")
    private OffsetDateTime checkOutAt;

    @Column(name = "exception_reason", length = 1000)
    private String exceptionReason;

    // NOTE: no unique(checklist_id, question_id) on the answer table — the
    // save flow replaces the whole collection, and Hibernate flushes inserts
    // before deletes (the uq_inspection_question lesson).
    @ElementCollection
    @CollectionTable(name = "checklist_answer", joinColumns = @JoinColumn(name = "checklist_id"))
    private List<Answer> answers = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "checklist_history", joinColumns = @JoinColumn(name = "checklist_id"))
    @OrderColumn(name = "seq")
    private List<HistoryEntry> history = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "checklist_work_order", joinColumns = @JoinColumn(name = "checklist_id"))
    @Column(name = "work_order_id")
    private List<UUID> workOrderIds = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public void log(String action, String detail, OffsetDateTime at) {
        HistoryEntry e = new HistoryEntry();
        e.setAt(at);
        e.setAction(action);
        e.setDetail(detail);
        history.add(e);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public UUID getConfigId() { return configId; }
    public void setConfigId(UUID configId) { this.configId = configId; }
    public String getConfigName() { return configName; }
    public void setConfigName(String configName) { this.configName = configName; }
    public UUID getTaggedProcedureId() { return taggedProcedureId; }
    public void setTaggedProcedureId(UUID taggedProcedureId) { this.taggedProcedureId = taggedProcedureId; }
    public UUID getProcedureId() { return procedureId; }
    public void setProcedureId(UUID procedureId) { this.procedureId = procedureId; }
    public String getProcedureName() { return procedureName; }
    public void setProcedureName(String procedureName) { this.procedureName = procedureName; }
    public TargetType getTargetType() { return targetType; }
    public void setTargetType(TargetType targetType) { this.targetType = targetType; }
    public UUID getTargetId() { return targetId; }
    public void setTargetId(UUID targetId) { this.targetId = targetId; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public String getAssigneeEmail() { return assigneeEmail; }
    public void setAssigneeEmail(String assigneeEmail) { this.assigneeEmail = assigneeEmail; }
    public ChecklistStatus getStatus() { return status; }
    public void setStatus(ChecklistStatus status) { this.status = status; }
    public ChecklistSource getSource() { return source; }
    public void setSource(ChecklistSource source) { this.source = source; }
    public OffsetDateTime getDueDate() { return dueDate; }
    public void setDueDate(OffsetDateTime dueDate) { this.dueDate = dueDate; }
    public boolean isCheckInRequired() { return checkInRequired; }
    public void setCheckInRequired(boolean checkInRequired) { this.checkInRequired = checkInRequired; }
    public OffsetDateTime getCheckInAt() { return checkInAt; }
    public void setCheckInAt(OffsetDateTime checkInAt) { this.checkInAt = checkInAt; }
    public OffsetDateTime getCheckOutAt() { return checkOutAt; }
    public void setCheckOutAt(OffsetDateTime checkOutAt) { this.checkOutAt = checkOutAt; }
    public String getExceptionReason() { return exceptionReason; }
    public void setExceptionReason(String exceptionReason) { this.exceptionReason = exceptionReason; }
    public List<Answer> getAnswers() { return answers; }
    public List<HistoryEntry> getHistory() { return history; }
    public List<UUID> getWorkOrderIds() { return workOrderIds; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** One answered question. {@code failed} marks a failing outcome (⇒ whole checklist FAILED on submit). */
    @Embeddable
    public static class Answer {

        @Column(name = "question_id", nullable = false)
        private UUID questionId;

        @Column(name = "answer_value", columnDefinition = "text")
        private String value;

        @Column(nullable = false)
        private boolean failed;

        @Column(length = 2000)
        private String comment;

        public UUID getQuestionId() { return questionId; }
        public void setQuestionId(UUID questionId) { this.questionId = questionId; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public boolean isFailed() { return failed; }
        public void setFailed(boolean failed) { this.failed = failed; }
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
    }

    /** Audit line for the inspection history. */
    @Embeddable
    public static class HistoryEntry {

        @Column(name = "occurred_at", nullable = false)
        private OffsetDateTime at;

        @Column(nullable = false, length = 64)
        private String action;

        @Column(columnDefinition = "text")
        private String detail;

        public OffsetDateTime getAt() { return at; }
        public void setAt(OffsetDateTime at) { this.at = at; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getDetail() { return detail; }
        public void setDetail(String detail) { this.detail = detail; }
    }
}
