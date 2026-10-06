package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionDiff;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CloneRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.NewDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.PublishResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.SaveDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.TemplateResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionSummary;
import com.sclera.applicationplane.procedure.event.ProcedureTemplateEvent.EventType;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.applicationplane.procedure.tenancy.TenantSchemas;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ConflictException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.exception.ValidationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ProcedureTemplateService against a real Postgres: migrations, tenant
 * schemas, row locks, optimistic locking and the database constraints behind
 * immutable versions.
 */
class ProcedureTemplateServiceIT extends PostgresIntegrationTest {

    @Autowired
    ProcedureTemplateService service;

    @Autowired
    JdbcTemplate jdbc;

    @Nested
    class Provisioning {

        @Test
        void aNewOrganizationGetsItsOwnMigratedSchema() {
            UUID orgId = actAsNewOrg();
            String schema = TenantSchemas.schemaFor(orgId);

            List<String> tables = jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
                    String.class, schema);
            String migratedTo = jdbc.queryForObject(
                    "SELECT version FROM \"" + schema + "\".flyway_schema_history "
                            + "WHERE success ORDER BY installed_rank DESC LIMIT 1",
                    String.class);

            List<String> policies = jdbc.queryForList(
                    "SELECT policyname FROM pg_policies WHERE schemaname = ? AND tablename = 'procedure_template'",
                    String.class, schema);

