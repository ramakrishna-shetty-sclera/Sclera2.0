package com.sclera.applicationplane.procedure.service;

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
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.dto.ResultTypeRequest;
import com.sclera.applicationplane.procedure.dto.ResultTypeResponse;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Evaluation through the service against real Postgres: the version is the
 * one the database holds, the result types are the organization's, and the
 * template is reached the way every other read reaches it — through its
 * organization and, where it has one, its property.
 *
 * The arithmetic is EvaluatorTest's job; these hold the wiring. Each test runs
 * in a brand-new organization, which starts with only Pass and Fail.
 */
class EvaluationIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService procedures;

    @Autowired
    private ResultTypeService resultTypes;

    @Test
    void aPublishedVersionIsEvaluated() {
        actAsNewOrg();
        UUID id = procedures.create(create(scoredYesNo())).id();
        procedures.publish(id, null);
        String question = firstKey(id, 1);

        EvaluationResponse response = procedures.evaluate(id, 1, answers(question, yes(id, 1)));

        assertThat(response.version().state()).isEqualTo(VersionState.PUBLISHED);
        assertThat(response.version().versionNo()).isEqualTo(1);
        assertThat(response.version().templateId()).isEqualTo(id);
        assertThat(response.overall().result()).isEqualTo("PASS");
        assertThat(response.overall().percentage()).isEqualByComparingTo("100");
        assertThat(response.overall().complete()).isTrue();
    }

    @Test
    void aDraftIsEvaluatedTooForTheAuthorsPreview() {
        actAsNewOrg();
        UUID id = procedures.create(create(scoredYesNo())).id();
        String question = procedures.getDraft(id).definition().items().get(0).key();
        String no = procedures.getDraft(id).definition().items().get(0).options().get(1).key();

        EvaluationResponse response = procedures.evaluate(id, 1, answers(question, no));

        assertThat(response.version().state()).isEqualTo(VersionState.DRAFT);
        assertThat(response.overall().result()).isEqualTo("FAIL");
        assertThat(response.overall().percentage()).isEqualByComparingTo("0");
    }

    @Test
    void nothingAnsweredIsEvaluatedAsNoScoreYet() {
        actAsNewOrg();
        UUID id = procedures.create(create(scoredYesNo())).id();
        procedures.publish(id, null);

        EvaluationResponse response = procedures.evaluate(id, 1, null);

        assertThat(response.overall().percentage()).isNull();
        assertThat(response.overall().result()).isNull();
        assertThat(response.unanswered()).containsExactly(firstKey(id, 1));
    }

    @Test
    void evaluatingChangesNothing() {
        actAsNewOrg();
        UUID id = procedures.create(create(scoredYesNo())).id();
        long before = procedures.getDraft(id).rowVersion();

        procedures.evaluate(id, 1, answers(firstKey(id, 1), yes(id, 1)));

        assertThat(procedures.getDraft(id).rowVersion()).isEqualTo(before);
    }

    @Test
    void answersThatCannotBelongAreRefusedAllAtOnce() {
        actAsNewOrg();
        UUID id = procedures.create(create(scoredYesNo())).id();
        procedures.publish(id, null);
        String question = firstKey(id, 1);

        assertThatThrownBy(() -> procedures.evaluate(id, 1, answers(question, "o999", "q999", "x")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(question + " has no option o999")
                .hasMessageContaining("q999 is not a question in this version");
    }

    @Test
    void aVersionThatDoesNotExistIsNotFound() {
        actAsNewOrg();
        UUID id = procedures.create(create(scoredYesNo())).id();

        assertThatThrownBy(() -> procedures.evaluate(id, 7, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("has no version 7");
    }

    @Test
    void aDeactivatedResultTypeStillRanks() {
        // The only way to see this is the real thing: a type a published
        // version names, deactivated afterwards. Loaded active-only, Amber
        // would be unknown, rank below Pass, and the verdict would read Pass.
        actAsNewOrg();
        // Ranked 2, between Fail and Pass. Left unranked it would append as
        // the least severe of all, below Pass.
        ResultTypeResponse amber = resultTypes.create(new ResultTypeRequest("AMBER", "Amber", "#f39c12", null, 2));
        Item condition = choice("Condition?", new Option(null, "Good", "PASS", null, false),
                new Option(null, "Worn", "AMBER", null, false));
        Item exit = choice("Exit clear?", new Option(null, "Yes", "PASS", null, false),
                new Option(null, "No", "FAIL", null, false));
        UUID id = procedures.create(create(doc(List.of(), condition, exit))).id();
        procedures.publish(id, null);
        resultTypes.deactivate(amber.id());

        List<Item> items = procedures.getVersion(id, 1).definition().items();
        EvaluationResponse response = procedures.evaluate(id, 1, answers(
                items.get(0).key(), items.get(0).options().get(1).key(),     // Worn: AMBER
                items.get(1).key(), items.get(1).options().get(0).key()));   // Yes: PASS

        assertThat(response.overall().result()).isEqualTo("AMBER");
    }

    @Test
    void aPropertysProcedureIsEvaluatedInsideThatPropertyOnly() {
        UUID org = actAsNewOrg();
        UUID property = UUID.randomUUID();
        UUID id = asProperty(org, property, () -> {
            UUID created = procedures.create(create(scoredYesNo())).id();
            procedures.publish(created, null);
            return created;
        });

        EvaluationResponse inside = asProperty(org, property, () -> procedures.evaluate(id, 1, null));
        assertThat(inside.version().templateId()).isEqualTo(id);

        // At organization level the property's procedure is invisible, the
        // same as every other read of it.
        asOrg(org, () -> assertThatThrownBy(() -> procedures.evaluate(id, 1, null))
                .isInstanceOf(ResourceNotFoundException.class));
    }

    @Test
    void anotherOrganizationsProcedureIsNotFound() {
        actAsNewOrg();
        UUID id = procedures.create(create(scoredYesNo())).id();
        procedures.publish(id, null);

        actAsNewOrg();

        assertThatThrownBy(() -> procedures.evaluate(id, 1, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- builders ---------------------------------------------------------------

    /** Yes passes for 10 points, No fails for none; 0 to 59 fails, 60 to 100 passes. */
    private static DefinitionDocument scoredYesNo() {
        Item question = new Item(null, "Exit clear?", null, QuestionType.YES_NO, true, false,
                List.of(new Option(null, "Yes", "PASS", 10, false), new Option(null, "No", "FAIL", 0, false)),
                null, null, null, false, null, false, null, null, null, null, null, List.of(), List.of());
        return doc(List.of(new Threshold(null, 0, 59, "FAIL"), new Threshold(null, 60, 100, "PASS")), question);
    }

    private static Item choice(String text, Option... options) {
        return new Item(null, text, null, QuestionType.RADIO, false, false, List.of(options),
                null, null, null, false, null, false, null, null, null, null, null, List.of(), List.of());
    }

    private static DefinitionDocument doc(List<Threshold> thresholds, Item... items) {
        return new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(items), thresholds, List.of());
    }

    private static CreateTemplateRequest create(DefinitionDocument definition) {
        return new CreateTemplateRequest("Evaluated " + UUID.randomUUID(), null, null, definition);
    }

    /** The first question's key in a version — minted by the service, so read back. */
    private String firstKey(UUID id, int versionNo) {
        return firstItem(id, versionNo).key();
    }

    private String yes(UUID id, int versionNo) {
        return firstItem(id, versionNo).options().get(0).key();
    }

    private Item firstItem(UUID id, int versionNo) {
        VersionResponse version = procedures.getVersion(id, versionNo);
        return version.definition().items().get(0);
    }

    private static EvaluateRequest answers(String... keyValuePairs) {
        Map<String, AnswerInput> answers = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            answers.put(keyValuePairs[i], new AnswerInput(keyValuePairs[i + 1]));
        }
        return new EvaluateRequest(answers);
    }
}
