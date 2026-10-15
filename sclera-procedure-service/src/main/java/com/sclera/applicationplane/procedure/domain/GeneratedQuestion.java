package com.sclera.applicationplane.procedure.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One AI-generated candidate question awaiting review. {@code questionJson}
 * holds the key-less CandidateQuestion shape, already structure-validated —
 * never raw LLM output. Keys are minted only when the author imports it, and
 * the minted key is kept here so a second import of the same candidate is a
 * visible no-op rather than a duplicate question.
 */
@Entity
@Table(name = "generated_question")
public class GeneratedQuestion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private GeneratedQuestionSource source;

    @Column(name = "source_ref", nullable = false, updatable = false)
    private UUID sourceRef;

    @Column(name = "source_span", length = 500)
    private String sourceSpan;

    @Column(name = "turn_no")
    private Integer turnNo;

    @Column(name = "question_json", nullable = false, columnDefinition = "text")
    private String questionJson;

    @Column
    private Double confidence;

    @Column(name = "similar_existing", length = 2000)
    private String similarExisting;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GeneratedQuestionStatus status = GeneratedQuestionStatus.DRAFT;

    @Column(name = "imported_key", length = 20)
    private String importedKey;

    @Column(name = "imported_at")
    private OffsetDateTime importedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public void markImported(String key, OffsetDateTime at) {
        this.status = GeneratedQuestionStatus.IMPORTED;
        this.importedKey = key;
        this.importedAt = at;
    }

    public UUID getId() { return id; }

    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }

    public UUID getTemplateId() { return templateId; }
    public void setTemplateId(UUID templateId) { this.templateId = templateId; }

    public GeneratedQuestionSource getSource() { return source; }
    public void setSource(GeneratedQuestionSource source) { this.source = source; }

    public UUID getSourceRef() { return sourceRef; }
    public void setSourceRef(UUID sourceRef) { this.sourceRef = sourceRef; }

    public String getSourceSpan() { return sourceSpan; }
    public void setSourceSpan(String sourceSpan) { this.sourceSpan = sourceSpan; }

    public Integer getTurnNo() { return turnNo; }
    public void setTurnNo(Integer turnNo) { this.turnNo = turnNo; }

    public String getQuestionJson() { return questionJson; }
    public void setQuestionJson(String questionJson) { this.questionJson = questionJson; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getSimilarExisting() { return similarExisting; }
    public void setSimilarExisting(String similarExisting) { this.similarExisting = similarExisting; }

    public GeneratedQuestionStatus getStatus() { return status; }
    public void setStatus(GeneratedQuestionStatus status) { this.status = status; }

    public String getImportedKey() { return importedKey; }
    public OffsetDateTime getImportedAt() { return importedAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
