package com.sclera.applicationplane.inspection.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dummy in-memory tagging aggregate for one inspection config: the procedures
 * tagged to it (each with asset/location targets carrying their own condition +
 * assignee) plus inspection-wide "outer" conditions that trigger email alerts /
 * work-order creation.
 */
public class InspectionTagging {

    private final UUID configId;
    private final List<TaggedProcedure> taggedProcedures = new ArrayList<>();
    private final List<OuterCondition> outerConditions = new ArrayList<>();

    public InspectionTagging(UUID configId) {
        this.configId = configId;
    }

    public UUID getConfigId() { return configId; }
    public List<TaggedProcedure> getTaggedProcedures() { return taggedProcedures; }
    public List<OuterCondition> getOuterConditions() { return outerConditions; }

    /** A procedure (checklist) tagged to this inspection, with its targets. */
    public static class TaggedProcedure {
        private UUID id;
        private UUID procedureId;
        private String procedureName;
        private final List<ProcedureTarget> targets = new ArrayList<>();

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public UUID getProcedureId() { return procedureId; }
        public void setProcedureId(UUID procedureId) { this.procedureId = procedureId; }
        public String getProcedureName() { return procedureName; }
        public void setProcedureName(String procedureName) { this.procedureName = procedureName; }
        public List<ProcedureTarget> getTargets() { return targets; }
    }

    /** An asset or location a tagged procedure applies to, with its own condition + assignee. */
    public static class ProcedureTarget {
        private UUID id;
        private TargetType targetType;
        private UUID targetId;
        private String targetName;
        private String condition;
        private String assigneeEmail;

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public TargetType getTargetType() { return targetType; }
        public void setTargetType(TargetType targetType) { this.targetType = targetType; }
        public UUID getTargetId() { return targetId; }
        public void setTargetId(UUID targetId) { this.targetId = targetId; }
        public String getTargetName() { return targetName; }
        public void setTargetName(String targetName) { this.targetName = targetName; }
        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }
        public String getAssigneeEmail() { return assigneeEmail; }
        public void setAssigneeEmail(String assigneeEmail) { this.assigneeEmail = assigneeEmail; }
    }

    /** Whole-inspection condition; triggers email alert and/or work-order creation. */
    public static class OuterCondition {
        private UUID id;
        private String description;
        private boolean emailAlert;
        private boolean createWorkOrder;

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public boolean isEmailAlert() { return emailAlert; }
        public void setEmailAlert(boolean emailAlert) { this.emailAlert = emailAlert; }
        public boolean isCreateWorkOrder() { return createWorkOrder; }
        public void setCreateWorkOrder(boolean createWorkOrder) { this.createWorkOrder = createWorkOrder; }
    }
}
