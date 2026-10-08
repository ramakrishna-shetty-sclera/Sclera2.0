package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.client.vocabulary.CachedVocabulary;
import com.sclera.applicationplane.procedure.client.vocabulary.Vocabulary;
import com.sclera.applicationplane.procedure.client.vocabulary.VocabularyEntry;
import com.sclera.applicationplane.procedure.client.vocabulary.VocabularyKind;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetKind;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetType;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.NewDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.SaveDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.TemplateResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/**
 * "Which procedures apply to this extinguisher?" — the discovery query.
 *
 * The first test is the one that matters most: a procedure that names no target
 * types is what every procedure authored before this feature is, and it has to be
 * found by every query. Each test runs in a brand-new organization, so every
 * procedure found was made by that test.
 */
class DiscoveryIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService service;

    @MockBean
    private CachedVocabulary vocabulary;

    private void vocabularyHasEverything() {
        List<VocabularyEntry> entries = List.of("EXTINGUISHER", "HOSE_REEL", "ROOM", "FLOOR").stream()
                .map(k -> new VocabularyEntry(k, k, 1, true)).toList();
        doReturn(new Vocabulary(Map.of(
                VocabularyKind.ASSET_CLASS, entries, VocabularyKind.LOCATION_TYPE, entries,
                VocabularyKind.HIERARCHY_LEVEL, entries, VocabularyKind.ASSET_TAG, entries)))
                .when(vocabulary).forOrg(any());
    }

    private static TargetType asset(String key) {
        return new TargetType(TargetKind.ASSET_CLASS, key);
    }

    private static DefinitionDocument document(TargetType... targets) {
        Item question = Item.builder().text("Present?").type(QuestionType.YES_NO).required(true)
                .options(List.of(new Option(null, "Yes", "PASS", null, false), new Option(null, "No", "FAIL", null, false)))
                .build();
        return new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(question), List.of(), List.of(targets), List.of());
    }

    private UUID published(String name, String consumer, TargetType... targets) {
        UUID id = service.create(new CreateTemplateRequest(name, null, consumer, document(targets))).id();
        service.publish(id, null);
        return id;
    }

    private UUID published(String name, TargetType... targets) {
        return published(name, null, targets);
    }

    private List<UUID> found(TargetKind kind, String key) {
        return foundFor("INSPECTION", kind, key);
    }

    private List<UUID> foundFor(String consumer, TargetKind kind, String key) {
        return service.discover(consumer, kind, key, PageRequest.of(0, 20)).getContent()
                .stream().map(TemplateResponse::id).toList();
    }

    @Test
    void aProcedureThatNamesNoTargetTypesIsFoundByEveryQuery() {
        actAsNewOrg();
        UUID anywhere = published("Applies anywhere");

        assertThat(found(TargetKind.ASSET_CLASS, "EXTINGUISHER")).containsExactly(anywhere);
        assertThat(found(TargetKind.LOCATION_TYPE, "ROOM")).containsExactly(anywhere);
        assertThat(found(TargetKind.HIERARCHY_LEVEL, "FLOOR")).containsExactly(anywhere);
        assertThat(found(TargetKind.ASSET_TAG, "ANYTHING_AT_ALL")).containsExactly(anywhere);
    }

    @Test
    void aProcedureIsFoundForATargetItNamesAndNotForOneItDoesNot() {
        actAsNewOrg();
        vocabularyHasEverything();
        UUID extinguishers = published("Extinguisher walk", asset("EXTINGUISHER"));

        assertThat(found(TargetKind.ASSET_CLASS, "EXTINGUISHER")).containsExactly(extinguishers);
        assertThat(found(TargetKind.ASSET_CLASS, "HOSE_REEL")).isEmpty();
    }

    @Test
    void aProcedureNamingSeveralTargetsIsFoundForAnyOfThem() {
        actAsNewOrg();
        vocabularyHasEverything();
        UUID both = published("Fire equipment", asset("EXTINGUISHER"), asset("HOSE_REEL"));

        assertThat(found(TargetKind.ASSET_CLASS, "EXTINGUISHER")).containsExactly(both);
        assertThat(found(TargetKind.ASSET_CLASS, "HOSE_REEL")).containsExactly(both);
    }

    @Test
    void theKindIsPartOfTheTargetSoTheSameKeyInAnotherListDoesNotMatch() {
        actAsNewOrg();
        vocabularyHasEverything();
        UUID rooms = published("Room check", new TargetType(TargetKind.LOCATION_TYPE, "ROOM"));

        assertThat(found(TargetKind.LOCATION_TYPE, "ROOM")).containsExactly(rooms);
        assertThat(found(TargetKind.ASSET_CLASS, "ROOM")).isEmpty();
    }

    @Test
    void aProcedureThatWasNeverPublishedIsNotFound() {
        actAsNewOrg();
        service.create(new CreateTemplateRequest("Still a draft", null, null, document()));

        assertThat(found(TargetKind.ASSET_CLASS, "EXTINGUISHER")).isEmpty();
    }

    @Test
    void anArchivedProcedureIsNotFound() {
        actAsNewOrg();
        UUID id = published("Retired walk");

        service.archive(id);

        assertThat(found(TargetKind.ASSET_CLASS, "EXTINGUISHER")).isEmpty();
    }

    @Test
    void onlyTheCurrentPublishedVersionDecidesWhatAProcedureAppliesTo() {
        actAsNewOrg();
        vocabularyHasEverything();
        UUID id = published("Changing scope", asset("EXTINGUISHER"));
        service.createDraft(id, new NewDraftRequest(1));
        VersionResponse draft = service.getDraft(id);
        DefinitionDocument moved = new DefinitionDocument(draft.definition().schema(), draft.definition().items(),
                draft.definition().thresholds(), List.of(asset("HOSE_REEL")), List.of());
        service.saveDraft(id, new SaveDraftRequest(moved, draft.rowVersion(), "Now hose reels"));
        service.publish(id, null);

        // v1 named extinguishers and is still in the index; it must not keep the
        // procedure discoverable once v2 stopped naming them.
        assertThat(found(TargetKind.ASSET_CLASS, "EXTINGUISHER")).isEmpty();
        assertThat(found(TargetKind.ASSET_CLASS, "HOSE_REEL")).containsExactly(id);
    }

    @Test
    void anOpenDraftDoesNotChangeWhatTheLastPublishedVersionAppliesTo() {
        actAsNewOrg();
        vocabularyHasEverything();
        UUID id = published("Published scope", asset("EXTINGUISHER"));
        service.createDraft(id, new NewDraftRequest(1));
        VersionResponse draft = service.getDraft(id);
        DefinitionDocument unscoped = new DefinitionDocument(draft.definition().schema(), draft.definition().items(),
                draft.definition().thresholds(), List.of(), List.of());
        service.saveDraft(id, new SaveDraftRequest(unscoped, draft.rowVersion(), "Not yet published"));

        assertThat(found(TargetKind.ASSET_CLASS, "HOSE_REEL")).isEmpty();
        assertThat(found(TargetKind.ASSET_CLASS, "EXTINGUISHER")).containsExactly(id);
    }

    @Test
    void onlyProceduresOfTheAskedConsumerAreFound() {
        actAsNewOrg();
        UUID inspection = published("For inspections");
        UUID task = published("For tasks", "TASK");

        assertThat(foundFor("INSPECTION", TargetKind.ASSET_CLASS, "EXTINGUISHER")).containsExactly(inspection);
        assertThat(foundFor("task", TargetKind.ASSET_CLASS, "EXTINGUISHER")).containsExactly(task);
    }

    @Test
    void aProcedureOfAPropertyIsFoundInsideThatPropertyAndNotAtOrganizationLevel() {
        UUID org = actAsNewOrg();
        UUID property = UUID.randomUUID();
        UUID shared = asOrg(org, () -> published("Shared"));
        UUID local = asProperty(org, property, () -> published("Property only"));

        // The index has no row-level security so that it can see every property's
        // versions; the procedure_template join is what keeps this one hidden.
        assertThat(asOrg(org, () -> found(TargetKind.ASSET_CLASS, "EXTINGUISHER"))).containsExactly(shared);
        assertThat(asProperty(org, property, () -> found(TargetKind.ASSET_CLASS, "EXTINGUISHER")))
                .containsExactlyInAnyOrder(shared, local);
    }

    @Test
    void anotherOrganizationsProceduresAreNeverFound() {
        UUID first = actAsNewOrg();
        asOrg(first, () -> published("First org's walk"));

        UUID second = actAsNewOrg();

        assertThat(asOrg(second, () -> found(TargetKind.ASSET_CLASS, "EXTINGUISHER"))).isEmpty();
    }

    @Test
    void resultsArePagedWithATotal() {
        actAsNewOrg();
        published("One");
        published("Two");
        published("Three");

        var firstPage = service.discover("INSPECTION", TargetKind.ASSET_CLASS, "EXTINGUISHER", PageRequest.of(0, 2));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
    }

    @Test
    void theResponseCarriesTheCurrentPublishedVersionNumber() {
        actAsNewOrg();
        published("Numbered");

        TemplateResponse response = service.discover("INSPECTION", TargetKind.ASSET_CLASS, "EXTINGUISHER",
                PageRequest.of(0, 20)).getContent().get(0);

        assertThat(response.currentPublishedVersionNo()).isEqualTo(1);
    }
}
