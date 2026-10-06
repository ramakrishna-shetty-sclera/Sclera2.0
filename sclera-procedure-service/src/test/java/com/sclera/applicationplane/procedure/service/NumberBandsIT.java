package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.RangeRule;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.VersionResultTypeRef;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ResultTypeRequest;
import com.sclera.applicationplane.procedure.dto.ResultTypeResponse;
import com.sclera.applicationplane.procedure.repository.VersionResultTypeRefRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A number question whose bands say what a reading means — through the
 * service, against real Postgres, so the rules are seen where an author meets
 * them: a save that is refused, a publish that is refused, a delete that is.
 *
 * Each test runs in a brand-new organization, which starts with only Pass and
 * Fail.
 */
class NumberBandsIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService procedures;

    @Autowired
    private ResultTypeService resultTypes;

    @Autowired
    private VersionResultTypeRefRepository refs;

    @Test
    void bandsSurviveTheRoundTripThroughADraft() {
        actAsNewOrg();
        amber();
        UUID id = procedures.create(create(pressure(threeBands()))).id();

        Item stored = procedures.getDraft(id).definition().items().get(0);

        assertThat(stored.rules()).containsExactly(threeBands().toArray(RangeRule[]::new));
    }

    @Test
    void publishingRecordsTheResultTypesTheBandsName() {
        actAsNewOrg();
        amber();
        UUID id = procedures.create(create(pressure(threeBands()))).id();

        procedures.publish(id, null);

        assertThat(refs.findAll()).extracting(VersionResultTypeRef::getResultTypeKey)
                .containsExactlyInAnyOrder("AMBER", "FAIL", "PASS");
    }

    @Test
    void aTypeOnlyABandUsesCannotBeDeleted() {
        actAsNewOrg();
        ResultTypeResponse amber = amber();
        procedures.publish(procedures.create(create(pressure(threeBands()))).id(), null);

        assertThatThrownBy(() -> resultTypes.delete(amber.id()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Amber is used by 1 published version, so it cannot be deleted; "
                        + "deactivate it instead");
    }

    @Test
    void aGapBetweenBandsSavesButDoesNotPublish() {
        actAsNewOrg();
        UUID id = procedures.create(create(pressure(List.of(
                new RangeRule(null, 11, "PASS"), new RangeRule(16, null, "FAIL"))))).id();

        assertThatThrownBy(() -> procedures.publish(id, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("This procedure cannot be published yet: "
                        + "'Pressure': no band covers readings from 12 to 15");
        assertThat(refs.count()).isZero();
    }

    @Test
    void aBandRunningBackwardsIsRefusedOnSave() {
        actAsNewOrg();

        assertThatThrownBy(() -> procedures.create(create(pressure(List.of(new RangeRule(20, 10, "PASS"))))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("has a band that starts above where it ends (20 to 10)");
    }

    // --- builders -----------------------------------------------------------

    private ResultTypeResponse amber() {
        return resultTypes.create(new ResultTypeRequest("AMBER", "Amber", "#f39c12", null, null));
    }

    /** Up to 11 passes, 12 to 15 is amber, from 16 fails. */
    private static List<RangeRule> threeBands() {
        return List.of(new RangeRule(null, 11, "PASS"),
                new RangeRule(12, 15, "AMBER"),
                new RangeRule(16, null, "FAIL"));
    }

    private static Item pressure(List<RangeRule> bands) {
        return new Item(null, "Pressure", null, QuestionType.INTEGER, true, false, List.of(),
                "psi", null, null, false, null, null, null, null, null, null, List.of(), bands);
    }

    private static CreateTemplateRequest create(Item item) {
        return new CreateTemplateRequest("Boiler pressure", null, null,
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(item), List.of()));
    }
}
