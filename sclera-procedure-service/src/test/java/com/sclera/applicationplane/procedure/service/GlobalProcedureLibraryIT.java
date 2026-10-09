package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.GlobalTemplateOrgCopy;
import com.sclera.applicationplane.procedure.domain.LinkState;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateVersionRepository;
import com.sclera.applicationplane.procedure.repository.GlobalTemplateOrgCopyRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the one real risk in this feature empirically rather than by theory.
 * The three global tables live in {@code public}; a per-request connection's
 * search_path is pinned to exactly the requesting organization's own tenant
 * schema with no {@code public} fallback (see
 * {@code SchemaMultiTenantConnectionProvider.setSearchPath}), so an entity
 * reached through an ordinary tenant-scoped request only resolves at all
 * because it is schema-qualified ({@code @Table(schema = "public", ...)}).
 * This is the first time that pattern is exercised anywhere in this service —
 * nothing else maps a JPA entity into {@code public} and reads it through the
 * normal per-request (app-role) connection, so it is checked here rather than
 * assumed.
 */
class GlobalProcedureLibraryIT extends PostgresIntegrationTest {

    @Autowired
    private GlobalProcedureTemplateRepository globalTemplates;

    @Autowired
    private GlobalProcedureTemplateVersionRepository globalVersions;

    @Autowired
    private GlobalTemplateOrgCopyRepository orgCopies;

    @Test
    void aGlobalTemplateIsReachableFromInsideAnOrdinaryTenantScopedRequest() {
        // Standing as an organization pins search_path to that organization's
        // own schema - nowhere near "public". If the entity were not
        // schema-qualified, this would fail with "relation does not exist",
        // not return empty.
        UUID org = actAsNewOrg();

        GlobalProcedureTemplate template = asOrg(org, () -> {
            GlobalProcedureTemplate t = new GlobalProcedureTemplate();
            t.setName("NFPA 10 - Fire extinguisher inspection");
            t.setConsumerKey("INSPECTION");
            return globalTemplates.saveAndFlush(t);
        });

        GlobalProcedureTemplate found =
                asOrg(org, () -> globalTemplates.findById(template.getId())).orElseThrow();

        assertThat(found.getName()).isEqualTo("NFPA 10 - Fire extinguisher inspection");
        assertThat(found.getStatus()).isEqualTo(TemplateStatus.ACTIVE);
    }

    @Test
    void aGlobalVersionRoundTripsItsCanonicalBytes() {
        UUID org = actAsNewOrg();

        UUID versionId = asOrg(org, () -> {
            GlobalProcedureTemplate t = new GlobalProcedureTemplate();
            t.setName("Boiler inspection");
            t = globalTemplates.saveAndFlush(t);

            GlobalProcedureTemplateVersion v = new GlobalProcedureTemplateVersion();
            v.setGlobalTemplateId(t.getId());
            v.setVersionNo(1);
            v.setState(VersionState.PUBLISHED);
            v.setDefinitionJson("{\"schema\":2,\"items\":[]}");
            v.setDefinitionHash("a".repeat(64));
            return globalVersions.saveAndFlush(v).getId();
        });

        GlobalProcedureTemplateVersion found = asOrg(org, () -> globalVersions.findById(versionId)).orElseThrow();

        assertThat(found.getDefinitionJson()).isEqualTo("{\"schema\":2,\"items\":[]}");
        assertThat(found.getVersionNo()).isEqualTo(1);
        assertThat(found.getState()).isEqualTo(VersionState.PUBLISHED);
    }

    /** A real, persisted global template to satisfy the FK every org copy needs. */
    private UUID newGlobalTemplate(UUID actingOrg) {
        return asOrg(actingOrg, () -> {
            GlobalProcedureTemplate t = new GlobalProcedureTemplate();
            t.setName("Shared procedure " + UUID.randomUUID());
            return globalTemplates.saveAndFlush(t).getId();
        });
    }

    @Test
    void anOrgCopyIsInvisibleToTheWrongOrgEvenWithNoSchemaBoundaryToStopIt() {
        // The hostile test: this table has no row-level security and lives in
        // the one schema every organization's requests can reach, so the
        // org_id filter in the repository method is the only thing protecting
        // the boundary. Prove it holds rather than assume it does.
        UUID owner = actAsNewOrg();
        UUID intruder = actAsNewOrg();
        UUID globalTemplateId = newGlobalTemplate(owner);

        UUID ownersTemplateId = UUID.randomUUID();
        asOrg(owner, () -> orgCopies.saveAndFlush(
                new GlobalTemplateOrgCopy(globalTemplateId, owner, ownersTemplateId, 1, OffsetDateTime.now())));

        assertThat(asOrg(owner, () -> orgCopies.findByGlobalTemplateIdAndOrgId(globalTemplateId, owner)))
                .isPresent();
        assertThat(asOrg(intruder, () -> orgCopies.findByGlobalTemplateIdAndOrgId(globalTemplateId, intruder)))
                .isEmpty();
    }

    @Test
    void aFreshLinkStartsLinkedWithNothingDeferred() {
        UUID org = actAsNewOrg();
        UUID globalTemplateId = newGlobalTemplate(org);
        UUID templateId = UUID.randomUUID();

        GlobalTemplateOrgCopy copy = asOrg(org, () -> orgCopies.saveAndFlush(
                new GlobalTemplateOrgCopy(globalTemplateId, org, templateId, 1, OffsetDateTime.now())));

        assertThat(copy.getLinkState()).isEqualTo(LinkState.LINKED);
        assertThat(copy.getDeferredVersionNo()).isNull();
        assertThat(copy.getDeferredAt()).isNull();
    }
}
