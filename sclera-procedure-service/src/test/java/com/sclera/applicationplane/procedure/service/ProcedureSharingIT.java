package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.DocumentRef;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.LinkState;
import com.sclera.applicationplane.procedure.domain.ProcedureDocument;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.ResultType;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryDtos.ExportedProcedure;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryDtos.ExportedVersion;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryDtos.ImportRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.NewDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.SaveDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.TemplateResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateVersionRepository;
import com.sclera.applicationplane.procedure.repository.GlobalTemplateOrgCopyRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureDocumentRepository;
import com.sclera.applicationplane.procedure.repository.ResultTypeRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ConflictException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.exception.ValidationException;
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

    @Autowired
    private GlobalProcedureTemplateRepository globalTemplates;

    @Autowired
    private GlobalProcedureTemplateVersionRepository globalVersions;

    @Autowired
    private GlobalTemplateOrgCopyRepository orgCopies;

    @Autowired
    private ResultTypeRepository resultTypes;

    private static Item question() {
        return Item.builder().text("Exit clear?").type(QuestionType.YES_NO).required(true)
                .options(List.of(new Option(null, "Yes", "PASS", null, false), new Option(null, "No", "FAIL", null, false)))
                .build();
    }

    private static CreateTemplateRequest request(String name, DocumentRef... refs) {
        return new CreateTemplateRequest(name, "A description", null,
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(question()), List.of(), List.of(), List.of(refs)));
    }

    /** A published global template — there is no authoring flow for the
     * Sclera-wide library in this feature, so tests seed one directly. */
    private GlobalProcedureTemplate publishedGlobalTemplate(String name, String description, Option... options) {
        GlobalProcedureTemplate t = new GlobalProcedureTemplate();
        t.setName(name);
        t.setDescription(description);
        t.setConsumerKey("INSPECTION");
        t.setKeySeq(2);
        t = globalTemplates.saveAndFlush(t);

        DefinitionDocument document = new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA,
                List.of(Item.builder().key("q1").text("Exit clear?").type(QuestionType.YES_NO).required(true)
                        .options(List.of(options)).build()),
                List.of(), List.of(), List.of());
        DefinitionCanonicalizer.Canonical canonical = canonicalizer.canonicalize(document);

        GlobalProcedureTemplateVersion v = new GlobalProcedureTemplateVersion();
        v.setGlobalTemplateId(t.getId());
        v.setVersionNo(1);
        v.setState(VersionState.PUBLISHED);
        v.setDefinitionJson(canonical.json());
        v.setDefinitionHash(canonical.hash());
        globalVersions.saveAndFlush(v);

        t.setCurrentPublishedVersionId(v.getId());
        return globalTemplates.saveAndFlush(t);
    }

    private static Option opt(String key, String label, String result) {
        return new Option(key, label, result, null, false);
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

    @Nested
    class Import {

        @Test
        void importingAsNewCreatesATemplateLinkedToTheGlobalSource() {
            UUID org = actAsNewOrg();
            GlobalProcedureTemplate global = publishedGlobalTemplate("NFPA 10", "Fire extinguisher check",
                    opt("o2", "Yes", "PASS"), opt("o3", "No", "FAIL"));

            TemplateResponse imported = service.importFromGlobal(
                    new ImportRequest(global.getId(), null, null, "Fire extinguisher walk"));

            assertThat(imported.name()).isEqualTo("Fire extinguisher walk");
            assertThat(imported.draftVersionNo()).isEqualTo(1);
            VersionResponse draft = service.getDraft(imported.id());
            assertThat(draft.definition().items()).extracting(Item::key).containsExactly("q1");

            var copy = orgCopies.findByGlobalTemplateIdAndOrgId(global.getId(), org).orElseThrow();
            assertThat(copy.getTemplateId()).isEqualTo(imported.id());
            assertThat(copy.getLinkState()).isEqualTo(LinkState.LINKED);
            assertThat(copy.getAppliedVersionNo()).isEqualTo(1);
        }

        @Test
        void importingAsNewRequiresAName() {
            actAsNewOrg();
            GlobalProcedureTemplate global = publishedGlobalTemplate("NFPA 10", null,
                    opt("o2", "Yes", "PASS"), opt("o3", "No", "FAIL"));

            assertThatThrownBy(() -> service.importFromGlobal(new ImportRequest(global.getId(), null, null, null)))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void anUnknownGlobalTemplateIsRefused() {
            actAsNewOrg();

            assertThatThrownBy(() -> service.importFromGlobal(new ImportRequest(UUID.randomUUID(), null, null, "X")))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void aNeverPublishedGlobalTemplateHasNothingToImport() {
            actAsNewOrg();
            GlobalProcedureTemplate global = new GlobalProcedureTemplate();
            global.setName("Draft only");
            global = globalTemplates.saveAndFlush(global);

            UUID id = global.getId();
            assertThatThrownBy(() -> service.importFromGlobal(new ImportRequest(id, null, null, "X")))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("never been published");
        }

        @Test
        void importingMapsAndCreatesMissingResultTypes() {
            UUID org = actAsNewOrg();
            GlobalProcedureTemplate global = publishedGlobalTemplate("Gauge check", null,
                    opt("o2", "Yes", "PASS"), opt("o3", "Partly", "AMBER"));
            assertThat(resultTypes.findAllByOrgIdOrderBySeverityOrderAsc(org))
                    .extracting(ResultType::getKey).doesNotContain("AMBER");

            service.importFromGlobal(new ImportRequest(global.getId(), null, null, "Gauge walk"));

            assertThat(resultTypes.findAllByOrgIdOrderBySeverityOrderAsc(org))
                    .extracting(ResultType::getKey).contains("AMBER");
        }

        @Test
        void importingIntoAnExistingTemplateCreatesANewDraft() {
            actAsNewOrg();
            GlobalProcedureTemplate global = publishedGlobalTemplate("NFPA 10", null,
                    opt("o2", "Yes", "PASS"), opt("o3", "No", "FAIL"));
            TemplateResponse first = service.importFromGlobal(new ImportRequest(global.getId(), null, null, "Fire walk"));
            service.publish(first.id(), null);

            TemplateResponse updated = service.importFromGlobal(
                    new ImportRequest(global.getId(), null, first.id(), null));

            assertThat(updated.id()).isEqualTo(first.id());
            assertThat(updated.draftVersionNo()).isEqualTo(2);
        }

        @Test
        void importingIntoATemplateThatAlreadyHasADraftIsRefused() {
            actAsNewOrg();
            GlobalProcedureTemplate global = publishedGlobalTemplate("NFPA 10", null,
                    opt("o2", "Yes", "PASS"), opt("o3", "No", "FAIL"));
            TemplateResponse first = service.importFromGlobal(new ImportRequest(global.getId(), null, null, "Fire walk"));
            // The import itself left v1 as an unpublished draft.

            assertThatThrownBy(() -> service.importFromGlobal(new ImportRequest(global.getId(), null, first.id(), null)))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void reImportingAsNewRepointsTheExistingLinkRatherThanDuplicatingIt() {
            // The link table's primary key is (global template, org), so a
            // second row for the same pair is impossible by construction -
            // what matters is that the one row that can exist points at the
            // newest copy, not the one from the first import.
            UUID org = actAsNewOrg();
            GlobalProcedureTemplate global = publishedGlobalTemplate("NFPA 10", null,
                    opt("o2", "Yes", "PASS"), opt("o3", "No", "FAIL"));
            service.importFromGlobal(new ImportRequest(global.getId(), null, null, "First copy"));

            TemplateResponse second = service.importFromGlobal(
                    new ImportRequest(global.getId(), null, null, "Second copy"));

            assertThat(orgCopies.findByGlobalTemplateIdAndOrgId(global.getId(), org).orElseThrow().getTemplateId())
                    .isEqualTo(second.id());
        }

        @Test
        void importingFromInsideAPropertySetsItsScope() {
            UUID org = actAsNewOrg();
            GlobalProcedureTemplate global = publishedGlobalTemplate("NFPA 10", null,
                    opt("o2", "Yes", "PASS"), opt("o3", "No", "FAIL"));
            UUID property = UUID.randomUUID();

            TemplateResponse imported = asProperty(org, property, () ->
                    service.importFromGlobal(new ImportRequest(global.getId(), null, null, "Fire walk")));

            assertThat(imported.propertyId()).isEqualTo(property);
        }
    }
}
