package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ResultTypeRequest;
import com.sclera.applicationplane.procedure.dto.ResultTypeResponse;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A result type a published version names can be deactivated but not deleted.
 *
 * The published versions are the record of what past inspections decided;
 * deleting the type they point at would leave that record unreadable. Each
 * test runs in a brand-new organization, which starts with only Pass and Fail.
 */
class ResultTypeUsageIT extends PostgresIntegrationTest {

    @Autowired
    private ResultTypeService resultTypes;

    @Autowired
    private ProcedureTemplateService procedures;

    @Test
    void aTypeAPublishedVersionUsesCannotBeDeleted() {
        actAsNewOrg();
        ResultTypeResponse amber = amber();
        publishUsing("Gauge check", "AMBER");

        assertThatThrownBy(() -> resultTypes.delete(amber.id()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Amber is used by 1 published version, so it cannot be deleted; "
                        + "deactivate it instead");
        assertThat(resultTypes.get(amber.id()).key()).isEqualTo("AMBER");
    }

    @Test
    void theRefusalCountsEveryPublishedVersionUsingIt() {
        actAsNewOrg();
        ResultTypeResponse amber = amber();
        publishUsing("Gauge check", "AMBER");
        publishUsing("Valve check", "AMBER");

        assertThatThrownBy(() -> resultTypes.delete(amber.id()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("used by 2 published versions");
    }

    @Test
    void aTypeNothingUsesCanStillBeDeleted() {
        actAsNewOrg();
        ResultTypeResponse amber = amber();
        publishUsing("Gauge check", "PASS");             // a published version, but not using AMBER

        resultTypes.delete(amber.id());

        assertThat(resultTypes.list(null)).extracting(ResultTypeResponse::key)
                .containsExactly("FAIL", "PASS");
    }

    @Test
    void aTypeOnlyADraftUsesCanStillBeDeleted() {
        actAsNewOrg();
        ResultTypeResponse amber = amber();
        procedures.create(procedureUsing("Draft only", "AMBER"));      // never published

        resultTypes.delete(amber.id());

        assertThat(resultTypes.list(null)).extracting(ResultTypeResponse::key)
                .doesNotContain("AMBER");
    }

    @Test
    void deactivatingATypeInUseStillWorksAndItsVersionsStillOpen() {
        actAsNewOrg();
        ResultTypeResponse amber = amber();
        UUID procedure = publishUsing("Gauge check", "AMBER");

        // The way out the refusal points to: no new version can use it, every
        // version that already does keeps rendering.
        assertThat(resultTypes.deactivate(amber.id()).active()).isFalse();
        assertThat(procedures.getVersion(procedure, 1).definition().resultTypeKeys())
                .contains("AMBER");
    }

    @Test
    void aVersionBelongingToAPropertyBlocksTheDeleteAtOrganizationLevel() {
        UUID org = actAsNewOrg();
        UUID property = UUID.randomUUID();
        UUID amberId = amber().id();

        asProperty(org, property, () -> publishUsing("Boiler check", "AMBER"));

        // At organization level the property's procedure is invisible, but its
        // use of AMBER still counts: the index has no row-level security.
        asOrg(org, () -> assertThatThrownBy(() -> resultTypes.delete(amberId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("used by 1 published version"));
    }

    // --- builders -----------------------------------------------------------

    private ResultTypeResponse amber() {
        return resultTypes.create(new ResultTypeRequest("AMBER", "Amber", "#f39c12", null, null));
    }

    /** Creates and publishes a procedure whose one question maps an answer to {@code key}. */
    private UUID publishUsing(String name, String key) {
        UUID id = procedures.create(procedureUsing(name, key)).id();
        procedures.publish(id, null);
        return id;
    }

    private static CreateTemplateRequest procedureUsing(String name, String key) {
        Item question = new Item(null, "Is it in range?", null, QuestionType.DROPDOWN, true, false,
                List.of(new Option(null, "Yes", "PASS", null, false), new Option(null, "Borderline", key, null, false)),
                null, null, null, false, null, false, null, null, null, null, null, List.of(), List.of());
        return new CreateTemplateRequest(name, null, null,
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(question), List.of()));
    }
}
