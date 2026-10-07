package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CloneRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.UpdateTemplateRequest;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.applicationplane.procedure.tenancy.TenantSchemas;
import com.sclera.controlplane.common.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who a procedure is for: the consumers {@code consumerKey} may name, checked
 * when a procedure is created.
 *
 * Each test runs in a brand-new organization, which starts with the seeded
 * consumers and nothing else.
 */
class ProcedureConsumerIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService service;

    @Autowired
    private JdbcTemplate jdbc;

    private static CreateTemplateRequest create(String consumerKey) {
        return new CreateTemplateRequest("Walk " + UUID.randomUUID(), null, consumerKey, DefinitionDocument.empty());
    }

    private void retire(UUID org, String key) {
        jdbc.update("UPDATE \"" + TenantSchemas.schemaFor(org) + "\".procedure_consumer SET active = false WHERE key = ?", key);
    }

    // --- what an organization starts with --------------------------------------------

    @Test
    void aNewOrganizationIsSeededWithInspectionFirstThenTask() {
        UUID org = actAsNewOrg();

        List<String> keys = jdbc.queryForList("SELECT key FROM \"" + TenantSchemas.schemaFor(org)
                + "\".procedure_consumer ORDER BY name", String.class);

        assertThat(keys).containsExactly("INSPECTION", "TASK");
    }

    @Test
    void aProcedureThatNamesNoConsumerStillCreatesAndBelongsToInspection() {
        // Every procedure authored before this existed is this one.
        actAsNewOrg();

        assertThat(service.create(create(null)).consumerKey()).isEqualTo("INSPECTION");
        assertThat(service.create(create("  ")).consumerKey()).isEqualTo("INSPECTION");
    }

    // --- choosing one ---------------------------------------------------------------------

    @Test
    void aSeededConsumerIsAccepted() {
        actAsNewOrg();

        assertThat(service.create(create("TASK")).consumerKey()).isEqualTo("TASK");
        assertThat(service.create(create("INSPECTION")).consumerKey()).isEqualTo("INSPECTION");
    }

    @Test
    void theKeyIsNormalisedAsItAlwaysWasBeforeItIsChecked() {
        actAsNewOrg();

        assertThat(service.create(create("  task ")).consumerKey()).isEqualTo("TASK");
    }

    @Test
    void anUnknownConsumerIsRefusedAndTheValidOnesAreNamed() {
        actAsNewOrg();

        assertThatThrownBy(() -> service.create(create("workorder")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("'WORKORDER' is not one of this organization's procedure consumers: INSPECTION, TASK");
    }

    @Test
    void aRefusedConsumerCreatesNothing() {
        actAsNewOrg();
        String name = "Refused " + UUID.randomUUID();

        assertThatThrownBy(() -> service.create(new CreateTemplateRequest(name, null, "NOPE", DefinitionDocument.empty())))
                .isInstanceOf(ValidationException.class);

        // The name is still free: nothing was stored before the check.
        assertThat(service.create(new CreateTemplateRequest(name, null, "TASK", DefinitionDocument.empty())).name())
                .isEqualTo(name);
    }

    // --- retiring one --------------------------------------------------------------------------

    @Test
    void aRetiredConsumerCannotBeChosenForANewProcedureAndIsLeftOutOfTheList() {
        UUID org = actAsNewOrg();
        retire(org, "TASK");

        assertThatThrownBy(() -> service.create(create("TASK")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("'TASK' is not one of this organization's procedure consumers: INSPECTION");
    }

    @Test
    void aProcedureKeepsAConsumerThatWasRetiredAfterItWasCreated() {
        UUID org = actAsNewOrg();
        UUID id = service.create(create("TASK")).id();

        retire(org, "TASK");

        assertThat(service.get(id).consumerKey()).isEqualTo("TASK");
        // Editing the procedure's identity does not re-check it either.
        assertThat(service.update(id, new UpdateTemplateRequest("Renamed " + UUID.randomUUID(), null)).consumerKey())
                .isEqualTo("TASK");
    }

    @Test
    void aCloneKeepsTheConsumerItCopiesEvenOnceRetired() {
        UUID org = actAsNewOrg();
        UUID id = service.create(create("TASK")).id();
        retire(org, "TASK");

        UUID copy = service.cloneTemplate(id, new CloneRequest("Copy " + UUID.randomUUID(), null)).id();

        assertThat(service.get(copy).consumerKey()).isEqualTo("TASK");
    }

    // --- isolation and the table itself ------------------------------------------------------------

    @Test
    void retiringAConsumerInOneOrganizationLeavesAnotherUntouched() {
        UUID first = actAsNewOrg();
        retire(first, "TASK");
        actAsNewOrg();

        assertThat(service.create(create("TASK")).consumerKey()).isEqualTo("TASK");
    }

    @Test
    void aKeyIsUppercaseOrTheDatabaseRefusesIt() {
        UUID org = actAsNewOrg();

        assertThatThrownBy(() -> jdbc.update("INSERT INTO \"" + TenantSchemas.schemaFor(org)
                + "\".procedure_consumer (key, name) VALUES ('task', 'x')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_procedure_consumer_key");
    }
}