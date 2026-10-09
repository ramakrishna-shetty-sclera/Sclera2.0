package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.controller.InternalUsageController;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.ProcedureUsage;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.NewDraftRequest;
import com.sclera.applicationplane.procedure.dto.UsageDtos.ReportUsageRequest;
import com.sclera.applicationplane.procedure.dto.UsageDtos.ReportUsageResponse;
import com.sclera.applicationplane.procedure.repository.ProcedureUsageRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.applicationplane.procedure.tenancy.PropertyContext;
import com.sclera.applicationplane.procedure.tenancy.TenantSchemas;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.exception.ValidationException;
import com.sclera.controlplane.common.security.OrgContext;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A consumer's report, the way a Dapr call delivers it: no JWT, so no
 * organization context and no property, only the organization in the path.
 *
 * Calls the controller bean rather than the service, because pinning the schema
 * ({@code ensureTenant} then {@code TenantContext.runAs}) is the controller's half
 * of the work and the part most likely to be wrong. HMAC signing is
 * sclera-common's filter and is not exercised here.
 */
class InternalUsageIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService procedures;

    @Autowired
    private InternalUsageController internal;

    @Autowired
    private ProcedureUsageRepository usage;

    @Autowired
    private JdbcTemplate jdbc;

    private record Published(UUID templateId, UUID versionId) {
    }

    private static CreateTemplateRequest create() {
        Item question = Item.builder().text("Present?").type(QuestionType.YES_NO).required(true)
                .options(List.of(new Option(null, "Yes", "PASS", null, false), new Option(null, "No", "FAIL", null, false)))
                .build();
        return new CreateTemplateRequest("Walk " + UUID.randomUUID(), null, null,
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(question), List.of(), List.of(),
                        List.of()));
    }

    private Published publish() {
        UUID id = procedures.create(create()).id();
        return new Published(id, procedures.publish(id, null).version().id());
    }

    private static ReportUsageRequest report(Published p, String consumer, String ref, String target) {
        return new ReportUsageRequest(p.templateId(), p.versionId(), consumer, ref, target);
    }

    /** The test reads what the call wrote as the organization, since the call itself ran with no context. */
    private long rows(UUID org) {
        return asOrg(org, () -> usage.count());
    }

    private static <T> T asDapr(Supplier<T> call) {
        OrgContext.clear();
        PropertyContext.clear();
        return call.get();
    }

    @Test
    void aFirstReportStoresARowAndSaysItWasNew() {
        UUID org = actAsNewOrg();
        Published p = publish();

        ReportUsageResponse response = asDapr(() ->
                internal.report(org, report(p, "INSPECTION", "config-17", "EXTINGUISHER")));

        assertThat(response.created()).isTrue();
        assertThat(response.templateId()).isEqualTo(p.templateId());
        assertThat(response.versionId()).isEqualTo(p.versionId());
        assertThat(response.consumerKey()).isEqualTo("INSPECTION");
        assertThat(response.consumerRefId()).isEqualTo("config-17");
        assertThat(response.targetTypeKey()).isEqualTo("EXTINGUISHER");
        assertThat(response.recordedAt()).isNotNull();
        assertThat(rows(org)).isEqualTo(1);
    }

    @Test
    void reportingTheSameFactAgainIsAnUpdateInPlaceNotASecondRow() {
        // The whole point of the unique triple: a configuration that moves from v1
        // to v2 is one row changing.
        UUID org = actAsNewOrg();
        Published v1 = publish();
        procedures.createDraft(v1.templateId(), new NewDraftRequest(1));
        var draft = procedures.getDraft(v1.templateId());
        var changed = new DefinitionDocument(draft.definition().schema(), draft.definition().items(),
                draft.definition().thresholds(), List.of(), List.of());
        procedures.saveDraft(v1.templateId(), new com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos
                .SaveDraftRequest(withHelp(changed), draft.rowVersion(), "second"));
        UUID v2 = procedures.publish(v1.templateId(), null).version().id();

        ReportUsageResponse first = asDapr(() -> internal.report(org, report(v1, "INSPECTION", "config-1", null)));
        ReportUsageResponse second = asDapr(() -> internal.report(org,
                new ReportUsageRequest(v1.templateId(), v2, "INSPECTION", "config-1", null)));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.versionId()).isEqualTo(v2);
        assertThat(rows(org)).isEqualTo(1);
    }

    private static DefinitionDocument withHelp(DefinitionDocument source) {
        Item q = source.items().get(0);
        Item reworded = q.toBuilder().text("Present and tagged?").build();
        return new DefinitionDocument(source.schema(), List.of(reworded), source.thresholds(), List.of(),
                List.of());
    }

    @Test
    void aDifferentConsumerReferenceIsAnotherRow() {
        UUID org = actAsNewOrg();
        Published p = publish();

        asDapr(() -> internal.report(org, report(p, "INSPECTION", "config-1", null)));
        ReportUsageResponse other = asDapr(() -> internal.report(org, report(p, "INSPECTION", "config-2", null)));

        assertThat(other.created()).isTrue();
        assertThat(rows(org)).isEqualTo(2);
    }

    @Test
    void aTargetTypeIsOptionalAndBlankReadsAsNone() {
        UUID org = actAsNewOrg();
        Published p = publish();

        ReportUsageResponse response = asDapr(() -> internal.report(org, report(p, "INSPECTION", "c", "   ")));

        assertThat(response.targetTypeKey()).isNull();
    }

    @Test
    void aReReportReplacesTheTargetTypeItSaidBefore() {
        UUID org = actAsNewOrg();
        Published p = publish();
        asDapr(() -> internal.report(org, report(p, "INSPECTION", "c", "EXTINGUISHER")));

        ReportUsageResponse again = asDapr(() -> internal.report(org, report(p, "INSPECTION", "c", null)));

        assertThat(again.targetTypeKey()).isNull();
        assertThat(asOrg(org, () -> usage.findAll())).extracting(ProcedureUsage::getTargetTypeKey)
                .containsExactly((String) null);
    }

    @Test
    void theConsumerKeyIsNormalisedLikeTheOneOnATemplate() {
        UUID org = actAsNewOrg();
        Published p = publish();

        ReportUsageResponse response = asDapr(() -> internal.report(org, report(p, "  inspection ", "c", null)));

        assertThat(response.consumerKey()).isEqualTo("INSPECTION");
    }

    @Test
    void aConsumerThatIsNotInTheListIsRefusedNamingIt() {
        UUID org = actAsNewOrg();
        Published p = publish();

        assertThatThrownBy(() -> asDapr(() -> internal.report(org, report(p, "INSPECTON", "c", null))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("INSPECTON");
        assertThat(rows(org)).isZero();
    }

    @Test
    void aRetiredConsumerStillReportsBecauseItsConfigurationsAreStillInUse() {
        UUID org = actAsNewOrg();
        Published p = publish();
        jdbc.update("UPDATE \"" + TenantSchemas.schemaFor(org) + "\".procedure_consumer SET active = false "
                + "WHERE key = 'TASK'");

        ReportUsageResponse response = asDapr(() -> internal.report(org, report(p, "TASK", "t-1", null)));

        assertThat(response.created()).isTrue();
    }

    @Test
    void aVersionThatDoesNotExistIsNotFound() {
        UUID org = actAsNewOrg();
        Published p = publish();

        assertThatThrownBy(() -> asDapr(() -> internal.report(org,
                new ReportUsageRequest(p.templateId(), UUID.randomUUID(), "INSPECTION", "c", null))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aVersionOfADifferentProcedureIsRefused() {
        // The consumer named a template and a version that do not go together; storing
        // it would make the impact preview of one procedure count another's version.
        UUID org = actAsNewOrg();
        Published a = publish();
        Published b = publish();

        assertThatThrownBy(() -> asDapr(() -> internal.report(org,
                new ReportUsageRequest(a.templateId(), b.versionId(), "INSPECTION", "c", null))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not belong");
        assertThat(rows(org)).isZero();
    }

    @Test
    void aDraftVersionIsRefusedBecauseAConsumerIsOnlyEverOnAPublishedOne() {
        UUID org = actAsNewOrg();
        Published p = publish();
        procedures.createDraft(p.templateId(), new NewDraftRequest(1));
        UUID draftVersionId = procedures.getDraft(p.templateId()).id();

        assertThatThrownBy(() -> asDapr(() -> internal.report(org,
                new ReportUsageRequest(p.templateId(), draftVersionId, "INSPECTION", "c", null))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("draft");
        assertThat(rows(org)).isZero();
    }

    @Test
    void aPropertysProcedureCanBeReportedAgainstWithNoPropertyHeader() {
        // A Dapr call carries no X-Sclera-Property, so it lands at organization level,
        // where row-level security hides this procedure's template. The version has no
        // such policy and the report is checked against it, never the template, so the
        // configuration that uses a property's procedure is still recorded.
        UUID org = actAsNewOrg();
        UUID property = UUID.randomUUID();
        Published p = asProperty(org, property, this::publish);

        asOrg(org, () -> assertThatThrownBy(() -> procedures.get(p.templateId()))
                .isInstanceOf(ResourceNotFoundException.class));
        ReportUsageResponse response = asDapr(() -> internal.report(org, report(p, "INSPECTION", "c", null)));

        assertThat(response.created()).isTrue();
    }

    @Test
    void anotherOrganizationCannotReportAgainstThisOnesVersion() {
        UUID first = actAsNewOrg();
        Published p = asOrg(first, this::publish);
        UUID second = actAsNewOrg();

        assertThatThrownBy(() -> asDapr(() -> internal.report(second, report(p, "INSPECTION", "c", null))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theRequestItselfRefusesBlanksAndOverlongValues() {
        // The bean is called directly here, which skips the HTTP layer's validation,
        // so the annotations are checked on their own.
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        UUID id = UUID.randomUUID();

        assertThat(validator.validate(new ReportUsageRequest(id, id, "INSPECTION", "c", null))).isEmpty();
        assertThat(validator.validate(new ReportUsageRequest(null, id, "INSPECTION", "c", null))).hasSize(1);
        assertThat(validator.validate(new ReportUsageRequest(id, null, "INSPECTION", "c", null))).hasSize(1);
        assertThat(validator.validate(new ReportUsageRequest(id, id, " ", "c", null))).hasSize(1);
        assertThat(validator.validate(new ReportUsageRequest(id, id, "INSPECTION", "", null))).hasSize(1);
        assertThat(validator.validate(new ReportUsageRequest(id, id, "INSPECTION", "x".repeat(101), null))).hasSize(1);
        assertThat(validator.validate(new ReportUsageRequest(id, id, "INSPECTION", "c", "x".repeat(51)))).hasSize(1);
    }
}
