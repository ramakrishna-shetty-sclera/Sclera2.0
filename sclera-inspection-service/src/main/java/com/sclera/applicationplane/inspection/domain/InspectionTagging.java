package com.sclera.applicationplane.inspection.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tagging aggregate for one inspection config: the procedures tagged to it
 * (each with asset/location targets carrying their own condition + assignee)
 * plus inspection-wide "outer" conditions that trigger email alerts /
 * work-order creation. Persisted in the tenant's schema.
 */
@Entity
@Table(name = "inspection_tagging")
public class InspectionTagging {

    @Id
    @Column(name = "config_id")
    private UUID configId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "config_id", nullable = false)
    private List<TaggedProcedure> taggedProcedures = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "config_id", nullable = false)
    private List<OuterCondition> outerConditions = new ArrayList<>();

    protected InspectionTagging() {
        // JPA
    }

    public InspectionTagging(UUID configId) {
        this.configId = configId;
    }

    public UUID getConfigId() { return configId; }
    public List<TaggedProcedure> getTaggedProcedures() { return taggedProcedures; }
    public List<OuterCondition> getOuterConditions() { return outerConditions; }

    /** A procedure (checklist) tagged to this inspection, with its targets. */
    @Entity
    @Table(name = "tagging_procedure")
    public static class TaggedProcedure {

        @Id
        private UUID id;

        @Column(name = "procedure_id", nullable = false)
        private UUID procedureId;

        @Column(name = "procedure_name", nullable = false, length = 200)
        private String procedureName;

        @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
        @JoinColumn(name = "tagging_procedure_id", nullable = false)
        private List<ProcedureTarget> targets = new ArrayList<>();

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public UUID getProcedureId() { return procedureId; }
        public void setProcedureId(UUID procedureId) { this.procedureId = procedureId; }
        public String getProcedureName() { return procedureName; }
        public void setProcedureName(String procedureName) { this.procedureName = procedureName; }
        public List<ProcedureTarget> getTargets() { return targets; }
    }

    /** An asset or location a tagged procedure applies to, with its own condition + assignee. */
    @Entity
    @Table(name = "tagging_target")
    public static class ProcedureTarget {

        @Id
        private UUID id;

        @Enumerated(EnumType.STRING)
        @Column(name = "target_type", nullable = false, length = 16)
        private TargetType targetType;

        @Column(name = "target_id", nullable = false)
        private UUID targetId;

        @Column(name = "target_name", nullable = false, length = 200)
        private String targetName;

        @Column(name = "target_condition", length = 1000)
        private String condition;

        @Column(name = "assignee_email", length = 255)
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
    @Entity
    @Table(name = "tagging_outer_condition")
    public static class OuterCondition {

        @Id
        private UUID id;

        @Column(nullable = false, length = 1000)
        private String description;

        @Column(name = "email_alert", nullable = false)
        private boolean emailAlert;

        @Column(name = "create_work_order", nullable = false)
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
