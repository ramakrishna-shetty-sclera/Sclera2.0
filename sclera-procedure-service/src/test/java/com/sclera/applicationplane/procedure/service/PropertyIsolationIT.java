package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.TemplateResponse;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties of one organization cannot see each other's procedures.
 *
 * These run as {@code sclera_app} — not the container's superuser — because
 * Postgres ignores row-level security for owners and superusers. Run as the
 * owner, every assertion here would pass with no isolation in place at all,
 * which is the one way this file could be worse than useless.
 */
class PropertyIsolationIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService service;

    @Autowired
    private ProcedureTemplateRepository repository;

    @PersistenceContext
    private EntityManager em;

    private CreateTemplateRequest named(String name) {
        return new CreateTemplateRequest(name, null, null, null);
    }

    private List<String> visibleNames() {
        return repository.findAll(PageRequest.of(0, 50)).getContent()
                .stream().map(t -> t.getName()).toList();
    }

    /**
     * Guards every other test in this file.
     *
     * Postgres ignores row-level security for superusers and for a table's
     * owner. If the application ever connects as one of those, every isolation
     * assertion below passes with no isolation in place — the failure mode is
     * silent and total, so it is worth one test that cannot miss it.
     */
    @Test
    void theApplicationConnectsAsARoleThatRowLevelSecurityAppliesTo() {
        UUID org = UUID.randomUUID();

        Object[] who = asOrg(org, () -> (Object[]) em.createNativeQuery(
                "SELECT current_user, "
                        + "(SELECT rolsuper FROM pg_roles WHERE rolname = current_user), "
                        + "(SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user)")
                .getSingleResult());

        assertThat(who[0]).isEqualTo("sclera_app");
        assertThat(who[1]).as("superuser bypasses RLS entirely").isEqualTo(false);
        assertThat(who[2]).as("BYPASSRLS bypasses RLS entirely").isEqualTo(false);
    }

    @Test
    void aProcedureBelongsToWhicheverPropertyItWasAuthoredIn() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();

        TemplateResponse inProperty = asProperty(org, vdms001, () -> service.create(named("Fire extinguisher")));
        TemplateResponse orgWide = asOrg(org, () -> service.create(named("Site induction")));

        // Nothing on the request says "this is a property procedure" — standing
        // inside a property is what makes it one.
        assertThat(asProperty(org, vdms001, () -> repository.findById(inProperty.id()).orElseThrow().getPropertyId()))
                .isEqualTo(vdms001);
        assertThat(asOrg(org, () -> repository.findById(orgWide.id()).orElseThrow().getPropertyId()))
                .isNull();
    }

    @Test
    void onePropertyCannotSeeAnothersProcedures() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();
        UUID vdms002 = UUID.randomUUID();

        asProperty(org, vdms001, () -> service.create(named("Extinguisher — VDMS001")));
        asProperty(org, vdms002, () -> service.create(named("Boiler — VDMS002")));
        asOrg(org, () -> service.create(named("Site induction — shared")));

        // Each property sees its own work and the organization's, never the
        // other property's. Same organization, same schema, same table.
        assertThat(asProperty(org, vdms001, this::visibleNames))
                .containsExactlyInAnyOrder("Extinguisher — VDMS001", "Site induction — shared");
        assertThat(asProperty(org, vdms002, this::visibleNames))
                .containsExactlyInAnyOrder("Boiler — VDMS002", "Site induction — shared");
    }

    @Test
    void organizationLevelSeesOnlyWhatIsShared() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();

        asProperty(org, vdms001, () -> service.create(named("Extinguisher — VDMS001")));
        asOrg(org, () -> service.create(named("Site induction — shared")));

        // The library screen before a property is opened. Property work is not
        // hidden by a filter the screen remembered to apply — with no property
        // in scope it is not reachable at all.
        assertThat(asOrg(org, this::visibleNames)).containsExactly("Site induction — shared");
    }

    @Test
    void aQueryThatForgetsToFilterStillCannotCrossProperties() {
        UUID org = UUID.randomUUID();
        UUID vdms001 = UUID.randomUUID();
        UUID vdms002 = UUID.randomUUID();

        asProperty(org, vdms001, () -> service.create(named("Extinguisher — VDMS001")));
        asProperty(org, vdms002, () -> service.create(named("Boiler — VDMS002")));
        asProperty(org, vdms002, () -> service.create(named("Chiller — VDMS002")));

        // The whole claim of this feature, and the only test that actually
        // makes it: raw SQL, no WHERE at all, no application code in the way.
        // Three rows exist in this schema; one is visible.
        Long seenFromVdms001 = asProperty(org, vdms001, () ->
                ((Number) em.createNativeQuery("SELECT count(*) FROM procedure_template").getSingleResult())
                        .longValue());
        Long seenFromVdms002 = asProperty(org, vdms002, () ->
                ((Number) em.createNativeQuery("SELECT count(*) FROM procedure_template").getSingleResult())
                        .longValue());

        assertThat(seenFromVdms001).isEqualTo(1);
        assertThat(seenFromVdms002).isEqualTo(2);
    }

    @Test
    void aPropertysWorkIsStillInvisibleToAnotherOrganization() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID sharedPropertyId = UUID.randomUUID();

        asProperty(orgA, sharedPropertyId, () -> service.create(named("Org A procedure")));

        // Deliberately the same property id in both organizations. Even if a
        // property id leaked or were guessed, the schema boundary is a second
        // wall and it does not depend on the policy being right.
        assertThat(asProperty(orgB, sharedPropertyId, this::visibleNames)).isEmpty();
    }
}
