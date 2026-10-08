package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Threshold;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.VersionResultTypeRef;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.NewDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.PublishResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.SaveDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.dto.ResultTypeRequest;
import com.sclera.applicationplane.procedure.repository.VersionResultTypeRefRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The index of which result types each published version uses — written at
 * publish, and only there.
 *
 * Each test runs in a brand-new organization, so its schema starts with an
 * empty index and every row found was written by that test.
 */
class VersionResultTypeRefIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService service;

    @Autowired
    private ResultTypeService resultTypes;

    @Autowired
    private VersionResultTypeRefRepository refs;

    @Test
    void publishingRecordsEachResultTypeOnceHoweverManyAnswersNameIt() {
        actAsNewOrg();
        // Six questions, each answered Yes = PASS / No = FAIL: twelve mapped
        // answers, two result types.
        List<Item> questions = IntStream.rangeClosed(1, 6)
                .mapToObj(n -> passFail("Check " + n + " done?"))
                .toList();
        UUID id = service.create(create("Six checks", questions)).id();

        PublishResponse published = service.publish(id, null);

        assertThat(refs.findAll())
                .extracting(VersionResultTypeRef::getVersionId, VersionResultTypeRef::getResultTypeKey)
                .containsExactlyInAnyOrder(
                        tuple(published.version().id(), "FAIL"),
                        tuple(published.version().id(), "PASS"));
    }

    @Test
    void aDraftRecordsNothing() {
        actAsNewOrg();
        UUID id = service.create(create("Still a draft", List.of(passFail("Exit clear?")))).id();

        VersionResponse draft = service.getDraft(id);
        service.saveDraft(id, new SaveDraftRequest(draft.definition(), draft.rowVersion(), "edited"));

        // A draft commits to nothing, so PASS and FAIL stay as deletable as before.
        assertThat(refs.count()).isZero();
    }

    @Test
    void republishingUnchangedContentAddsNoRows() {
        actAsNewOrg();
        UUID id = service.create(create("Republished", List.of(passFail("Exit clear?")))).id();
        PublishResponse first = service.publish(id, null);

        service.createDraft(id, new NewDraftRequest(1));            // v1's content again
        PublishResponse again = service.publish(id, null);

        // The draft is dropped and v1 stays current; its rows were written the
        // first time and are not written twice.
        assertThat(again.newVersion()).isFalse();
        assertThat(refs.findAll())
                .extracting(VersionResultTypeRef::getVersionId)
                .containsOnly(first.version().id())
                .hasSize(2);
    }

    @Test
    void anAnswerOnAFollowUpCountsAsMuchAsATopLevelOne() {
        actAsNewOrg();
        // A result type that only the follow-up uses, so a missed follow-up
        // would show up as a missing row.
        resultTypes.create(new ResultTypeRequest("REQUIRED", "Required", "#e67e22", null, null));

        UUID id = service.create(create("With a follow-up", List.of(passFail("Extinguisher present?")))).id();
        VersionResponse draft = service.getDraft(id);
        Item parent = draft.definition().items().get(0);
        String noKey = parent.options().get(1).key();             // "No", minted on create

        Item followUp = new Item(null, "What is needed?", null, QuestionType.DROPDOWN, false, false,
                List.of(new Option(null, "Replace it", "REQUIRED", null, false), new Option(null, "Nothing yet", "PASS", null, false)),
                null, null, null, false, null, false, null, null, null, noKey, null, List.of(), List.of());
        service.saveDraft(id, new SaveDraftRequest(
                doc(List.of(parent.withFollow(List.of(followUp)))), draft.rowVersion(), null));

        PublishResponse published = service.publish(id, null);

        assertThat(refs.findAll())
                .extracting(VersionResultTypeRef::getResultTypeKey)
                .containsExactlyInAnyOrder("FAIL", "PASS", "REQUIRED");
        assertThat(refs.countByResultTypeKey("REQUIRED")).isEqualTo(1);
        assertThat(published.newVersion()).isTrue();
    }

    @Test
    void aResultTypeNamedOnlyByAScoreThresholdIsStillRecorded() {
        // The gap the feature 5 / feature 6 merge opened. resultTypeKeys() was
        // written when answers and reading bands were the only places a result
        // key could appear, and thresholds arrived from the other branch without
        // it. A type only the score scale named therefore got no row, the delete
        // guard counted none, and it could be deleted out from under a published
        // version that still pointed at it.
        actAsNewOrg();
        resultTypes.create(new ResultTypeRequest("AMBER", "Amber", "#f59e0b", null, null));

        UUID id = service.create(new CreateTemplateRequest("Scored walk", null, null,
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA,
                        List.of(scored(passFail("Exit clear?"), 10, 0)),
                        List.of(new Threshold(null, null, 69, "FAIL"),
                                new Threshold(null, 70, 89, "AMBER"),
                                new Threshold(null, 90, null, "PASS")), List.of()))).id(), List.of()))).id();
        PublishResponse published = service.publish(id, null);

        assertThat(refs.findAll())
                .extracting(VersionResultTypeRef::getResultTypeKey)
                .containsExactlyInAnyOrder("PASS", "FAIL", "AMBER");

        // And the guard that depends on it now refuses the delete.
        UUID amber = resultTypes.list(null).stream()
                .filter(r -> r.key().equals("AMBER")).findFirst().orElseThrow().id();
        assertThatThrownBy(() -> resultTypes.delete(amber))
                .hasMessageContaining("cannot be deleted")
                .hasMessageContaining("deactivate it instead");
        assertThat(published.version().versionNo()).isEqualTo(1);
    }

    // --- builders -----------------------------------------------------------

    private static CreateTemplateRequest create(String name, List<Item> items) {
        return new CreateTemplateRequest(name, null, null, doc(items));
    }

    private static DefinitionDocument doc(List<Item> items) {
        return new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, items, List.of(), List.of());
    }

    /** The same question with points on its answers, so the document scores. */
    private static Item scored(Item question, Integer yes, Integer no) {
        return question.withOptions(List.of(
                new Option(null, "Yes", "PASS", yes, false),
                new Option(null, "No", "FAIL", no, false)));
    }

    /** A Yes/No question mapped the usual way: Yes passes, No fails. */
    private static Item passFail(String text) {
        return new Item(null, text, null, QuestionType.YES_NO, true, false,
                List.of(new Option(null, "Yes", "PASS", null, false), new Option(null, "No", "FAIL", null, false)),
                null, null, null, false, null, false, null, null, null, null, null, List.of(), List.of());
    }
}
