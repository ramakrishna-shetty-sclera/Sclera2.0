package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.dto.ProcedureDocumentResponse;
import com.sclera.applicationplane.procedure.repository.ProcedureDocumentRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private MultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/pdf",
                ("content of " + name).getBytes(StandardCharsets.UTF_8));
    }

    private List<String> visibleNames() {
        return service.list(null).stream().map(ProcedureDocumentResponse::name).toList();
    }

    @Test
    void refusesAnEmptyFile() {
        UUID org = UUID.randomUUID();
        MultipartFile empty = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> asOrg(org, () -> service.upload(empty)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void aDocumentBelongsToWhicheverPropertyItWasUploadedIn() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();

        ProcedureDocumentResponse inProperty =
                asProperty(org, vdms001, () -> service.upload(file("Local code.pdf")));
        ProcedureDocumentResponse orgWide =
                asOrg(org, () -> service.upload(file("NFPA 10.pdf")));

        assertThat(inProperty.propertyId()).isEqualTo(vdms001);
        assertThat(orgWide.propertyId()).isNull();
    }

    @Test
    void onePropertyCannotSeeAnothersDocuments() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();
        UUID vdms002 = UUID.randomUUID();

        asProperty(org, vdms001, () -> service.upload(file("VDMS001 only.pdf")));
        asProperty(org, vdms002, () -> service.upload(file("VDMS002 only.pdf")));
        asOrg(org, () -> service.upload(file("Shared standard.pdf")));

        assertThat(asProperty(org, vdms001, this::visibleNames))
                .containsExactlyInAnyOrder("VDMS001 only.pdf", "Shared standard.pdf");
        assertThat(asProperty(org, vdms002, this::visibleNames))
                .containsExactlyInAnyOrder("VDMS002 only.pdf", "Shared standard.pdf");
    }

    @Test
    void organizationLevelSeesOnlyWhatIsShared() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();

        asProperty(org, vdms001, () -> service.upload(file("Property only.pdf")));
        asOrg(org, () -> service.upload(file("Shared standard.pdf")));

        assertThat(asOrg(org, this::visibleNames)).containsExactly("Shared standard.pdf");
    }

    @Test
    void aQueryThatForgetsToFilterStillCannotCrossProperties() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();
        UUID vdms002 = UUID.randomUUID();

        asProperty(org, vdms001, () -> service.upload(file("A.pdf")));
        asProperty(org, vdms002, () -> service.upload(file("B.pdf")));
        asProperty(org, vdms002, () -> service.upload(file("C.pdf")));

        long seenFromVdms001 = asProperty(org, vdms001, () -> repository.findAll().size());
        long seenFromVdms002 = asProperty(org, vdms002, () -> repository.findAll().size());

        assertThat(seenFromVdms001).isEqualTo(1);
        assertThat(seenFromVdms002).isEqualTo(2);
    }

    @Test
    void deactivatingHidesItFromNewCitationsButActivateBringsItBack() {
        UUID org = UUID.randomUUID();

        UUID id = asOrg(org, () -> service.upload(file("Draft standard.pdf")).id());

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

        UUID id = asOrg(orgA, () -> service.upload(file("Org A only.pdf")).id());

        assertThatThrownBy(() -> asOrg(orgB, () -> service.get(id)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
