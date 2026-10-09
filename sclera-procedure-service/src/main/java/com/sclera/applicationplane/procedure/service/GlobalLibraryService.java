package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryBrowseDtos.GlobalTemplateDetail;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryBrowseDtos.GlobalTemplateSummary;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateVersionRepository;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reading the Sclera-wide library: browsing it and opening one template.
 *
 * <p>Read-only, and deliberately not organization-scoped. The library is
 * Sclera's own content, the same for everyone, and it lives in {@code public}, so
 * nothing here looks at who is asking.
 *
 * <p>Copying a template into an organization is not here: that is the existing
 * import, and this exists to make a template easy to find before importing it.
 */
@Service
@Transactional(readOnly = true)
public class GlobalLibraryService {

    private final GlobalProcedureTemplateRepository templates;
    private final GlobalProcedureTemplateVersionRepository versions;
    private final DefinitionCanonicalizer canonicalizer;

    public GlobalLibraryService(GlobalProcedureTemplateRepository templates,
                                GlobalProcedureTemplateVersionRepository versions,
                                DefinitionCanonicalizer canonicalizer) {
        this.templates = templates;
        this.versions = versions;
        this.canonicalizer = canonicalizer;
    }

    /**
     * The templates a reader can pick from, filtered by a word in the name or
     * description and by consumer, each optional and independent of the other.
     * A consumer is trimmed and upper-cased as it is everywhere else, because
     * that is how it is stored. The search is case-insensitive, and what is typed
     * is taken literally: a {@code %} or {@code _} in it matches itself.
     */
    public Page<GlobalTemplateSummary> browse(String search, String consumer, Pageable pageable) {
        Page<GlobalProcedureTemplate> page = templates.browse(
                consumer == null ? "" : consumer.strip().toUpperCase(Locale.ROOT),
                likePattern(search),
                pageable);

        // One query for the whole page's version numbers rather than one per template.
        List<UUID> versionIds = page.getContent().stream()
                .map(GlobalProcedureTemplate::getCurrentPublishedVersionId)
                .filter(Objects::nonNull)
                .toList();
        Map<UUID, GlobalProcedureTemplateVersion> byId = versions.findAllById(versionIds).stream()
                .collect(Collectors.toMap(GlobalProcedureTemplateVersion::getId, Function.identity()));

        return page.map(t -> {
            GlobalProcedureTemplateVersion current = byId.get(t.getCurrentPublishedVersionId());
            return new GlobalTemplateSummary(t.getId(), t.getName(), t.getDescription(), t.getConsumerKey(),
                    current.getVersionNo(), current.getPublishedAt());
        });
    }

    /**
     * One template with its current version's whole form. A template that is
     * archived, or has never been published, is not found: it is not in the list
     * either, and there is nothing in it an import could take.
     */
    public GlobalTemplateDetail get(UUID id) {
        GlobalProcedureTemplate template = templates.findById(id)
                .filter(t -> t.getStatus() == TemplateStatus.ACTIVE)
                .filter(t -> t.getCurrentPublishedVersionId() != null)
                .orElseThrow(() -> new ResourceNotFoundException("Global template not found: " + id));
        GlobalProcedureTemplateVersion current = versions.findById(template.getCurrentPublishedVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("Global template not found: " + id));

        return new GlobalTemplateDetail(template.getId(), template.getName(), template.getDescription(),
                template.getConsumerKey(), current.getVersionNo(), current.getPublishedAt(),
                current.getChangeNote(), canonicalizer.parse(current.getDefinitionJson()));
    }

    /** A lower-cased LIKE pattern that matches the text anywhere, with its own wildcards made literal. */
    static String likePattern(String search) {
        if (search == null || search.isBlank()) {
            return "%";
        }
        String escaped = search.strip().toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}
