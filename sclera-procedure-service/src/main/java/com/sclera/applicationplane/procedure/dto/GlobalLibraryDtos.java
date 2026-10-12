package com.sclera.applicationplane.procedure.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.TemplateResponse;

import java.util.List;
import java.util.UUID;

/** Export, import, linking and favourites — the Sclera-wide library's own shapes. */
public final class GlobalLibraryDtos {

    private GlobalLibraryDtos() {
    }

    // --- export ---------------------------------------------------------------

    /**
     * One version, packaged for sharing: enough to recreate it elsewhere,
     * nothing more. {@code definitionJson}/{@code definitionHash} are
     * recomputed for the export, not copied from the source version — they
     * differ from the source whenever {@code includeDocuments} stripped the
     * cited documents, and the export must describe what it actually
     * contains, not what the original did.
     */
    public record ExportedVersion(
            int versionNo,
            String definitionJson,
            String definitionHash,
            String changeNote) {}

    /**
     * A procedure, packaged for sharing — the whole thing {@code /export}
     * produces. Carries no org id, no property id and no FGA state: none of
     * that travels with a procedure once it leaves the organization that
     * authored it.
     *
     * <p>{@code keySeq} is the source template's key counter, the number the next
     * question, section or answer key is minted from. It is carried because it is
     * not recoverable from the content: keys are never reused, so a template that
     * has had items added and removed has a counter past the highest key still in
     * its document. A copy that restarted the counter from what it can see could
     * mint a key an earlier edit already used, and the key minter would accept it
     * as fresh. Cloning and forking carry the counter for the same reason.
     */
    public record ExportedProcedure(
            String name,
            String description,
            String consumerKey,
            int keySeq,
            List<ExportedVersion> versions) {}

    // --- import -----------------------------------------------------------------

    /**
     * Pulls one version of a global template into this organization.
     *
     * @param globalTemplateId the source in the Sclera-wide library
     * @param versionNo        which published version to import; null means
     *                         the global template's current one
     * @param templateId       null creates a new template; set updates this
     *                         existing one with a new draft
     * @param name             the new template's name — required when
     *                         {@code templateId} is null, ignored (the
     *                         existing name is kept) otherwise
     */
    public record ImportRequest(
            @NotNull UUID globalTemplateId,
            @Positive Integer versionNo,
            UUID templateId,
            @Size(max = 200) String name) {}

    /**
     * What importing a file produced: the new procedure, and how many document
     * citations were left out of it.
     *
     * <p>A citation names a library document by id, and that id means nothing
     * outside the scope it was exported from. The importer does not own the
     * destination's document library, so a citation it cannot resolve is dropped
     * rather than refusing the whole import, and the count is reported so the
     * screen can say so instead of losing them silently.
     */
    public record ImportFileResponse(
            TemplateResponse template,
            int droppedDocumentCitations) {}

    // --- favourites -------------------------------------------------------------

    public record FavouriteResponse(boolean favourite) {}
}
