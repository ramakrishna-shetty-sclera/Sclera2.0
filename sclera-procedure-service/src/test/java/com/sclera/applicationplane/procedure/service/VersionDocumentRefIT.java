package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.DocumentRef;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.ProcedureDocument;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.VersionDocumentRef;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.NewDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.PublishResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.SaveDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.repository.ProcedureDocumentRepository;
import com.sclera.applicationplane.procedure.repository.VersionDocumentRefRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The index of which documents each published version cites — written at
 * publish, and only there. Same pattern as {@link VersionResultTypeRefIT},
 * copied deliberately.
 *
 * Each test runs in a brand-new organization, so its schema starts with an
 * empty index and every row found was written by that test.
 */
class VersionDocumentRefIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService service;

    @Autowired
    private ProcedureDocumentRepository documents;

    @Autowired
    private VersionDocumentRefRepository refs;

    private UUID document(UUID orgId) {
        ProcedureDocument document = new ProcedureDocument();
        document.setOrgId(orgId);
        document.setName("Standard.pdf");
        document.setLocation("stub-" + UUID.randomUUID());
        document.setUploadedAt(OffsetDateTime.now());
        return documents.saveAndFlush(document).getId();
    }

    /** Keys are left unset: a new template has issued none, so the minter assigns them. */
    private static Item question() {
        return new Item(null, "Exit clear?", null, QuestionType.YES_NO, true, false,
                List.of(new Option(null, "Yes", "PASS", null, false), new Option(null, "No", "FAIL", null, false)),
                null, null, null, false, null, false, null, null, null, null, null, List.of(), List.of());
    }

    private static CreateTemplateRequest request(DocumentRef... refs) {
        return new CreateTemplateRequest("Fire check", null, null,
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(question()), List.of(), List.of(refs)));
    }

    @Test
    void publishingRecordsEachDistinctDocumentOnce() {
        UUID org = UUID.randomUUID();
        UUID docA = asOrg(org, () -> document(org));
        UUID docB = asOrg(org, () -> document(org));

        PublishResponse published = asOrg(org, () -> {
            UUID id = service.create(request()).id();
            // The question's key is minted on create, so a citation hanging off
            // that question can only be written once the key is known.
            VersionResponse draft = service.getDraft(id);
            String questionKey = draft.definition().items().get(0).key();
            service.saveDraft(id, new SaveDraftRequest(
                    new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA,
                            draft.definition().items(), List.of(),
                            List.of(new DocumentRef(docA.toString(), null),
                                    new DocumentRef(docA.toString(), questionKey),
                                    new DocumentRef(docB.toString(), null))),
                    draft.rowVersion(), null));
            return service.publish(id, null);
        });

        // docA is cited twice — at procedure level and against the question —
        // and gets one row, not two.
        assertThat(asOrg(org, () -> { return refs.findAll(); }))
                .extracting(VersionDocumentRef::getVersionId, VersionDocumentRef::getDocumentId)
                .containsExactlyInAnyOrder(
                        tuple(published.version().id(), docA),
                        tuple(published.version().id(), docB));
    }

    @Test
    void aDraftRecordsNothing() {
        UUID org = UUID.randomUUID();
        UUID docId = asOrg(org, () -> document(org));
        UUID id = asOrg(org, () -> service.create(request(new DocumentRef(docId.toString(), null))).id());

        VersionResponse draft = asOrg(org, () -> service.getDraft(id));
        asOrg(org, () -> service.saveDraft(id, new SaveDraftRequest(draft.definition(), draft.rowVersion(), "edited")));

        assertThat(asOrg(org, () -> refs.count())).isZero();
    }

    @Test
    void republishingUnchangedContentAddsNoRows() {
        UUID org = UUID.randomUUID();
        UUID docId = asOrg(org, () -> document(org));
        UUID id = asOrg(org, () -> service.create(request(new DocumentRef(docId.toString(), null))).id());
        PublishResponse first = asOrg(org, () -> service.publish(id, null));

        asOrg(org, () -> service.createDraft(id, new NewDraftRequest(1)));   // v1's content again
        PublishResponse again = asOrg(org, () -> service.publish(id, null));

        assertThat(again.newVersion()).isFalse();
        assertThat(asOrg(org, () -> { return refs.findAll(); }))
                .extracting(VersionDocumentRef::getVersionId)
                .containsOnly(first.version().id())
                .hasSize(1);
    }
}
