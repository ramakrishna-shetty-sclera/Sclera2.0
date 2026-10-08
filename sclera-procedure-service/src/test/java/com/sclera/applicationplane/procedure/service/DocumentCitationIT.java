package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.DocumentRef;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.ProcedureDocument;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.PublishResponse;
import com.sclera.applicationplane.procedure.repository.ProcedureDocumentRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A version's document citations, checked at publish against real Postgres
 * and row-level security. {@link ProcedureTemplateService}'s
 * {@code citableDocumentIds} is the wiring this feature adds, and the
 * isolation rule it enforces (decision 4 of Feature 9) is the one thing here
 * worth proving in both directions — only testing the allowed direction
 * proves nothing.
 *
 * Documents are inserted directly through {@link ProcedureDocumentRepository}
 * rather than {@code ProcedureDocumentService.create}, since this is about
 * the citation check at publish, not the upload path.
 */
class DocumentCitationIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService service;

    @Autowired
    private ProcedureDocumentService documentService;

    @Autowired
    private ProcedureDocumentRepository documents;

    private UUID document(UUID orgId, UUID propertyId) {
        ProcedureDocument document = new ProcedureDocument();
        document.setOrgId(orgId);
        document.setPropertyId(propertyId);
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
    void anOrganizationWideProcedureCannotCiteAPropertysDocument() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();
        UUID docId = asProperty(org, vdms001, () -> document(org, vdms001));

        UUID id = asOrg(org, () -> service.create(request(new DocumentRef(docId.toString(), null))).id());

        assertThatThrownBy(() -> asOrg(org, () -> service.publish(id, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("does not exist, is inactive, or is not visible");
    }

    @Test
    void aPropertysProcedureCanCiteTheOrganizationsDocument() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();
        UUID docId = asOrg(org, () -> document(org, null));

        UUID id = asProperty(org, vdms001, () -> service.create(request(new DocumentRef(docId.toString(), null))).id());
        PublishResponse published = asProperty(org, vdms001, () -> service.publish(id, null));

        assertThat(published.newVersion()).isTrue();
    }

    @Test
    void aPropertysProcedureCanCiteItsOwnPropertysDocument() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();
        UUID docId = asProperty(org, vdms001, () -> document(org, vdms001));

        UUID id = asProperty(org, vdms001, () -> service.create(request(new DocumentRef(docId.toString(), null))).id());
        PublishResponse published = asProperty(org, vdms001, () -> service.publish(id, null));

        assertThat(published.newVersion()).isTrue();
    }

    @Test
    void aPropertysProcedureCannotCiteAnotherPropertysDocument() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();
        UUID vdms002 = UUID.randomUUID();
        UUID docId = asProperty(org, vdms002, () -> document(org, vdms002));

        UUID id = asProperty(org, vdms001, () -> service.create(request(new DocumentRef(docId.toString(), null))).id());

        assertThatThrownBy(() -> asProperty(org, vdms001, () -> service.publish(id, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("does not exist, is inactive, or is not visible");
    }

    @Test
    void citingANonexistentDocumentRefusesPublish() {
        UUID org = UUID.randomUUID();
        UUID id = asOrg(org, () -> service.create(request(new DocumentRef("not-a-real-id", null))).id());

        assertThatThrownBy(() -> asOrg(org, () -> service.publish(id, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not-a-real-id does not exist");
    }

    @Test
    void aDocumentCitedAtProcedureLevelPublishesFine() {
        UUID org = UUID.randomUUID();
        UUID docId = asOrg(org, () -> document(org, null));

        UUID id = asOrg(org, () -> service.create(request(new DocumentRef(docId.toString(), null))).id());
        PublishResponse published = asOrg(org, () -> service.publish(id, null));

        assertThat(published.newVersion()).isTrue();
    }

    @Test
    void aQuestionKeyThatNoLongerExistsRefusesPublish() {
        UUID org = UUID.randomUUID();
        UUID docId = asOrg(org, () -> document(org, null));

        UUID id = asOrg(org, () -> service.create(request(new DocumentRef(docId.toString(), "q99"))).id());

        assertThatThrownBy(() -> asOrg(org, () -> service.publish(id, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("is attached to question 'q99', which does not exist");
    }

    // --- the delete guard -----------------------------------------------------

    @Test
    void aDocumentNothingCitesCanStillBeDeleted() {
        UUID org = UUID.randomUUID();
        UUID docId = asOrg(org, () -> document(org, null));

        asOrg(org, () -> documentService.delete(docId));

        assertThat(asOrg(org, () -> documents.findById(docId))).isEmpty();
    }

    @Test
    void aDocumentAPublishedVersionCitesCannotBeDeleted() {
        UUID org = UUID.randomUUID();
        UUID docId = asOrg(org, () -> document(org, null));
        asOrg(org, () -> {
            UUID id = service.create(request(new DocumentRef(docId.toString(), null))).id();
            service.publish(id, null);
            return id;
        });

        assertThatThrownBy(() -> asOrg(org, () -> documentService.delete(docId)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("is used by 1 published version")
                .hasMessageContaining("deactivate it instead");
        assertThat(asOrg(org, () -> documents.findById(docId))).isPresent();
    }

    @Test
    void aDocumentOnlyADraftCitesCanStillBeDeleted() {
        UUID org = UUID.randomUUID();
        UUID docId = asOrg(org, () -> document(org, null));
        asOrg(org, () -> service.create(request(new DocumentRef(docId.toString(), null))));  // never published

        asOrg(org, () -> documentService.delete(docId));

        assertThat(asOrg(org, () -> documents.findById(docId))).isEmpty();
    }
}
