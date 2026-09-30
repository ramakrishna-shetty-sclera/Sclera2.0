package com.sclera.applicationplane.procedure.dto;

import com.sclera.applicationplane.procedure.definition.DefinitionDiff;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.domain.VersionOrigin;
import com.sclera.applicationplane.procedure.domain.VersionState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Request and response shapes for /api/v1/procedure-templates. */
public final class ProcedureTemplateDtos {

    private ProcedureTemplateDtos() {
    }

    // --- requests -----------------------------------------------------------

    /** Creates the template and its first DRAFT (v1). {@code definition} may be omitted. */
    public record CreateTemplateRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @Size(max = 50) String consumerKey,
            @Valid DefinitionDocument definition) {}

    /** Identity only — name and description are not versioned. */
    public record UpdateTemplateRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description) {}

    /**
     * Replaces the draft's whole document. {@code rowVersion} must be the value
     * the client last read; a mismatch means someone else saved in between.
     */
    public record SaveDraftRequest(
            @NotNull @Valid DefinitionDocument definition,
            @NotNull Long rowVersion,
            @Size(max = 2000) String changeNote) {}

    public record NewDraftRequest(@NotNull @Positive Integer fromVersionNo) {}

    /** Optional body; when rowVersion is given, publish refuses a draft that changed since. */
    public record PublishRequest(Long rowVersion) {}

    /** fromVersionNo defaults to the current published version, else the draft. */
    public record CloneRequest(
            @NotBlank @Size(max = 200) String name,
            @Positive Integer fromVersionNo) {}

    // --- responses ----------------------------------------------------------

    public record TemplateResponse(
            UUID id,
            UUID orgId,
            String name,
            String description,
            String consumerKey,
            TemplateStatus status,
            UUID currentPublishedVersionId,
            Integer currentPublishedVersionNo,
            Integer draftVersionNo,
            UUID createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    /** A version without its document, for history lists. */
    public record VersionSummary(
            UUID id,
            int versionNo,
            VersionState state,
            VersionOrigin origin,
            String definitionHash,
            String changeNote,
            UUID createdBy,
            OffsetDateTime createdAt,
            UUID publishedBy,
            OffsetDateTime publishedAt,
            long rowVersion) {}

    public record VersionResponse(
            UUID id,
            UUID templateId,
            int versionNo,
            VersionState state,
            VersionOrigin origin,
            String definitionHash,
            String changeNote,
            UUID createdBy,
            OffsetDateTime createdAt,
            UUID publishedBy,
            OffsetDateTime publishedAt,
            long rowVersion,
            DefinitionDocument definition) {}

    /**
     * {@code newVersion} is false when the draft's content matched an existing
     * published version: no version was created, the draft was discarded, and
     * that version became (or already was) the current one.
     */
    public record PublishResponse(boolean newVersion, VersionResponse version) {}

    public record DiffResponse(int fromVersionNo, int toVersionNo, DefinitionDiff diff) {}
}
