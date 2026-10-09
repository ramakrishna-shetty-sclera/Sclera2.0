package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.DocumentRef;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.ProcedureDocument;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryDtos.ExportedProcedure;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryDtos.ExportedVersion;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.NewDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.SaveDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.repository.ProcedureDocumentRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Export and import — feature 10. Export packages a template's versions for
 * sharing; import (added alongside it) pulls one back in from the Sclera-wide
 * library. Both sides land in this one file since they are two ends of the
 * same story.
 */
class ProcedureSharingIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService service;

    @Autowired
    private ProcedureDocumentRepository documents;

    @Autowired
    private DefinitionCanonicalizer canonicalizer;

    private static Item question() {
        return Item.builder().text("Exit clear?").type(QuestionType.YES_NO).required(true)
                .options(List.of(new Option(null, "Yes", "PASS", null, false), new Option(null, "No", "FAIL", null, false)))
                .build();
    }

    private static CreateTemplateRequest request(String name, DocumentRef... refs) {
        return new CreateTemplateRequest(name, "A description", null,
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(question()), List.of(), List.of(), List.of(refs)));
    }

    private UUID orgWideDocument(UUID orgId) {
        ProcedureDocument document = new ProcedureDocument();
        document.setOrgId(orgId);
        document.setName("Standard.pdf");
        document.setLocation("stub-" + UUID.randomUUID());
        document.setUploadedAt(OffsetDateTime.now());
        return documents.saveAndFlush(document).getId();
    }

    @Nested
    class Export {

        @Test
        void withNoVersionsGivenExportsTheCurrentPublishedOne() {
            actAsNewOrg();
            UUID id = service.create(request("Fire walk")).id();
            service.publish(id, null);

            ExportedProcedure exported = service.export(id, null, false);

            assertThat(exported.name()).isEqualTo("Fire walk");
            assertThat(exported.description()).isEqualTo("A description");
            assertThat(exported.versions()).singleElement()
                    .extracting(ExportedVersion::versionNo).isEqualTo(1);
        }

        @Test
        void aNeverPublishedTemplateHasNothingToExport() {
            actAsNewOrg();
            UUID id = service.create(request("Fire walk")).id();

            assertThatThrownBy(() -> service.export(id, null, false))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("never been published");
        }

        @Test
        void specificVersionsCanBeExportedTogether() {
            actAsNewOrg();
            UUID id = service.create(request("Fire walk")).id();
            service.publish(id, null);
            VersionResponse draft = service.createDraft(id, new NewDraftRequest(1));
            service.saveDraft(id, new SaveDraftRequest(
                    new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA,
                            List.of(question()), List.of(), List.of(), List.of()),
                    draft.rowVersion(), "v2"));
            service.publish(id, null);

            ExportedProcedure exported = service.export(id, List.of(1, 2), false);

            assertThat(exported.versions()).extracting(ExportedVersion::versionNo).containsExactly(1, 2);
        }

        @Test
        void anUnknownVersionNumberIsRefused() {
            actAsNewOrg();
            UUID id = service.create(request("Fire walk")).id();
            service.publish(id, null);

            assertThatThrownBy(() -> service.export(id, List.of(99), false))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void excludingDocumentsStripsTheCitationAndChangesTheHash() {
            UUID org = actAsNewOrg();
            UUID docId = orgWideDocument(org);
            UUID id = service.create(request("Fire walk", new DocumentRef(docId.toString(), null))).id();
            service.publish(id, null);

            ExportedProcedure withDocs = service.export(id, null, true);
            ExportedProcedure withoutDocs = service.export(id, null, false);

            assertThat(canonicalizer.parse(withDocs.versions().get(0).definitionJson()).documents()).hasSize(1);
            assertThat(canonicalizer.parse(withoutDocs.versions().get(0).definitionJson()).documents()).isEmpty();
            // Different content, so a different hash - the export describes
            // what it actually contains, not what the source version did.
            assertThat(withoutDocs.versions().get(0).definitionHash())
                    .isNotEqualTo(withDocs.versions().get(0).definitionHash());
        }

        @Test
        void includingDocumentsReusesTheStoredBytesVerbatim() {
            UUID org = actAsNewOrg();
            UUID docId = orgWideDocument(org);
            UUID id = service.create(request("Fire walk", new DocumentRef(docId.toString(), null))).id();
            service.publish(id, null);

            ExportedProcedure withDocs = service.export(id, null, true);

            assertThat(withDocs.versions().get(0).definitionHash())
                    .isEqualTo(service.getVersion(id, 1).definitionHash());
        }

        @Test
        void aVersionWithNoDocumentsIsUnaffectedByTheToggle() {
            actAsNewOrg();
            UUID id = service.create(request("Fire walk")).id();
            service.publish(id, null);

            ExportedProcedure withDocs = service.export(id, null, true);
            ExportedProcedure withoutDocs = service.export(id, null, false);

            assertThat(withDocs.versions().get(0).definitionHash())
                    .isEqualTo(withoutDocs.versions().get(0).definitionHash());
        }
    }
}