            assertThat(tables).contains("procedure_template", "procedure_template_version", "version_result_type_ref")
                    .doesNotContain("question_template", "template_section", "question");
            // Bump this when a tenant migration is added. Pinning it is the
            // point: it makes anyone adding one notice that every existing
            // tenant schema has to be migrated too, not just new ones.
            assertThat(migratedTo).isEqualTo("5");
            // A property-scoped table with no policy is wide open, and the
            // failure is silent — so provisioning asserts the policy arrived,
            // not merely that the migration ran.
            assertThat(policies).containsExactly("property_isolation");
        }
    }

    @Nested
    class CreateAndDraft {

        @Test
        void createMakesDraftV1WithMintedKeys() {
            actAsNewOrg();

            TemplateResponse created = service.create(create("Fire walk",
                    section(null, "Fire exits", question(null, "Is the exit clear?"), question(null, "Any obstruction?"))));
            VersionResponse draft = service.getDraft(created.id());

            assertThat(created.draftVersionNo()).isEqualTo(1);
            assertThat(created.currentPublishedVersionNo()).isNull();
            assertThat(draft.state()).isEqualTo(VersionState.DRAFT);
            assertThat(draft.definitionHash()).matches("^[0-9a-f]{64}$");
            assertThat(keys(draft)).containsExactly("s1", "q2", "q3");
        }

        @Test
        void saveKeepsExistingKeysAndMintsOnlyForNewItems() {
            actAsNewOrg();
            UUID id = service.create(create("Fire walk",
                    section(null, "Fire exits", question(null, "Is the exit clear?")))).id();
            VersionResponse before = service.getDraft(id);

            VersionResponse after = service.saveDraft(id, new SaveDraftRequest(
                    doc(section("s1", "Fire exits",
                            question("q2", "Is every exit clear?"),
                            question(null, "Photo of the exit"))),
                    before.rowVersion(), "Added a photo"));

            assertThat(keys(after)).containsExactly("s1", "q2", "q3");
            assertThat(after.definition().items().get(1).text())
                    .isEqualTo("Is every exit clear?");
            assertThat(after.rowVersion()).isEqualTo(before.rowVersion() + 1);
            assertThat(after.changeNote()).isEqualTo("Added a photo");
        }

        @Test
        void aSaveBasedOnAStaleRowVersionIsRejected() {
            actAsNewOrg();
            UUID id = service.create(create("Fire walk",
                    section(null, "Fire exits", question(null, "Is the exit clear?")))).id();
            long loaded = service.getDraft(id).rowVersion();

            // Two editors loaded the same draft; the first save wins.
            service.saveDraft(id, new SaveDraftRequest(
                    doc(section("s1", "Fire exits", question("q2", "First editor's wording"))), loaded, null));

            assertThatThrownBy(() -> service.saveDraft(id, new SaveDraftRequest(
                    doc(section("s1", "Fire exits", question("q2", "Second editor's wording"))), loaded, null)))
                    .isInstanceOf(ConflictException.class);
            assertThat(service.getDraft(id).definition().items().get(1).text())
                    .isEqualTo("First editor's wording");
        }

        @Test
        void anInventedKeyIsRejectedAndConsumesNoKeyNumbers() {
            actAsNewOrg();
            UUID id = service.create(create("Fire walk",
                    section(null, "Fire exits", question(null, "Is the exit clear?")))).id();
            long rowVersion = service.getDraft(id).rowVersion();

            assertThatThrownBy(() -> service.saveDraft(id, new SaveDraftRequest(
                    doc(section("s1", "Fire exits", question("q99", "Invented key"))), rowVersion, null)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("q99");

            // key_seq is still 2 (c1, q2), so the next new question is q3.
            VersionResponse after = service.saveDraft(id, new SaveDraftRequest(
                    doc(section("s1", "Fire exits", question("q2", "Is the exit clear?"), question(null, "New"))),
                    rowVersion, null));
            assertThat(keys(after)).containsExactly("s1", "q2", "q3");
        }

        @Test
        void aTemplateHasAtMostOneDraft() {
            actAsNewOrg();
            UUID id = service.create(create("Fire walk",
                    section(null, "Fire exits", question(null, "Is the exit clear?")))).id();

            assertThatThrownBy(() -> service.createDraft(id, new NewDraftRequest(1)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already has a draft");
        }

        @Test
        void namesAreUniqueIgnoringCaseUntilArchived() {
            actAsNewOrg();
            UUID first = service.create(create("Fire walk")).id();

            assertThatThrownBy(() -> service.create(create("FIRE WALK")))
                    .isInstanceOf(ConflictException.class);

            service.archive(first);
            assertThat(service.create(create("Fire walk")).id()).isNotEqualTo(first);
        }
    }

    @Nested
    class PublishAndHistory {

        @Test
        void publishFreezesTheDraftAsV1AndSendsOneEvent() {
            UUID userId = UUID.randomUUID();
            actAs(UUID.randomUUID(), userId);
            UUID id = service.create(create("Fire walk",
                    section(null, "Fire exits", question(null, "Is the exit clear?")))).id();

            PublishResponse published = service.publish(id, null);

            assertThat(published.newVersion()).isTrue();
            assertThat(published.version().versionNo()).isEqualTo(1);
            assertThat(published.version().state()).isEqualTo(VersionState.PUBLISHED);
            assertThat(published.version().publishedBy()).isEqualTo(userId);
            assertThat(published.version().publishedAt()).isNotNull();

            TemplateResponse template = service.get(id);
            assertThat(template.currentPublishedVersionNo()).isEqualTo(1);
            assertThat(template.draftVersionNo()).isNull();
            assertThatThrownBy(() -> service.getDraft(id)).isInstanceOf(ResourceNotFoundException.class);

            verify(events, times(1)).publish(any(), argThat(v -> v.getVersionNo() == 1), eq(EventType.PUBLISHED));
        }

        @Test
        void publishingUnchangedContentCreatesNothingAndSendsNoEvent() {
            actAsNewOrg();
            UUID id = publishedTemplate("Fire walk");
            service.createDraft(id, new NewDraftRequest(1));

            PublishResponse again = service.publish(id, null);

            assertThat(again.newVersion()).isFalse();
            assertThat(again.version().versionNo()).isEqualTo(1);
            assertThat(service.listVersions(id)).extracting(VersionSummary::versionNo).containsExactly(1);
            assertThat(service.get(id).draftVersionNo()).isNull();
            verify(events, times(1)).publish(any(), any(), eq(EventType.PUBLISHED)); // v1 only
        }

        @Test
        void publishingAnOlderVersionsContentRollsBackWithoutANewVersion() {
            actAsNewOrg();
            UUID id = publishedTemplate("Fire walk");
            publishChange(id, 1, "Reworded");                       // v2

            service.createDraft(id, new NewDraftRequest(1));        // v1's content again
            PublishResponse rollback = service.publish(id, null);

            assertThat(rollback.newVersion()).isFalse();
            assertThat(rollback.version().versionNo()).isEqualTo(1);
            assertThat(service.get(id).currentPublishedVersionNo()).isEqualTo(1);
            assertThat(service.listVersions(id)).extracting(VersionSummary::versionNo).containsExactly(2, 1);
            // v1, v2, then the rollback to v1: the current version moved, so consumers hear about it.
            verify(events, times(3)).publish(any(), any(), eq(EventType.PUBLISHED));
        }

        @Test
        void aPublishedVersionNeverChangesAndHistoryAndDiffUseStableKeys() {
            actAsNewOrg();
            UUID id = publishedTemplate("Fire walk");
            VersionResponse v1 = service.getVersion(id, 1);

            VersionResponse draft = service.createDraft(id, new NewDraftRequest(1));
            service.saveDraft(id, new SaveDraftRequest(
                    doc(section("s1", "Fire exits",
                            question("q2", "Is every exit clear?"),
                            question(null, "Photo of the exit"))),
                    draft.rowVersion(), "Reworded q2, added a photo"));
            service.publish(id, null);

            VersionResponse v1Later = service.getVersion(id, 1);
            assertThat(v1Later.definitionHash()).isEqualTo(v1.definitionHash());
            assertThat(v1Later.definition()).isEqualTo(v1.definition());

            assertThat(service.listVersions(id)).extracting(VersionSummary::versionNo, VersionSummary::state)
                    .containsExactly(tuple(2, VersionState.PUBLISHED), tuple(1, VersionState.PUBLISHED));

            DefinitionDiff diff = service.diff(id, 1, 2).diff();
            assertThat(diff.items()).extracting(DefinitionDiff.ItemChange::key, DefinitionDiff.ItemChange::kind)
                    .containsExactly(tuple("q2", DefinitionDiff.Kind.MODIFIED), tuple("q3", DefinitionDiff.Kind.ADDED));
            assertThat(diff.items().get(0).changedFields()).containsExactly("text");
        }

        @Test
        void aProcedureWithNoQuestionsCannotBePublished() {
            actAsNewOrg();
            UUID id = service.create(create("Empty", section(null, "Nothing yet"))).id();

            assertThatThrownBy(() -> service.publish(id, null))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("at least one question");
            verify(events, never()).publish(any(), any(), any());
        }

        @Test
        void publishNamesEveryReasonAndChecksTheOrganizationsOwnResultTypes() {
            actAsNewOrg();
            // PASS and FAIL are seeded for a new organization; AMBER is not.
            Item mappedToAmber = new Item(null, "Is the gauge in range?", null, QuestionType.YES_NO, false,
                    List.of(new Option(null, "Yes", "PASS"), new Option(null, "Partly", "AMBER")),
                    null, null, null, false, null, null, null, null, List.of(), List.of());
            Item onlyOneAnswer = new Item(null, "Is the seal intact?", null, QuestionType.DROPDOWN, false,
                    List.of(new Option(null, "Yes", "PASS")),
                    null, null, null, false, null, null, null, null, List.of(), List.of());
            UUID id = service.create(new CreateTemplateRequest("Blockers", null, null,
                    new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA,
                            List.of(mappedToAmber, onlyOneAnswer)))).id();

            // Both problems in one refusal: an author fixing a checklist should
            // not have to publish once per mistake to find them all.
            assertThatThrownBy(() -> service.publish(id, null))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("not an active result type")
                    .hasMessageContaining("needs at least two answers");
            verify(events, never()).publish(any(), any(), any());
        }

        @Test
        void aDraftCanBeDiscardedOnlyAfterTheFirstPublish() {
            actAsNewOrg();
            UUID neverPublished = service.create(create("New one",
                    section(null, "Fire exits", question(null, "Is the exit clear?")))).id();
            assertThatThrownBy(() -> service.discardDraft(neverPublished))
                    .isInstanceOf(BusinessRuleException.class);

            UUID id = publishedTemplate("Fire walk");
            service.createDraft(id, new NewDraftRequest(1));
            service.discardDraft(id);

            assertThat(service.get(id).draftVersionNo()).isNull();
            assertThat(service.get(id).currentPublishedVersionNo()).isEqualTo(1);
        }

        @Test
        void cloneCopiesAVersionAndItsKeysIntoAnIndependentTemplate() {
            actAsNewOrg();
            UUID source = publishedTemplate("Fire walk");

            TemplateResponse copy = service.cloneTemplate(source, new CloneRequest("Fire walk copy", 1));
            VersionResponse copyDraft = service.getDraft(copy.id());
            assertThat(copy.id()).isNotEqualTo(source);
            assertThat(copy.draftVersionNo()).isEqualTo(1);
            assertThat(keys(copyDraft)).containsExactly("s1", "q2");
            assertThat(copyDraft.definitionHash()).isEqualTo(service.getVersion(source, 1).definitionHash());

            // The key counter came along too, so the copy never re-issues a number.
            VersionResponse edited = service.saveDraft(copy.id(), new SaveDraftRequest(
                    doc(section("s1", "Fire exits", question("q2", "Is the exit clear?"), question(null, "New"))),
                    copyDraft.rowVersion(), null));
            assertThat(keys(edited)).containsExactly("s1", "q2", "q3");
            assertThat(service.listVersions(source)).hasSize(1);
        }

        @Test
        void archiveStopsChangesKeepsHistoryAndIsIdempotent() {
            actAsNewOrg();
            UUID id = publishedTemplate("Fire walk");

            assertThat(service.archive(id).status()).isEqualTo(TemplateStatus.ARCHIVED);
            assertThat(service.archive(id).status()).isEqualTo(TemplateStatus.ARCHIVED);

            assertThatThrownBy(() -> service.createDraft(id, new NewDraftRequest(1)))
                    .isInstanceOf(BusinessRuleException.class);
            assertThat(service.getVersion(id, 1).state()).isEqualTo(VersionState.PUBLISHED);
            verify(events, times(1)).publish(any(), argThat(v -> v.getVersionNo() == 1), eq(EventType.ARCHIVED));
        }
    }

    /**
     * The first four tests bypass the service and write SQL straight into the
     * tenant schema: the rules behind immutable versions must hold even for
     * code that forgets them, or for a hand-written script.
     */
    @Nested
    class DatabaseGuaranteesAndIsolation {

        private static final String OTHER_HASH = "a".repeat(64);

        @Test
        void theDatabaseRefusesASecondDraft() {
            UUID orgId = actAsNewOrg();
            UUID id = service.create(create("Fire walk",
                    section(null, "Fire exits", question(null, "Is the exit clear?")))).id();

            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO " + versions(orgId) + " (template_id, version_no, state, definition_json, definition_hash) "
                            + "VALUES (?, 2, 'DRAFT', '{\"schema\":1}', ?)", id, OTHER_HASH))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_ptv_one_draft");
        }

        @Test
        void theDatabaseRefusesTwoPublishedVersionsWithTheSameContent() {
            UUID orgId = actAsNewOrg();
            UUID id = publishedTemplate("Fire walk");
            String v1Hash = service.getVersion(id, 1).definitionHash();

            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO " + versions(orgId) + " (template_id, version_no, state, definition_json, definition_hash, "
                            + "published_at) VALUES (?, 2, 'PUBLISHED', '{\"schema\":1}', ?, now())", id, v1Hash))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_ptv_published_hash");
        }

        @Test
        void theDatabaseRefusesAPublishedVersionWithNoPublishStamp() {
            UUID orgId = actAsNewOrg();
            UUID id = publishedTemplate("Fire walk");

            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO " + versions(orgId) + " (template_id, version_no, state, definition_json, definition_hash) "
                            + "VALUES (?, 2, 'PUBLISHED', '{\"schema\":1}', ?)", id, OTHER_HASH))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_ptv_published");
        }

        @Test
        void aTemplateWithVersionsCannotBeHardDeleted() {
            UUID orgId = actAsNewOrg();
            UUID id = publishedTemplate("Fire walk");

            assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM \"" + TenantSchemas.schemaFor(orgId) + "\".procedure_template WHERE id = ?", id))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(service.getVersion(id, 1).state()).isEqualTo(VersionState.PUBLISHED);
        }

        @Test
        void anotherOrganizationCannotSeeOrReachATemplate() {
            UUID orgA = actAsNewOrg();
            UUID id = publishedTemplate("Fire walk");

            UUID orgB = actAsNewOrg();
            assertThat(service.list(null, PageRequest.of(0, 20)).getContent()).isEmpty();
            assertThatThrownBy(() -> service.get(id)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.getVersion(id, 1)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.createDraft(id, new NewDraftRequest(1)))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.archive(id)).isInstanceOf(ResourceNotFoundException.class);

            // The rows live only in org A's schema.
            assertThat(countTemplates(orgA)).isEqualTo(1);
            assertThat(countTemplates(orgB)).isZero();
        }

        @Test
        void differentOrganizationsMayUseTheSameName() {
            actAsNewOrg();
            UUID inA = service.create(create("Fire walk")).id();

            actAsNewOrg();
            UUID inB = service.create(create("Fire walk")).id();

            assertThat(inB).isNotEqualTo(inA);
        }

        private String versions(UUID orgId) {
            return "\"" + TenantSchemas.schemaFor(orgId) + "\".procedure_template_version";
        }

        private int countTemplates(UUID orgId) {
            return jdbc.queryForObject(
                    "SELECT count(*) FROM \"" + TenantSchemas.schemaFor(orgId) + "\".procedure_template", Integer.class);
        }
    }

    // --- scenario helpers ---------------------------------------------------

    /** A template with one category and one question, published as v1 (keys c1, q2). */
    private UUID publishedTemplate(String name) {
        UUID id = service.create(create(name,
                section(null, "Fire exits", question(null, "Is the exit clear?")))).id();
        service.publish(id, null);
        return id;
    }

    /** New draft from {@code fromVersionNo}, reword q2, publish. */
    private void publishChange(UUID id, int fromVersionNo, String newText) {
        VersionResponse draft = service.createDraft(id, new NewDraftRequest(fromVersionNo));
        service.saveDraft(id, new SaveDraftRequest(
                doc(section("s1", "Fire exits", question("q2", newText))), draft.rowVersion(), null));
        service.publish(id, null);
    }

    // --- builders -----------------------------------------------------------
    //
    // A "section with questions" is a heading followed by its questions as
    // siblings in the one flat list — the document has no level that wraps
    // them. The helper returns the group so the call sites read the way the
    // screen does.

    @SafeVarargs
    private static CreateTemplateRequest create(String name, List<Item>... groups) {
        return new CreateTemplateRequest(name, null, null, doc(groups));
    }

    @SafeVarargs
    private static DefinitionDocument doc(List<Item>... groups) {
        return new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA,
                Stream.of(groups).flatMap(List::stream).toList());
    }

    private static List<Item> section(String key, String title, Item... questions) {
        Item heading = new Item(key, title, null, QuestionType.SECTION, false, List.of(),
                null, null, null, false, null, null, null, null, List.of(), List.of());
        return Stream.concat(Stream.of(heading), Stream.of(questions)).toList();
    }

    private static Item question(String key, String text) {
        return new Item(key, text, null, QuestionType.TEXT, false, List.of(),
                null, null, null, false, null, null, null, null, List.of(), List.of());
    }

    /** Every key in document order, follow-ups included. */
    private static List<String> keys(VersionResponse version) {
        return version.definition().flatten().stream().map(Item::key).toList();
    }
}
