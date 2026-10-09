package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.NewDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.SaveDraftRequest;
import com.sclera.applicationplane.procedure.dto.UsageDtos.ReportUsageRequest;
import com.sclera.applicationplane.procedure.dto.UsageDtos.VersionUsageSummary;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a template's author is told about the versions still in use: the impact
 * preview behind {@code GET /api/v1/procedure-templates/{id}/usage}.
 *
 * Reports go in through the service, as a consumer's call would, and the summary
 * is read back through {@link ProcedureUsageService#usageOf}. The endpoint adds
 * only the permission check, which is the gateway-and-OpenFGA half and not
 * exercised here.
 */
class VersionUsageIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService procedures;

    @Autowired
    private ProcedureUsageService usage;

    /** A procedure and the ids of its published versions, in order: index 0 is v1. */
    private record Procedure(UUID id, List<UUID> versionIds) {
    }

    private static DefinitionDocument document(String wording) {
        Item question = new Item(null, wording, null, QuestionType.YES_NO, true, false,
                List.of(new Option(null, "Yes", "PASS", null, false), new Option(null, "No", "FAIL", null, false)),
                null, null, null, false, null, false, null, null, null, null, null, List.of(), List.of());
        return new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(question), List.of(), List.of(),
                List.of());
    }

    /** A published procedure with {@code versions} versions, each worded differently so each can be published. */
    private Procedure publishedWith(int versions) {
        UUID id = procedures.create(new CreateTemplateRequest("Walk " + UUID.randomUUID(), null, null,
                document("Present?"))).id();
        List<UUID> ids = new ArrayList<>();
        ids.add(procedures.publish(id, null).version().id());
        for (int n = 2; n <= versions; n++) {
            procedures.createDraft(id, new NewDraftRequest(n - 1));
            var draft = procedures.getDraft(id);
            procedures.saveDraft(id, new SaveDraftRequest(document("Present, take " + n + "?"),
                    draft.rowVersion(), "take " + n));
            ids.add(procedures.publish(id, null).version().id());
        }
        return new Procedure(id, ids);
    }

    private void report(Procedure p, int versionNo, String ref, String target) {
        usage.reportUsage(new ReportUsageRequest(p.id(), p.versionIds().get(versionNo - 1), "INSPECTION", ref,
                target));
    }

    @Test
    void consumersAreCountedPerVersionOldestFirst() {
        // Three configurations on v3 and one on v4: two summaries, not four rows.
        actAsNewOrg();
        Procedure p = publishedWith(4);
        report(p, 3, "config-1", null);
        report(p, 3, "config-2", null);
        report(p, 3, "config-3", null);
        report(p, 4, "config-4", null);

        assertThat(usage.usageOf(p.id())).containsExactly(
                new VersionUsageSummary(3, 3),
                new VersionUsageSummary(4, 1));
    }

    @Test
    void theOldestVersionComesFirstWhateverOrderTheReportsArrivedIn() {
        actAsNewOrg();
        Procedure p = publishedWith(3);
        report(p, 3, "config-1", null);
        report(p, 1, "config-2", null);
        report(p, 2, "config-3", null);

        assertThat(usage.usageOf(p.id())).extracting(VersionUsageSummary::versionNo).containsExactly(1, 2, 3);
    }

    @Test
    void aConsumerThatMovedVersionsIsCountedOnlyOnTheNewOne() {
        actAsNewOrg();
        Procedure p = publishedWith(2);
        report(p, 1, "config-1", null);
        report(p, 2, "config-1", null);

        assertThat(usage.usageOf(p.id())).containsExactly(new VersionUsageSummary(2, 1));
    }

    @Test
    void aProcedureNobodyHasReportedAboutIsAnEmptyListNotAnError() {
        actAsNewOrg();
        Procedure p = publishedWith(1);

        assertThat(usage.usageOf(p.id())).isEmpty();
    }

    @Test
    void anotherProceduresConsumersAreNotCounted() {
        actAsNewOrg();
        Procedure mine = publishedWith(1);
        Procedure other = publishedWith(1);
        report(other, 1, "config-1", null);
        report(other, 1, "config-2", null);
        report(mine, 1, "config-3", null);

        assertThat(usage.usageOf(mine.id())).containsExactly(new VersionUsageSummary(1, 1));
    }

    @Test
    void aTargetTypeIsNotNeededToBeCounted() {
        // Some consumers bind a target type and some do not; both are consumers.
        actAsNewOrg();
        Procedure p = publishedWith(1);
        report(p, 1, "config-1", "EXTINGUISHER");
        report(p, 1, "config-2", null);

        assertThat(usage.usageOf(p.id())).containsExactly(new VersionUsageSummary(1, 2));
    }

    @Test
    void aPropertysProcedureHasItsUsageSeenFromOrganizationLevel() {
        // The procedure itself is hidden from organization level by row-level
        // security, but the usage and version tables have none, so an
        // organization admin still gets an honest impact preview.
        UUID org = actAsNewOrg();
        UUID property = UUID.randomUUID();
        Procedure p = asProperty(org, property, () -> {
            Procedure made = publishedWith(2);
            report(made, 2, "config-1", null);
            report(made, 2, "config-2", null);
            return made;
        });

        List<VersionUsageSummary> seen = asOrg(org, () -> usage.usageOf(p.id()));

        assertThat(seen).containsExactly(new VersionUsageSummary(2, 2));
    }
}
