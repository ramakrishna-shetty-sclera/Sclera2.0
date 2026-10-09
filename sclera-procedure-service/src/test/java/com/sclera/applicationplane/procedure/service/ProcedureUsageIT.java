package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.domain.ProcedureUsage;
import com.sclera.applicationplane.procedure.repository.ProcedureUsageRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.applicationplane.procedure.tenancy.TenantSchemas;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The table of what consumers last reported — its shape and its one rule.
 *
 * Nothing here goes through an endpoint, because none exists yet: this proves the
 * table, the entity and the unique rule that the report endpoint will be built
 * on. Each test runs in a brand-new organization, so its schema starts empty and
 * every row found was written by that test.
 */
class ProcedureUsageIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureUsageRepository usage;

    @Autowired
    private JdbcTemplate jdbc;

    private static ProcedureUsage report(UUID template, UUID version, String consumer, String ref, String target) {
        return new ProcedureUsage(template, version, consumer, ref, target);
    }

    @Test
    void aReportIsStoredWithEverythingTheConsumerSaid() {
        actAsNewOrg();
        UUID template = UUID.randomUUID();
        UUID version = UUID.randomUUID();

        usage.saveAndFlush(report(template, version, "INSPECTION", "config-17", "EXTINGUISHER"));

        ProcedureUsage found = usage.findAll().get(0);
        assertThat(found.getId()).isNotNull();
        assertThat(found.getTemplateId()).isEqualTo(template);
        assertThat(found.getVersionId()).isEqualTo(version);
        assertThat(found.getConsumerKey()).isEqualTo("INSPECTION");
        assertThat(found.getConsumerRefId()).isEqualTo("config-17");
        assertThat(found.getTargetTypeKey()).isEqualTo("EXTINGUISHER");
        assertThat(found.getRecordedAt()).isNotNull();
    }

    @Test
    void aTargetTypeIsOptional() {
        // A consumer that applies a procedure without binding it to a target type
        // simply says nothing about one.
        actAsNewOrg();

        usage.saveAndFlush(report(UUID.randomUUID(), UUID.randomUUID(), "INSPECTION", "config-1", null));

        assertThat(usage.findAll()).extracting(ProcedureUsage::getTargetTypeKey).containsExactly((String) null);
    }

    @Test
    void theSameConsumerReferenceAndTemplateCannotBeStoredTwice() {
        // The unique triple is the whole mechanism: it is what lets a re-report be
        // an update. The service upserts against it; the database refuses the rest.
        actAsNewOrg();
        UUID template = UUID.randomUUID();
        usage.saveAndFlush(report(template, UUID.randomUUID(), "INSPECTION", "config-1", null));

        assertThatThrownBy(() ->
                usage.saveAndFlush(report(template, UUID.randomUUID(), "INSPECTION", "config-1", null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_procedure_usage");
    }

    @Test
    void anyPartOfTheTripleDifferingIsADifferentFact() {
        // The rule is about the three together. One consumer reference using two
        // templates, two references using one template, and two consumers sharing a
        // reference are all separate rows.
        actAsNewOrg();
        UUID templateA = UUID.randomUUID();
        UUID templateB = UUID.randomUUID();
        UUID version = UUID.randomUUID();

        usage.saveAndFlush(report(templateA, version, "INSPECTION", "config-1", null));
        usage.saveAndFlush(report(templateB, version, "INSPECTION", "config-1", null));  // other template
        usage.saveAndFlush(report(templateA, version, "INSPECTION", "config-2", null));  // other reference
        usage.saveAndFlush(report(templateA, version, "TASK", "config-1", null));        // other consumer

        assertThat(usage.count()).isEqualTo(4);
    }

    @Test
    void reportingAgainMovesTheRowToTheNewVersionInsteadOfAddingOne() {
        // "A config was on v3, now it is on v4" must read as one row changing.
        actAsNewOrg();
        UUID template = UUID.randomUUID();
        UUID v3 = UUID.randomUUID();
        UUID v4 = UUID.randomUUID();
        ProcedureUsage first = usage.saveAndFlush(report(template, v3, "INSPECTION", "config-1", "EXTINGUISHER"));
        UUID id = first.getId();
        var firstRecordedAt = first.getRecordedAt();

        ProcedureUsage existing = usage
                .findByConsumerKeyAndConsumerRefIdAndTemplateId("INSPECTION", "config-1", template).orElseThrow();
        existing.reportAgain(v4, null);
        usage.saveAndFlush(existing);

        List<ProcedureUsage> all = usage.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getId()).isEqualTo(id);
        assertThat(all.get(0).getVersionId()).isEqualTo(v4);
        assertThat(all.get(0).getTargetTypeKey()).as("a re-report replaces what was said before").isNull();
        assertThat(all.get(0).getRecordedAt()).isAfterOrEqualTo(firstRecordedAt);
    }

    @Test
    void theLookupFindsOnlyTheExactTriple() {
        actAsNewOrg();
        UUID template = UUID.randomUUID();
        usage.saveAndFlush(report(template, UUID.randomUUID(), "INSPECTION", "config-1", null));

        assertThat(usage.findByConsumerKeyAndConsumerRefIdAndTemplateId("INSPECTION", "config-1", template))
                .isPresent();
        assertThat(usage.findByConsumerKeyAndConsumerRefIdAndTemplateId("INSPECTION", "config-2", template))
                .isEmpty();
        assertThat(usage.findByConsumerKeyAndConsumerRefIdAndTemplateId("TASK", "config-1", template)).isEmpty();
        assertThat(usage.findByConsumerKeyAndConsumerRefIdAndTemplateId("INSPECTION", "config-1", UUID.randomUUID()))
                .isEmpty();
    }

    @Test
    void theIdsAreTheConsumersToReportWithNoForeignKeyHoldingThemBack() {
        // This table records what another service said. It does not check that a
        // template exists in the database, and a retired consumer key must not stop
        // its old reports being kept.
        actAsNewOrg();

        usage.saveAndFlush(report(UUID.randomUUID(), UUID.randomUUID(), "A_RETIRED_CONSUMER", "x", null));

        assertThat(usage.count()).isEqualTo(1);
    }

    @Test
    void theTableCarriesNoPolicyOfItsOwn() {
        // Usage is reported by other services and read across every property, so it
        // must not have the row-level security procedure_template has. Pinned so that
        // someone copying that table's migration does not add one here by habit.
        UUID org = actAsNewOrg();

        List<String> policies = jdbc.queryForList(
                "SELECT policyname FROM pg_policies WHERE schemaname = ? AND tablename = 'procedure_usage'",
                String.class, TenantSchemas.schemaFor(org));

        assertThat(policies).isEmpty();
    }

    @Test
    void aRowReportedInsidePropertiesIsStillSeenFromOrganizationLevel() {
        UUID org = actAsNewOrg();
        UUID property = UUID.randomUUID();
        UUID template = UUID.randomUUID();
        asProperty(org, property, () ->
                usage.saveAndFlush(report(template, UUID.randomUUID(), "INSPECTION", "config-9", null)));

        long seenAtOrganizationLevel = asOrg(org, () -> usage.count());

        assertThat(seenAtOrganizationLevel).isEqualTo(1);
    }
}
