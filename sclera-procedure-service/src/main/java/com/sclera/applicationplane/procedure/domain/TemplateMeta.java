package com.sclera.applicationplane.procedure.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.Size;

/**
 * The descriptive fields of a procedure — how it is categorized, filed and
 * scheduled (docs/procedures-v3.html DetailsTab). One value object so the same
 * shape flows through the entity, the API and validation without copying
 * eight fields at every hop.
 *
 * All free text by design: category/standard/folder vocabularies are the
 * organization's to grow, so nothing here is an enum except availability,
 * which has exactly two meanings.
 */
@Embeddable
public record TemplateMeta(
        @Size(max = 50) @Column(length = 50) String category,
        @Size(max = 50) @Column(name = "sub_category", length = 50) String subCategory,
        @Size(max = 30) @Column(length = 30) String frequency,
        @Size(max = 20) @Column(length = 20) String priority,
        @Size(max = 30) @Column(length = 30) String code,
        @Size(max = 50) @Column(length = 50) String standard,
        @Enumerated(EnumType.STRING) @Column(length = 20) TemplateAvailability availability,
        @Size(max = 50) @Column(length = 50) String folder) {

    public static TemplateMeta empty() {
        return new TemplateMeta(null, null, null, null, null, null, null, null);
    }
}
