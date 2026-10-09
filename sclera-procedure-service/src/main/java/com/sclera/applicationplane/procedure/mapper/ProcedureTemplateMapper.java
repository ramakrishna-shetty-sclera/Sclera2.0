package com.sclera.applicationplane.procedure.mapper;

import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.TemplateResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionSummary;
import org.springframework.stereotype.Component;

@Component
public class ProcedureTemplateMapper {

    private final DefinitionCanonicalizer canonicalizer;

    public ProcedureTemplateMapper(DefinitionCanonicalizer canonicalizer) {
        this.canonicalizer = canonicalizer;
    }

    /** Version numbers are looked up by the caller; either may be null. */
    public TemplateResponse toResponse(ProcedureTemplate t, Integer currentPublishedVersionNo, Integer draftVersionNo) {
        return new TemplateResponse(
                t.getId(),
                t.getOrgId(),
                t.getPropertyId(),
                t.getName(),
                t.getDescription(),
                t.getConsumerKey(),
                t.getStatus(),
                t.getCurrentPublishedVersionId(),
                currentPublishedVersionNo,
                draftVersionNo,
                t.getCreatedBy(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }

    public VersionSummary toSummary(ProcedureTemplateVersion v) {
        return new VersionSummary(
                v.getId(),
                v.getVersionNo(),
                v.getState(),
                v.getOrigin(),
                v.getDefinitionHash(),
                v.getChangeNote(),
                v.getCreatedBy(),
                v.getCreatedAt(),
                v.getPublishedBy(),
                v.getPublishedAt(),
                v.getRowVersion());
    }

    public VersionResponse toResponse(ProcedureTemplateVersion v) {
        return new VersionResponse(
                v.getId(),
                v.getTemplateId(),
                v.getVersionNo(),
                v.getState(),
                v.getOrigin(),
                v.getDefinitionHash(),
                v.getChangeNote(),
                v.getCreatedBy(),
                v.getCreatedAt(),
                v.getPublishedBy(),
                v.getPublishedAt(),
                v.getRowVersion(),
                canonicalizer.parse(v.getDefinitionJson()));
    }
}
