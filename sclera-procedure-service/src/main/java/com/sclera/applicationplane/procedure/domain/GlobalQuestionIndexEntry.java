package com.sclera.applicationplane.procedure.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One question of a published global version, kept in its own row so the
 * library's question bank can be searched. A copy of what the version already
 * holds, written only when that version is published and rebuilt from it if
 * ever needed.
 *
 * <p>Schema-qualified for the same reason {@link GlobalProcedureTemplate} is:
 * the table lives in {@code public}, and an ordinary tenant-scoped connection's
 * search_path would never find an unqualified reference to it.
 */
@Entity
@Table(name = "global_question_index", schema = "public")
public class GlobalQuestionIndexEntry {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "global_version_id", nullable = false, updatable = false)
    private UUID globalVersionId;

    @Column(name = "global_template_id", nullable = false, updatable = false)
    private UUID globalTemplateId;

    @Column(name = "question_key", nullable = false, updatable = false, length = 20)
    private String questionKey;

    @Column(nullable = false, updatable = false, length = 1000)
    private String text;

    /** The standard the question came from, such as "NFPA 10". Often absent. */
    @Column(updatable = false, length = 50)
    private String standard;

    /** The heading it sits under, or null above the first heading. */
    @Column(name = "section_text", updatable = false, length = 1000)
    private String sectionText;

    protected GlobalQuestionIndexEntry() {
        // for JPA
    }

    public GlobalQuestionIndexEntry(UUID globalVersionId, UUID globalTemplateId, String questionKey, String text,
                                    String standard, String sectionText) {
        this.globalVersionId = globalVersionId;
        this.globalTemplateId = globalTemplateId;
        this.questionKey = questionKey;
        this.text = text;
        this.standard = standard;
        this.sectionText = sectionText;
    }

    public UUID getId() { return id; }
    public UUID getGlobalVersionId() { return globalVersionId; }
    public UUID getGlobalTemplateId() { return globalTemplateId; }
    public String getQuestionKey() { return questionKey; }
    public String getText() { return text; }
    public String getStandard() { return standard; }
    public String getSectionText() { return sectionText; }
}
