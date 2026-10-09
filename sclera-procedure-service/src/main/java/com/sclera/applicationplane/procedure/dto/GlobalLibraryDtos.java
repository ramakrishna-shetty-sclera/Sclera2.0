package com.sclera.applicationplane.procedure.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

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
     */
    public record ExportedProcedure(
            String name,
            String description,
            String consumerKey,
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

    // --- favourites -------------------------------------------------------------

    public record FavouriteResponse(boolean favourite) {}
}
