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

    private static Item question() {
        return new Item("q2", "Exit clear?", null, QuestionType.YES_NO, true, false,
                List.of(new Option("o3", "Yes", "PASS", null, false), new Option("o4", "No", "FAIL", null, false)),
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

        // docA cited twice (procedure level and against q2); docB once.
        PublishResponse published = asOrg(org, () -> {
            UUID id = service.create(request(
                    new DocumentRef(docA.toString(), null),
                    new DocumentRef(docA.toString(), "q2"),
                    new DocumentRef(docB.toString(), null))).id();
            return service.publish(id, null);
        });

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
