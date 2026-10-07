package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.client.storage.StorageClient;
import com.sclera.applicationplane.procedure.dto.ProcedureDocumentRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureDocumentResponse;
import com.sclera.applicationplane.procedure.repository.ProcedureDocumentRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The reference-document library, against a real Postgres: row-level
 * security is exactly {@code procedure_template}'s mechanism applied to a
 * second table, so this both proves the library's own behaviour and repeats
 * {@link PropertyIsolationIT}'s shape against it, per the plan's own
 * verification checklist.
 */
class ProcedureDocumentServiceIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureDocumentService service;

    @Autowired
    private ProcedureDocumentRepository repository;

    @MockBean
    private StorageClient storage;

    private ProcedureDocumentRequest request(String name, String location) {
        return new ProcedureDocumentRequest(name, "application/pdf", 1024L, location);
    }

    private List<String> visibleNames() {
        return service.list(null).stream().map(ProcedureDocumentResponse::name).toList();
    }

    @Test
    void refusesALocationStorageDoesNotRecognise() {
        UUID org = UUID.randomUUID();
        when(storage.resolves(anyString())).thenReturn(false);

        assertThatThrownBy(() -> asOrg(org, () -> service.create(request("NFPA 10.pdf", "stub-missing"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No stored document found");
    }

    @Test
    void aDocumentBelongsToWhicheverPropertyItWasUploadedIn() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();
        when(storage.resolves(anyString())).thenReturn(true);

        ProcedureDocumentResponse inProperty =
                asProperty(org, vdms001, () -> service.create(request("Local code.pdf", "stub-a")));
        ProcedureDocumentResponse orgWide =
                asOrg(org, () -> service.create(request("NFPA 10.pdf", "stub-b")));

        assertThat(inProperty.propertyId()).isEqualTo(vdms001);
        assertThat(orgWide.propertyId()).isNull();
    }

    @Test
    void onePropertyCannotSeeAnothersDocuments() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();
        UUID vdms002 = UUID.randomUUID();
        when(storage.resolves(anyString())).thenReturn(true);

        asProperty(org, vdms001, () -> service.create(request("VDMS001 only.pdf", "stub-a")));
        asProperty(org, vdms002, () -> service.create(request("VDMS002 only.pdf", "stub-b")));
        asOrg(org, () -> service.create(request("Shared standard.pdf", "stub-c")));

        assertThat(asProperty(org, vdms001, this::visibleNames))
                .containsExactlyInAnyOrder("VDMS001 only.pdf", "Shared standard.pdf");
        assertThat(asProperty(org, vdms002, this::visibleNames))
                .containsExactlyInAnyOrder("VDMS002 only.pdf", "Shared standard.pdf");
    }

    @Test
    void organizationLevelSeesOnlyWhatIsShared() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();
        when(storage.resolves(anyString())).thenReturn(true);

        asProperty(org, vdms001, () -> service.create(request("Property only.pdf", "stub-a")));
        asOrg(org, () -> service.create(request("Shared standard.pdf", "stub-b")));

        assertThat(asOrg(org, this::visibleNames)).containsExactly("Shared standard.pdf");
    }

    @Test
    void aQueryThatForgetsToFilterStillCannotCrossProperties() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();
        UUID vdms002 = UUID.randomUUID();
        when(storage.resolves(anyString())).thenReturn(true);

        asProperty(org, vdms001, () -> service.create(request("A.pdf", "stub-a")));
        asProperty(org, vdms002, () -> service.create(request("B.pdf", "stub-b")));
        asProperty(org, vdms002, () -> service.create(request("C.pdf", "stub-c")));

        long seenFromVdms001 = asProperty(org, vdms001, () -> repository.findAll().size());
        long seenFromVdms002 = asProperty(org, vdms002, () -> repository.findAll().size());

        assertThat(seenFromVdms001).isEqualTo(1);
        assertThat(seenFromVdms002).isEqualTo(2);
    }

    @Test
    void deactivatingHidesItFromNewCitationsButActivateBringsItBack() {
        UUID org = UUID.randomUUID();
        when(storage.resolves(anyString())).thenReturn(true);

        UUID id = asOrg(org, () -> service.create(request("Draft standard.pdf", "stub-a")).id());

        asOrg(org, () -> service.deactivate(id));
        assertThat(asOrg(org, () -> service.list(true))).isEmpty();
        assertThat(asOrg(org, () -> service.list(false))).extracting(ProcedureDocumentResponse::id).containsExactly(id);

        asOrg(org, () -> service.activate(id));
        assertThat(asOrg(org, () -> service.list(true))).extracting(ProcedureDocumentResponse::id).containsExactly(id);
    }

    @Test
    void aDocumentFromAnotherOrganizationIsNotFound() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        when(storage.resolves(anyString())).thenReturn(true);

        UUID id = asOrg(orgA, () -> service.create(request("Org A only.pdf", "stub-a")).id());

        assertThatThrownBy(() -> asOrg(orgB, () -> service.get(id)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
