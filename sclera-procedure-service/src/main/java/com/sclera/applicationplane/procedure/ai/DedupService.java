package com.sclera.applicationplane.procedure.ai;

import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateVersionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Flags candidates that duplicate a question the organization already has, so
 * the reviewer reuses instead of re-authoring. There is no question bank in
 * this system — the corpus is every question in the org's template versions
 * (current published + draft of each template), matched on normalized text:
 * exact match, or a token-overlap above {@link #SIMILARITY_THRESHOLD}.
 *
 * Advisory only: a flagged candidate is still importable; the flag rides along
 * for the review screen.
 */
@Component
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
public class DedupService {

    private static final double SIMILARITY_THRESHOLD = 0.8;

    private final ProcedureTemplateRepository templates;
    private final ProcedureTemplateVersionRepository versions;
    private final DefinitionCanonicalizer canonicalizer;

    public DedupService(ProcedureTemplateRepository templates,
                        ProcedureTemplateVersionRepository versions,
                        DefinitionCanonicalizer canonicalizer) {
        this.templates = templates;
        this.versions = versions;
        this.canonicalizer = canonicalizer;
    }

    /** What the review screen shows next to a flagged candidate. */
    public record SimilarQuestion(UUID templateId, String templateName, String questionKey, String text) {
    }

    /** The org's question corpus, built once per generation run and probed per candidate. */
    public record Index(List<Entry> entries) {
        record Entry(SimilarQuestion question, String normalized, Set<String> tokens) {
        }
    }

    public Index buildIndex(UUID orgId) {
        List<Index.Entry> entries = new ArrayList<>();
        for (ProcedureTemplate template : templates.findAllByOrgId(orgId, null, null, false, null, Pageable.unpaged())) {
            for (ProcedureTemplateVersion version : relevantVersions(template)) {
                DefinitionDocument document = canonicalizer.parse(version.getDefinitionJson());
                for (Item item : document.flatten()) {
                    if (item.type() == QuestionType.SECTION) {
                        continue;
                    }
                    String normalized = normalize(item.text());
                    if (normalized.isEmpty()) {
                        continue;
                    }
                    entries.add(new Index.Entry(
                            new SimilarQuestion(template.getId(), template.getName(), item.key(), item.text()),
                            normalized, tokens(normalized)));
                }
            }
        }
        return new Index(entries);
    }

    public Optional<SimilarQuestion> findSimilar(Index index, String candidateText) {
        String normalized = normalize(candidateText);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        Set<String> candidateTokens = tokens(normalized);
        SimilarQuestion best = null;
        double bestScore = 0;
        for (Index.Entry entry : index.entries()) {
            if (entry.normalized().equals(normalized)) {
                return Optional.of(entry.question());
            }
            double score = jaccard(candidateTokens, entry.tokens());
            if (score >= SIMILARITY_THRESHOLD && score > bestScore) {
                best = entry.question();
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    /** What the org would actually see duplicated: the live version and the draft. */
    private List<ProcedureTemplateVersion> relevantVersions(ProcedureTemplate template) {
        List<ProcedureTemplateVersion> out = new ArrayList<>();
        if (template.getCurrentPublishedVersionId() != null) {
            versions.findById(template.getCurrentPublishedVersionId()).ifPresent(out::add);
        }
        versions.findByTemplateIdAndState(template.getId(), VersionState.DRAFT).ifPresent(out::add);
        return out;
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }

    private static Set<String> tokens(String normalized) {
        return new HashSet<>(List.of(normalized.split(" ")));
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        if (intersection.isEmpty()) {
            return 0;
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
