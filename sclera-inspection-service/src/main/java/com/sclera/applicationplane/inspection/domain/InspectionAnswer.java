package com.sclera.applicationplane.inspection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "inspection_answer",
        uniqueConstraints = @UniqueConstraint(columnNames = {"inspection_id", "question_id"}))
public class InspectionAnswer {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", nullable = false)
    private Inspection inspection;

    /** Question id from the template snapshot. */
    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    /** Typed answer as JSON: "text", 42, true, ["a","b"], {"photoUrl": "..."} ... */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer_value", columnDefinition = "jsonb")
    private String answerValue;

    @Column
    private Integer score;

    @Column(length = 2000)
    private String comment;

    public UUID getId() { return id; }
    public Inspection getInspection() { return inspection; }
    public void setInspection(Inspection inspection) { this.inspection = inspection; }
    public UUID getQuestionId() { return questionId; }
    public void setQuestionId(UUID questionId) { this.questionId = questionId; }
    public String getAnswerValue() { return answerValue; }
    public void setAnswerValue(String answerValue) { this.answerValue = answerValue; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
