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
