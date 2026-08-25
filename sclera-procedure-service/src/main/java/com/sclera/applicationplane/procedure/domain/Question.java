package com.sclera.applicationplane.procedure.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "question")
public class Question {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "section_id", nullable = false)
    private TemplateSection section;

    @Column(nullable = false, length = 1000)
    private String text;

    @Column(name = "help_text", length = 1000)
    private String helpText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuestionType type;

    @Column(nullable = false)
    private boolean required;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** JSON array of options for SINGLE_CHOICE / MULTI_CHOICE, e.g. ["Pass","Fail","N/A"]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String options;

    @Column(name = "score_weight")
    private Integer scoreWeight;

    public UUID getId() { return id; }
    public TemplateSection getSection() { return section; }
    public void setSection(TemplateSection section) { this.section = section; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getHelpText() { return helpText; }
    public void setHelpText(String helpText) { this.helpText = helpText; }
    public QuestionType getType() { return type; }
    public void setType(QuestionType type) { this.type = type; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }
    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }
    public Integer getScoreWeight() { return scoreWeight; }
    public void setScoreWeight(Integer scoreWeight) { this.scoreWeight = scoreWeight; }
}
