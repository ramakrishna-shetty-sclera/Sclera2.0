package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.DocumentRef;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.ProcedureDocument;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.ResultType;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryDtos.ExportedProcedure;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryDtos.ExportedVersion;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryDtos.ImportFileResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.NewDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.SaveDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.TemplateResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.event.ProcedureTemplateEvent;
import com.sclera.applicationplane.procedure.repository.ProcedureDocumentRepository;
import com.sclera.applicationplane.procedure.repository.ResultTypeRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ConflictException;
import com.sclera.controlplane.common.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Importing a procedure from a file {@code /export} produced.
 *
 * Most files here are produced the way a person would produce them, by exporting
 * a real procedure, so the round trip is the thing under test. A few are built by
 * hand, because the point of them is something an export would never contain: a
 * file edited after the fact, one from an older version of the service, or one
 * naming a result type the destination does not have.
 */
class ImportFileIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService service;

    @Autowired
    private DefinitionCanonicalizer canonicalizer;

    @Autowired
    private ResultTypeRepository resultTypes;

    @Autowired
    private ProcedureDocumentRepository documents;

    // --- builders -----------------------------------------------------------

    private static Item question(String text) {
        return Item.builder().text(text).type(QuestionType.YES_NO).required(true)
                .options(List.of(new Option(null, "Yes", "PASS", null, false), new Option(null, "No", "FAIL", null, false)))
                .build();
    }

    private static DefinitionDocument document(List<Item> items, DocumentRef... refs) {
        return new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, items, List.of(), List.of(), List.of(refs));
    }

    private UUID publishedProcedure(String name, DocumentRef... refs) {
        UUID id = service.create(new CreateTemplateRequest(name, "A description", null,
                document(List.of(question("Exit clear?")), refs))).id();
        service.publish(id, null);
        return id;
    }

    private static ExportedProcedure renamed(ExportedProcedure file, String name) {
        return new ExportedProcedure(name, file.description(), file.consumerKey(), file.keySeq(), file.versions());
    }

    private ExportedVersion exportedVersion(int versionNo, DefinitionDocument document) {
        DefinitionCanonicalizer.Canonical canonical = canonicalizer.canonicalize(document);
        return new ExportedVersion(versionNo, canonical.json(), canonical.hash(), "note");
    }

    private ExportedProcedure handMade(String name, int keySeq, ExportedVersion... versions) {
        return new ExportedProcedure(name, null, null, keySeq, List.of(versions));
    }

    private UUID orgWideDocument(UUID orgId) {
        ProcedureDocument document = new ProcedureDocument();
        document.setOrgId(orgId);
        document.setName("Standard.pdf");
        document.setLocation("stub-" + UUID.randomUUID());
        document.setUploadedAt(OffsetDateTime.now());
        return documents.saveAndFlush(document).getId();
    }

    private List<String> procedureNames() {
        return service.list(null, null, false, PageRequest.of(0, 100)).map(TemplateResponse::name).getContent();
    }

    /** Adds one new question to the imported procedure's draft, and returns the keys that draft then holds. */
    private List<String> keysAfterAddingAQuestion(UUID id) {
        VersionResponse draft = service.createDraft(id, new NewDraftRequest(1));
        List<Item> items = new ArrayList<>(draft.definition().items());
        items.add(question("A later question"));
        VersionResponse saved = service.saveDraft(id, new SaveDraftRequest(
                document(items, draft.definition().documents().toArray(new DocumentRef[0])), draft.rowVersion(), "edit"));
        List<String> keys = new ArrayList<>();
        for (Item item : saved.definition().items()) {
            keys.add(item.key());
            item.options().forEach(o -> keys.add(o.key()));
        }
        return keys;
    }

    // --- the round trip ------------------------------------------------------

    @Test
    void anExportedProcedureComesBackWithTheSameContentByteForByte() {
        actAsNewOrg();
        UUID original = publishedProcedure("Fire walk");
        ExportedProcedure file = service.export(original, null, false);

        ImportFileResponse imported = service.importFromFile(renamed(file, "Fire walk copy"));

        TemplateResponse copy = imported.template();
        assertThat(copy.id()).isNotEqualTo(original);
        // The hash is taken over the exact stored bytes, so equal hashes mean the
        // same bytes, not merely the same meaning.
        assertThat(service.getVersion(copy.id(), 1).definitionHash())
                .isEqualTo(service.getVersion(original, 1).definitionHash());
        assertThat(service.getVersion(copy.id(), 1).definition())
                .isEqualTo(service.getVersion(original, 1).definition());
        assertThat(imported.droppedDocumentCitations()).isZero();
    }

    @Test
    void theCopyIsPublishedAtOnceAsItsOnlyVersion() {
        actAsNewOrg();
        UUID original = publishedProcedure("Fire walk");

        TemplateResponse copy = service.importFromFile(
                renamed(service.export(original, null, false), "Fire walk copy")).template();

        assertThat(copy.currentPublishedVersionNo()).isEqualTo(1);
        assertThat(copy.draftVersionNo()).as("there is no draft left waiting").isNull();
        assertThat(service.listVersions(copy.id())).hasSize(1);
        verify(events, atLeastOnce()).publish(any(), any(), eq(ProcedureTemplateEvent.EventType.PUBLISHED));
    }

    @Test
    void theNameAndDescriptionAreNotPartOfTheHashSoTheyCanBeChangedInTheFile() {
        actAsNewOrg();
        UUID original = publishedProcedure("Fire walk");
        ExportedProcedure file = service.export(original, null, false);
        ExportedProcedure edited = new ExportedProcedure("Edited by hand", "A different description",
                file.consumerKey(), file.keySeq(), file.versions());

        TemplateResponse copy = service.importFromFile(edited).template();

        assertThat(copy.name()).isEqualTo("Edited by hand");
        assertThat(copy.description()).isEqualTo("A different description");
    }

    @Test
    void onlyTheHighestVersionInTheFileIsRecreated() {
        actAsNewOrg();
        ExportedProcedure file = handMade("History", 6,
                exportedVersion(1, document(List.of(Item.builder().key("q1").text("First wording").type(QuestionType.TEXT).build()))),
                exportedVersion(3, document(List.of(Item.builder().key("q1").text("Current wording").type(QuestionType.TEXT).build()))),
                exportedVersion(2, document(List.of(Item.builder().key("q1").text("Middle wording").type(QuestionType.TEXT).build()))));

        TemplateResponse copy = service.importFromFile(file).template();

        assertThat(service.listVersions(copy.id())).as("one version, not three").hasSize(1);
        assertThat(service.getVersion(copy.id(), 1).definition().items())
                .extracting(Item::text).containsExactly("Current wording");
    }

    @Test
    void aFileFromAnotherOrganizationImportsIntoThisOne() {
        UUID source = actAsNewOrg();
        UUID original = publishedProcedure("Fire walk");
        ExportedProcedure file = service.export(original, null, false);
        UUID destination = actAsNewOrg();

        TemplateResponse copy = asOrg(destination, () -> service.importFromFile(file).template());

        assertThat(copy.name()).isEqualTo("Fire walk");
        assertThat(copy.orgId()).isEqualTo(destination).isNotEqualTo(source);
        assertThat(copy.propertyId()).isNull();
    }

    @Test
    void insideAPropertyTheCopyBelongsToThatPropertyAndAtOrganizationLevelItDoesNot() {
        UUID org = actAsNewOrg();
        UUID property = UUID.randomUUID();
        UUID original = publishedProcedure("Fire walk");
        ExportedProcedure file = service.export(original, null, false);

        TemplateResponse inProperty = asProperty(org, property, () -> service.importFromFile(renamed(file, "In property")).template());
        TemplateResponse atOrg = asOrg(org, () -> service.importFromFile(renamed(file, "At organization")).template());

        assertThat(inProperty.propertyId()).isEqualTo(property);
        assertThat(atOrg.propertyId()).isNull();
    }

    // --- the hash ------------------------------------------------------------

    @Test
    void aFileEditedAfterExportIsRefusedByNameAndNothingIsCreated() {
        actAsNewOrg();
        UUID original = publishedProcedure("Fire walk");
        ExportedProcedure file = service.export(original, null, false);
        ExportedVersion version = file.versions().get(0);
        ExportedVersion tampered = new ExportedVersion(version.versionNo(),
                version.definitionJson().replace("Exit clear?", "Exit blocked?"), version.definitionHash(), version.changeNote());

        assertThatThrownBy(() -> service.importFromFile(
                new ExportedProcedure("Tampered", null, null, file.keySeq(), List.of(tampered))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not match its own hash")
                .hasMessageContaining("edited by hand");
        assertThat(procedureNames()).doesNotContain("Tampered");
    }

    @Test
    void aFileWhoseHashWasAlteredIsRefusedToo() {
        actAsNewOrg();
        UUID original = publishedProcedure("Fire walk");
        ExportedProcedure file = service.export(original, null, false);
        ExportedVersion version = file.versions().get(0);
        ExportedVersion wrongHash = new ExportedVersion(version.versionNo(), version.definitionJson(),
                "0".repeat(64), version.changeNote());

        assertThatThrownBy(() -> service.importFromFile(
                new ExportedProcedure("Wrong hash", null, null, file.keySeq(), List.of(wrongHash))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not match its own hash");
    }

    @Test
    void aDefinitionThatIsNotAProcedureIsRefusedWithAClearMessageEvenWhenItsHashMatches() {
        actAsNewOrg();
        String notJson = "this is not json";
        ExportedVersion version = new ExportedVersion(1, notJson, DefinitionCanonicalizer.sha256Hex(notJson), null);

        assertThatThrownBy(() -> service.importFromFile(handMade("Not json", 3, version)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not a procedure definition this service can read");
    }

    // --- what is refused before anything is built ----------------------------

    @Test
    void aFileWithNoVersionsOrNoNameOrANonsenseCounterIsRefused() {
        actAsNewOrg();
        ExportedVersion ok = exportedVersion(1, document(List.of(question("Q"))));

        assertThatThrownBy(() -> service.importFromFile(handMade("No versions", 3)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("no version");
        assertThatThrownBy(() -> service.importFromFile(handMade("  ", 3, ok)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("does not name a procedure");
        assertThatThrownBy(() -> service.importFromFile(handMade("Negative", -1, ok)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("out of range");
        assertThatThrownBy(() -> service.importFromFile(handMade("Huge", 1_000_000_000, ok)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("out of range");
    }

    @Test
    void importingTheSameFileTwiceIntoOneScopeIsRefusedBecauseTheNameIsTaken() {
        actAsNewOrg();
        UUID original = publishedProcedure("Fire walk");
        ExportedProcedure file = renamed(service.export(original, null, false), "Imported once");
        service.importFromFile(file);

        assertThatThrownBy(() -> service.importFromFile(file)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aConsumerThisOrganizationDoesNotHaveIsRefused() {
        actAsNewOrg();
        ExportedVersion ok = exportedVersion(1, document(List.of(question("Q"))));
        ExportedProcedure file = new ExportedProcedure("Odd consumer", null, "NOT_A_CONSUMER", 3, List.of(ok));

        assertThatThrownBy(() -> service.importFromFile(file)).isInstanceOf(ValidationException.class);
    }

    // --- result types -------------------------------------------------------------

    @Test
    void aResultTypeTheOrganizationLacksIsCreatedNotDropped() {
        UUID org = actAsNewOrg();
        Item question = Item.builder().key("q1").text("Reading?").type(QuestionType.YES_NO)
                .options(List.of(new Option("o2", "Fine", "PASS", null, false), new Option("o3", "Odd", "AMBER", null, false)))
                .build();
        ExportedProcedure file = handMade("Needs amber", 3, exportedVersion(1, document(List.of(question))));
        assertThat(resultTypes.findAllByOrgIdOrderBySeverityOrderAsc(org)).extracting(ResultType::getKey)
                .doesNotContain("AMBER");

        TemplateResponse copy = service.importFromFile(file).template();

        List<ResultType> after = resultTypes.findAllByOrgIdOrderBySeverityOrderAsc(org);
        assertThat(after).extracting(ResultType::getKey).contains("AMBER");
        assertThat(after.get(after.size() - 1).getKey()).as("appended as the least severe").isEqualTo("AMBER");
        assertThat(copy.currentPublishedVersionNo()).isEqualTo(1);
    }

    @Test
    void contentThatCannotBePublishedHereRefusesTheWholeImportAndLeavesNothingBehind() {
        // The first question names a result type the organization lacks, so the
        // import creates it. The second has no answer mapped to any result, which
        // blocks publishing. The refusal must undo the result type the import had
        // already created, as well as the template.
        UUID org = actAsNewOrg();
        Item names = Item.builder().key("q1").text("Reading?").type(QuestionType.YES_NO)
                .options(List.of(new Option("o2", "Fine", "TEMPORARY_TYPE", null, false),
                        new Option("o3", "Odd", "PASS", null, false)))
                .build();
        Item mapsNothing = Item.builder().key("q4").text("Decides nothing?").type(QuestionType.YES_NO)
                .options(List.of(new Option("o5", "Yes", null, null, false), new Option("o6", "No", null, null, false)))
                .build();
        ExportedProcedure file = handMade("Cannot publish", 6, exportedVersion(1, document(List.of(names, mapsNothing))));

        assertThatThrownBy(() -> service.importFromFile(file))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot be published yet");

        assertThat(procedureNames()).doesNotContain("Cannot publish");
        assertThat(resultTypes.findAllByOrgIdOrderBySeverityOrderAsc(org)).extracting(ResultType::getKey)
                .doesNotContain("TEMPORARY_TYPE");
    }

    // --- document citations --------------------------------------------------------

    @Test
    void aCitationThatDoesNotResolveInTheDestinationIsDroppedAndCounted() {
        UUID source = actAsNewOrg();
        UUID docId = orgWideDocument(source);
        UUID original = publishedProcedure("Cites a document", new DocumentRef(docId.toString(), null));
        ExportedProcedure file = service.export(original, null, true);
        assertThat(canonicalizer.parse(file.versions().get(0).definitionJson()).documents()).hasSize(1);
        UUID destination = actAsNewOrg();

        ImportFileResponse imported = asOrg(destination, () -> service.importFromFile(file));

        assertThat(imported.droppedDocumentCitations()).isEqualTo(1);
        assertThat(asOrg(destination, () -> service.getVersion(imported.template().id(), 1).definition().documents()))
                .as("the import succeeded without the citation").isEmpty();
    }

    @Test
    void aCitationThatDoesResolveSurvives() {
        UUID org = actAsNewOrg();
        UUID docId = orgWideDocument(org);
        UUID original = publishedProcedure("Cites a document", new DocumentRef(docId.toString(), null));
        ExportedProcedure file = renamed(service.export(original, null, true), "Cites it again");

        ImportFileResponse imported = service.importFromFile(file);

        assertThat(imported.droppedDocumentCitations()).isZero();
        assertThat(service.getVersion(imported.template().id(), 1).definition().documents())
                .extracting(DocumentRef::id).containsExactly(docId.toString());
    }

    // --- the key counter ------------------------------------------------------------

    @Test
    void theFilesKeyCounterIsKeptSoALaterEditMintsAKeyNothingUsedBefore() {
        // Exported from a template whose counter is 6 although its content only
        // reaches key 3. A counter rebuilt from the content would mint q4 next,
        // which the removed question already used; the file's counter mints q7.
        actAsNewOrg();
        ExportedProcedure file = handMade("Counter six", 6, exportedVersion(1, document(List.of(
                Item.builder().key("q1").text("Exit clear?").type(QuestionType.YES_NO)
                        .options(List.of(new Option("o2", "Yes", "PASS", null, false), new Option("o3", "No", "FAIL", null, false)))
                        .build()))));

        TemplateResponse copy = service.importFromFile(file).template();

        assertThat(keysAfterAddingAQuestion(copy.id())).containsExactly("q1", "o2", "o3", "q7", "o8", "o9");
    }

    @Test
    void aFileFromBeforeTheCounterWasRecordedGetsOneFromItsOwnContent() {
        actAsNewOrg();
        ExportedProcedure file = handMade("No counter", 0, exportedVersion(1, document(List.of(
                Item.builder().key("q1").text("Exit clear?").type(QuestionType.YES_NO)
                        .options(List.of(new Option("o2", "Yes", "PASS", null, false), new Option("o3", "No", "FAIL", null, false)))
                        .build()))));

        TemplateResponse copy = service.importFromFile(file).template();

        assertThat(keysAfterAddingAQuestion(copy.id())).containsExactly("q1", "o2", "o3", "q4", "o5", "o6");
    }

    @Test
    void aCounterBelowTheContentsOwnKeysIsRaisedInsteadOfRefusingTheFile() {
        // Without this the key minter would reject the file's own keys as never issued.
        actAsNewOrg();
        ExportedProcedure file = handMade("Low counter", 1, exportedVersion(1, document(List.of(
                Item.builder().key("q1").text("Exit clear?").type(QuestionType.YES_NO)
                        .options(List.of(new Option("o2", "Yes", "PASS", null, false), new Option("o3", "No", "FAIL", null, false)))
                        .build()))));

        TemplateResponse copy = service.importFromFile(file).template();

        assertThat(copy.currentPublishedVersionNo()).isEqualTo(1);
        assertThat(keysAfterAddingAQuestion(copy.id())).containsExactly("q1", "o2", "o3", "q4", "o5", "o6");
    }
}
