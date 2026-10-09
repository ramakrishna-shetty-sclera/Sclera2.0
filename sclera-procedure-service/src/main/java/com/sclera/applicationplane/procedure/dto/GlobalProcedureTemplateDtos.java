package com.sclera.applicationplane.procedure.dto;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.domain.VersionState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Request and response shapes for authoring Sclera's own shared library —
 * {@code /api/v1/global-procedure-templates}. Deliberately minimal: create,
 * save a draft, publish. No update, archive, clone or version history — this
 * is a small, infrequent, platform-admin-only action, not a second full
 * authoring surface; feature 12 is what makes this content genuinely
 * browsable.
 */
public final class GlobalProcedureTemplateDtos {

    private GlobalProcedureTemplateDtos() {
    }

    /** Creates the global template and its first DRAFT (v1). {@code definition} may be omitted. */
    public record CreateGlobalTemplateRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @Size(max = 50) String consumerKey,
            @Valid DefinitionDocument definition) {}

    /**
     * Replaces the draft's whole document. No {@code rowVersion}: unlike
     * {@code procedure_template_version}, this table carries no optimistic
     * lock — a deliberate omission, not an oversight, matching how small and
     * infrequent this authoring surface is expected to be (decision 3). If
     * concurrent global authors ever becomes a real scenario, add the column
     * and the check then.
     */
    public record SaveGlobalDraftRequest(
            @NotNull @Valid DefinitionDocument definition,
            @Size(max = 2000) String changeNote) {}

    public record GlobalTemplateResponse(
            UUID id,
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

    public record GlobalVersionResponse(
            UUID id,
            UUID globalTemplateId,
            int versionNo,
            VersionState state,
            String definitionHash,
            String changeNote,
            UUID publishedBy,
            OffsetDateTime publishedAt,
            DefinitionDocument definition) {}

    /**
     * {@code linkedOrgCount} — how many organizations currently track this
     * global template's updates ({@code link_state = LINKED}) at the moment
     * of publish. The only visible confirmation that the broadcast reached
     * anyone: the Kafka event itself carries no recipient (see
     * {@code GlobalTemplateEvent}), so without this count a platform admin
     * publishing a new version would have no way to tell that anything
     * happened. Derived fresh at publish time, never stored.
     */
    public record GlobalPublishResponse(boolean newVersion, GlobalVersionResponse version, long linkedOrgCount) {}
}
