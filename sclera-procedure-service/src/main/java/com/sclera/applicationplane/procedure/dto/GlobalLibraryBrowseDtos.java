package com.sclera.applicationplane.procedure.dto;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What browsing the Sclera-wide library returns. Read-only shapes: nothing here
 * is ever sent back to the service, since authoring global content is another
 * feature's and copying a template goes through the existing import.
 */
public final class GlobalLibraryBrowseDtos {

    private GlobalLibraryBrowseDtos() {
    }

    /**
     * One template in the list: enough to recognise it and decide whether to open
     * it. {@code currentVersionNo} is the version an import would take.
     */
    public record GlobalTemplateSummary(
            UUID id,
            String name,
            String description,
            String consumerKey,
            int currentVersionNo,
            OffsetDateTime publishedAt) {}

    /**
     * One question of the question bank, with where it came from. Carries what is
     * needed to recognise it and find it again, not its whole definition: to pull
     * the question into a draft, open the template ({@code templateId}) and take the
     * item with this {@code questionKey} from its document.
     *
     * @param sectionText the heading it sits under, or null above the first heading
     * @param versionNo   the template's current version, the one the key belongs to
     */
    public record QuestionBankEntry(
            String questionKey,
            String text,
            String standard,
            String sectionText,
            UUID templateId,
            String templateName,
            int versionNo) {}

    /**
     * A template with its current version's whole form, so it can be read before
     * it is imported.
     */
    public record GlobalTemplateDetail(
            UUID id,
            String name,
            String description,
            String consumerKey,
            int currentVersionNo,
            OffsetDateTime publishedAt,
            String changeNote,
            DefinitionDocument definition) {}
}
