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
import com.sclera.applicationplane.procedure.domain.VersionTargetType;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.NewDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.PublishResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.SaveDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.repository.VersionTargetTypeRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.applicationplane.procedure.tenancy.TenantSchemas;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/**
 * The index of what each published version applies to — written at publish, and
 * only there.
 *
 * The vocabulary is mocked as complete, since what is under test is the index and
 * not the check. Each test runs in a brand-new organization, so its schema starts
 * with an empty index and every row found was written by that test.
 */
class VersionTargetTypeIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService service;

    @Autowired
    private VersionTargetTypeRepository index;

    @Autowired
    private JdbcTemplate jdbc;

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

    private static CreateTemplateRequest create(TargetType... targets) {
        Item question = new Item(null, "Present?", null, QuestionType.YES_NO, true, false,
                List.of(new Option(null, "Yes", "PASS", null, false), new Option(null, "No", "FAIL", null, false)),
                null, null, null, false, null, null, null, null, null, null, List.of(), List.of());
        return new CreateTemplateRequest("Walk " + UUID.randomUUID(), null, null,
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(question), List.of(), List.of(targets)));
    }

    private static List<org.assertj.core.groups.Tuple> rows(List<VersionTargetType> found) {
        return found.stream().map(r -> tuple(r.getVersionId(), r.getKind(), r.getKey())).toList();
    }

    @Test
    void publishingRecordsEachDistinctTargetTypeOnce() {
        actAsNewOrg();
        vocabularyHasEverything();
        UUID id = service.create(create(asset("EXTINGUISHER"), asset("HOSE_REEL"))).id();

        PublishResponse published = service.publish(id, null);

        assertThat(rows(index.findAll())).containsExactlyInAnyOrder(
                tuple(published.version().id(), TargetKind.ASSET_CLASS, "EXTINGUISHER"),
                tuple(published.version().id(), TargetKind.ASSET_CLASS, "HOSE_REEL"));
    }

    @Test
    void theSameKeyInTwoListsIsTwoRowsBecauseTheyAreTwoDifferentTargets() {
        actAsNewOrg();
        vocabularyHasEverything();
        UUID id = service.create(create(new TargetType(TargetKind.LOCATION_TYPE, "ROOM"),
                new TargetType(TargetKind.ASSET_CLASS, "ROOM"))).id();

        service.publish(id, null);

        assertThat(index.findAll()).extracting(VersionTargetType::getKind, VersionTargetType::getKey)
                .containsExactlyInAnyOrder(tuple(TargetKind.LOCATION_TYPE, "ROOM"), tuple(TargetKind.ASSET_CLASS, "ROOM"));
    }

    @Test
    void aProcedureThatAppliesToAnythingWritesNoRows() {
        // No rows is not "applies to nothing": it is the default, and discovery
        // has to read the absence as a match.
        actAsNewOrg();
        UUID id = service.create(create()).id();

        service.publish(id, null);

        assertThat(index.count()).isZero();
    }

    @Test
    void aDraftWritesNothingSoNothingIsCommittedTo() {
        actAsNewOrg();
        vocabularyHasEverything();

        service.create(create(asset("EXTINGUISHER")));

        assertThat(index.count()).isZero();
    }

    @Test
    void republishingUnchangedContentAddsNoRows() {
        actAsNewOrg();
        vocabularyHasEverything();
        UUID id = service.create(create(asset("EXTINGUISHER"))).id();
        PublishResponse first = service.publish(id, null);

        service.createDraft(id, new NewDraftRequest(1));      // v1's content again
        PublishResponse again = service.publish(id, null);

        assertThat(again.newVersion()).isFalse();
        assertThat(index.findAll()).extracting(VersionTargetType::getVersionId)
                .containsOnly(first.version().id()).hasSize(1);
    }

    @Test
    void wideningWhatAVersionAppliesToAddsRowsForTheNewVersionAndLeavesTheOldOnesAlone() {
        actAsNewOrg();
        vocabularyHasEverything();
        UUID id = service.create(create(asset("EXTINGUISHER"))).id();
        PublishResponse v1 = service.publish(id, null);

        service.createDraft(id, new NewDraftRequest(1));
        VersionResponse draft = service.getDraft(id);
        DefinitionDocument widened = new DefinitionDocument(draft.definition().schema(), draft.definition().items(),
                draft.definition().thresholds(), List.of(asset("EXTINGUISHER"), asset("HOSE_REEL")));
        service.saveDraft(id, new SaveDraftRequest(widened, draft.rowVersion(), "Added hose reels"));
        PublishResponse v2 = service.publish(id, null);

        // v1 still applies to extinguishers only; v2 to both.
        assertThat(rows(index.findAll())).containsExactlyInAnyOrder(
                tuple(v1.version().id(), TargetKind.ASSET_CLASS, "EXTINGUISHER"),
                tuple(v2.version().id(), TargetKind.ASSET_CLASS, "EXTINGUISHER"),
                tuple(v2.version().id(), TargetKind.ASSET_CLASS, "HOSE_REEL"));
    }

    @Test
    void aPropertysVersionIsInTheIndexAtOrganizationLevelBecauseTheTableHasNoRowLevelSecurity() {
        // Discovery must see every property's versions. A procedure that belongs
        // to a property is invisible at organization level as a template, but its
        // rows in this index are not.
        UUID org = actAsNewOrg();
        vocabularyHasEverything();
        UUID property = UUID.randomUUID();
        PublishResponse published = asProperty(org, property, () -> {
            UUID id = service.create(create(asset("EXTINGUISHER"))).id();
            return service.publish(id, null);
        });

        List<VersionTargetType> atOrgLevel = asOrg(org, () -> index.findAll());

        assertThat(rows(atOrgLevel)).containsExactly(
                tuple(published.version().id(), TargetKind.ASSET_CLASS, "EXTINGUISHER"));
    }

    @Test
    void theTableCarriesNoPolicyOfItsOwn() {
        // The opposite of procedure_template, which must have one. Pinned so that
        // someone copying that table's migration does not add one here by habit.
        UUID org = actAsNewOrg();

        List<String> policies = jdbc.queryForList(
                "SELECT policyname FROM pg_policies WHERE schemaname = ? AND tablename = 'version_target_type'",
                String.class, TenantSchemas.schemaFor(org));

        assertThat(policies).isEmpty();
    }

    @Test
    void anUnknownKindIsRefusedByTheDatabase() {
        UUID org = actAsNewOrg();
        vocabularyHasEverything();
        UUID id = service.create(create(asset("EXTINGUISHER"))).id();
        UUID versionId = service.publish(id, null).version().id();

        assertThatThrownBy(() -> jdbc.update("INSERT INTO \"" + TenantSchemas.schemaFor(org)
                + "\".version_target_type (version_id, kind, key) VALUES (?, 'ZONE', 'X')", versionId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_version_target_type_kind");
    }
}