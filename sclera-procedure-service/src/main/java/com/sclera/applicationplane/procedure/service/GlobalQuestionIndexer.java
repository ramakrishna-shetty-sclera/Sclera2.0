package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Group;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.GlobalQuestionIndexEntry;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.event.GlobalTemplateEvent;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateVersionRepository;
import com.sclera.applicationplane.procedure.repository.GlobalQuestionIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the question bank in step with the Sclera-wide library: when a global
 * version is published, its questions are written to {@code global_question_index}.
 *
 * <p>Runs from a Kafka listener, so there is no request, no user and no
 * organization. That is fine: the tables are in {@code public}, which is where a
 * connection with no organization points.
 *
 * <p><b>Safe to run twice for the same event.</b> Kafka delivers at least once,
 * and a version may be indexed again after a fix, so the version's rows are
 * deleted and written afresh in one transaction instead of being added to.
 */
@Service
@Transactional
public class GlobalQuestionIndexer {

    private static final Logger log = LoggerFactory.getLogger(GlobalQuestionIndexer.class);

    private final GlobalProcedureTemplateVersionRepository versions;
    private final GlobalQuestionIndexRepository index;
    private final DefinitionCanonicalizer canonicalizer;

    public GlobalQuestionIndexer(GlobalProcedureTemplateVersionRepository versions,
                                 GlobalQuestionIndexRepository index,
                                 DefinitionCanonicalizer canonicalizer) {
        this.versions = versions;
        this.index = index;
        this.canonicalizer = canonicalizer;
    }

    /**
     * Indexes the version a PUBLISHED event names, and returns how many
     * questions it wrote.
     *
     * <p>An event is not trusted to be right about the database. A version that
     * does not exist, or is not published, writes nothing and is logged: a
     * draft's questions must never reach a bank every organization searches.
     * Nothing is thrown for either, because a listener that throws is retried
     * and the same event would fail the same way each time.
     */
    public int indexPublished(GlobalTemplateEvent event) {
        if (event.versionId() == null) {
            log.warn("Global template event {} names no version; nothing to index", event.eventId());
            return 0;
        }
        GlobalProcedureTemplateVersion version = versions.findById(event.versionId()).orElse(null);
        if (version == null) {
            log.warn("Global template event {} names version {} which does not exist; nothing to index",
                    event.eventId(), event.versionId());
            return 0;
        }
        if (version.getState() != VersionState.PUBLISHED) {
            log.warn("Global template event {} names version {} which is not published; nothing to index",
                    event.eventId(), event.versionId());
            return 0;
        }

        List<GlobalQuestionIndexEntry> rows = rowsFor(version, canonicalizer.parse(version.getDefinitionJson()));
        index.deleteByGlobalVersionId(version.getId());
        index.saveAll(rows);
        index.flush();
        log.info("Indexed {} questions of global template {} v{}",
                rows.size(), version.getGlobalTemplateId(), version.getVersionNo());
        return rows.size();
    }

    /**
     * One row per question, headings excluded, each carrying the heading it sits
     * under. Follow-ups are questions too and go under their parent's heading.
     */
    private static List<GlobalQuestionIndexEntry> rowsFor(GlobalProcedureTemplateVersion version,
                                                          DefinitionDocument document) {
        List<GlobalQuestionIndexEntry> rows = new ArrayList<>();
        for (Group group : document.groups()) {
            String sectionText = group.section() == null ? null : group.section().text();
            for (Item question : group.questions()) {
                collect(version, question, sectionText, rows);
            }
        }
        return rows;
    }

    private static void collect(GlobalProcedureTemplateVersion version, Item question, String sectionText,
                                List<GlobalQuestionIndexEntry> rows) {
        // A published version always has keys; one without cannot be told apart from
        // its neighbours, so it is left out rather than failing the whole version.
        if (question.key() != null) {
            rows.add(new GlobalQuestionIndexEntry(version.getId(), version.getGlobalTemplateId(),
                    question.key(), question.text(), question.standard(), sectionText));
        }
        for (Item followUp : question.follow()) {
            collect(version, followUp, sectionText, rows);
        }
    }
}
