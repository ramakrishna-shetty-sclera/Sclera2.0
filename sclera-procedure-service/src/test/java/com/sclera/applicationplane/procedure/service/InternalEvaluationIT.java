package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.controller.InternalEvaluationController;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Threshold;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.dto.EvaluationDtos.AnswerInput;
import com.sclera.applicationplane.procedure.dto.EvaluationDtos.EvaluateRequest;
import com.sclera.applicationplane.procedure.dto.EvaluationDtos.EvaluationResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.PublishResponse;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.applicationplane.procedure.tenancy.PropertyContext;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The inspection service's evaluation, the way a Dapr call arrives: no JWT, so
 * no organization context and no property — only the version id and the
 * organization id in the path.
 *
 * Calls the controller bean rather than the service, because pinning the
 * schema ({@code ensureTenant} then {@code TenantContext.runAs}) is the
 * controller's half of the work and the part most likely to be wrong. HMAC
 * signing is sclera-common's filter and is not exercised here.
 */
class InternalEvaluationIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService procedures;

    @Autowired
    private InternalEvaluationController internal;

    @Test
    void aPublishedVersionIsEvaluatedByItsIdAndOrganization() {
        UUID org = actAsNewOrg();
        Published published = publish();

        EvaluationResponse response = asDapr(() -> internal.evaluate(published.versionId(), org,
                answers(published.question(), published.yes())));

        assertThat(response.version().templateId()).isEqualTo(published.templateId());
        assertThat(response.version().state()).isEqualTo(VersionState.PUBLISHED);
        assertThat(response.overall().result()).isEqualTo("PASS");
        assertThat(response.overall().percentage()).isEqualByComparingTo("100");
    }

    @Test
    void aPropertysProcedureIsReachedWithNoPropertyHeader() {
        // The test that proves the reasoning rather than the luck. A Dapr call
        // carries no X-Sclera-Property, so it lands at organization level,
        // where row-level security hides this procedure's template row. The
        // version has no row-level security, and the lookup never touches the
        // template — so the checklist on a property's procedure is evaluated.
        UUID org = actAsNewOrg();
        UUID property = UUID.randomUUID();
        Published published = asProperty(org, property, this::publish);

        // The organization level genuinely cannot see it…
        asOrg(org, () -> assertThatThrownBy(() -> procedures.get(published.templateId()))
                .isInstanceOf(ResourceNotFoundException.class));

        // …and the internal call reaches it anyway.
        EvaluationResponse response = asDapr(() -> internal.evaluate(published.versionId(), org,
                answers(published.question(), published.yes())));
        assertThat(response.overall().result()).isEqualTo("PASS");
    }

    @Test
    void aDraftIsRefused() {
        UUID org = actAsNewOrg();
        UUID id = procedures.create(create()).id();
        UUID draftId = procedures.getDraft(id).id();

        assertThatThrownBy(() -> asDapr(() -> internal.evaluate(draftId, org, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("is a draft; only a published version can be evaluated for an inspection");
    }

    @Test
    void anUnknownVersionIsNotFound() {
        UUID org = actAsNewOrg();
        UUID nowhere = UUID.randomUUID();

        assertThatThrownBy(() -> asDapr(() -> internal.evaluate(nowhere, org, null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Procedure version not found");
    }

    @Test
    void anotherOrganizationsVersionIsNotFoundInThisOne() {
        // The schema is the organization boundary: naming the wrong
        // organization in the path looks in the wrong schema and finds nothing.
        actAsNewOrg();
        Published published = publish();
        UUID otherOrg = actAsNewOrg();

        assertThatThrownBy(() -> asDapr(() -> internal.evaluate(published.versionId(), otherOrg, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anOrganizationNeverSeenBeforeIsProvisionedRatherThanFailing() {
        // The first call for an organization may arrive here before any user
        // of it has signed in; ensureTenant creates its schema on the way.
        UUID brandNew = UUID.randomUUID();

        assertThatThrownBy(() -> asDapr(() -> internal.evaluate(UUID.randomUUID(), brandNew, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- builders ---------------------------------------------------------------

    private record Published(UUID templateId, UUID versionId, String question, String yes) {
    }

    /** Creates and publishes a scored Yes/No procedure in whatever scope is current. */
    private Published publish() {
        UUID id = procedures.create(create()).id();
        PublishResponse response = procedures.publish(id, null);
        Item question = response.version().definition().items().get(0);
        return new Published(id, response.version().id(), question.key(), question.options().get(0).key());
    }

    private static CreateTemplateRequest create() {
        Item question = new Item(null, "Exit clear?", null, QuestionType.YES_NO, true, false,
                List.of(new Option(null, "Yes", "PASS", 10, false), new Option(null, "No", "FAIL", 0, false)),
                null, null, null, false, null, null, null, null, null, null, List.of(), List.of());
        DefinitionDocument document = new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(question),
                List.of(new Threshold(null, 0, 59, "FAIL"), new Threshold(null, 60, 100, "PASS")));
        return new CreateTemplateRequest("Inspected " + UUID.randomUUID(), null, null, document);
    }

    private static EvaluateRequest answers(String question, String option) {
        return new EvaluateRequest(Map.of(question, new AnswerInput(option)));
    }

    /** As a Dapr call arrives: no organization context, no property. */
    private static <T> T asDapr(java.util.function.Supplier<T> call) {
        OrgContext.clear();
        PropertyContext.clear();
        return call.get();
    }
}
