package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
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

        Item followUp = new Item(null, "What is needed?", null, QuestionType.DROPDOWN, false,
                List.of(new Option(null, "Replace it", "REQUIRED"), new Option(null, "Nothing yet", "PASS")),
                null, null, null, false, null, null, null, noKey, List.of());
        service.saveDraft(id, new SaveDraftRequest(
                doc(List.of(parent.withFollow(List.of(followUp)))), draft.rowVersion(), null));

        PublishResponse published = service.publish(id, null);

        assertThat(refs.findAll())
                .extracting(VersionResultTypeRef::getResultTypeKey)
                .containsExactlyInAnyOrder("FAIL", "PASS", "REQUIRED");
        assertThat(refs.countByResultTypeKey("REQUIRED")).isEqualTo(1);
        assertThat(published.newVersion()).isTrue();
    }

    // --- builders -----------------------------------------------------------

    private static CreateTemplateRequest create(String name, List<Item> items) {
        return new CreateTemplateRequest(name, null, null, doc(items));
    }

    private static DefinitionDocument doc(List<Item> items) {
        return new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, items);
    }

    /** A Yes/No question mapped the usual way: Yes passes, No fails. */
    private static Item passFail(String text) {
        return new Item(null, text, null, QuestionType.YES_NO, true,
                List.of(new Option(null, "Yes", "PASS"), new Option(null, "No", "FAIL")),
                null, null, null, false, null, null, null, null, List.of());
    }
}
