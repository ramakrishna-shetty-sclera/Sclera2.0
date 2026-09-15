package com.sclera.applicationplane.inspection.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dummy in-memory checklist record — one fillable instance generated from a
 * tagged procedure (procedure × asset/location) under an inspection config.
 * Questions are rendered from the live procedure template; only answers are
 * stored here (keyed by questionId).
 */
public class Checklist {

    private UUID id;
    private UUID orgId;

    private UUID configId;
    private String configName;

    private UUID taggedProcedureId;
    private UUID procedureId;
    private String procedureName;

    private TargetType targetType;   // nullable — procedure with no target
    private UUID targetId;
    private String targetName;

    private String assigneeEmail;
    private ChecklistStatus status = ChecklistStatus.TODO;

    private OffsetDateTime dueDate;
    private boolean checkInRequired;
    private OffsetDateTime checkInAt;
    private OffsetDateTime checkOutAt;
    private String exceptionReason;

    private final List<Answer> answers = new ArrayList<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private final List<UUID> workOrderIds = new ArrayList<>();

    private OffsetDateTime createdAt;
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
    public static class Answer {
        private UUID questionId;
        private String value;
        private boolean failed;
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
    public static class HistoryEntry {
        private OffsetDateTime at;
        private String action;
        private String detail;

        public OffsetDateTime getAt() { return at; }
        public void setAt(OffsetDateTime at) { this.at = at; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getDetail() { return detail; }
        public void setDetail(String detail) { this.detail = detail; }
    }
}
