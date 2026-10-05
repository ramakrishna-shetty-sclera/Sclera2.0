package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.controlplane.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefinitionValidatorTest {

    private static final Set<String> PASS_FAIL = Set.of("PASS", "FAIL");

    private static Item item(String key, String text, QuestionType type) {
        return new Item(key, text, null, type, false, List.of(), null, null, null,
                false, null, null, null, null, List.of());
    }

    private static Item yesNo(String key, String text) {
        return item(key, text, QuestionType.YES_NO).withOptions(
                List.of(new Option("o90", "Yes", "PASS"), new Option("o91", "No", "FAIL")));
    }

    private static DefinitionDocument doc(Item... items) {
        return new DefinitionDocument(2, List.of(items));
    }

    // --- structure: refused on save ------------------------------------------

    @Test
    void aWellFormedDocumentPasses() {
        Item follow = item("q3", "Describe the obstruction", QuestionType.TEXT);
        assertThatCode(() -> DefinitionValidator.validateStructure(doc(
                item("s1", "Fire exits", QuestionType.SECTION),
                yesNo("q2", "Exit clear?").withFollow(List.of(
                        new Item(follow.key(), follow.text(), null, follow.type(), false, List.of(),
                                null, null, null, false, null, null, null, "o91", List.of()))))))
                .doesNotThrowAnyException();
    }

    @Test
    void aFollowUpMustNameAnAnswerOfItsOwnParent() {
        Item stray = new Item("q3", "Why?", null, QuestionType.TEXT, false, List.of(),
                null, null, null, false, null, null, null, "o77", List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                doc(yesNo("q2", "Exit clear?").withFollow(List.of(stray)))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("depends on an answer that is not one of");
    }

    @Test
    void aFollowUpMustSayWhichAnswerShowsIt() {
        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                doc(yesNo("q2", "Exit clear?").withFollow(List.of(item("q3", "Why?", QuestionType.TEXT))))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must say which answer shows it");
    }

    @Test
    void aTopLevelQuestionCannotBeConditional() {
        Item conditional = new Item("q2", "Why?", null, QuestionType.TEXT, false, List.of(),
                null, null, null, false, null, null, null, "o90", List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(conditional)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("top-level and cannot depend on an answer");
    }

    @Test
    void aFollowUpCannotHangOffAQuestionWithNoAnswers() {
        Item follow = new Item("q3", "Why?", null, QuestionType.TEXT, false, List.of(),
                null, null, null, false, null, null, null, "o90", List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                doc(item("q2", "Remarks", QuestionType.TEXT).withFollow(List.of(follow)))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("has no answers to depend on");
    }

    @Test
    void onlyChoiceQuestionsMayHaveAnswers() {
        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                doc(item("q2", "Remarks", QuestionType.TEXT)
                        .withOptions(List.of(new Option("o3", "Yes", "PASS"))))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot have answers");
    }

    @Test
    void onlyNumberQuestionsMayHaveAUnitOrRange() {
        Item texty = new Item("q2", "Remarks", null, QuestionType.TEXT, false, List.of(),
                "psi", null, null, false, null, null, null, null, List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(texty)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot have a unit or a range");
    }

    @Test
    void aRangeCannotRunBackwards() {
        Item backwards = new Item("q2", "Reading", null, QuestionType.INTEGER, false, List.of(),
                "psi", 300, 10, false, null, null, null, null, List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(backwards)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("minimum above its maximum");
    }

    @Test
    void onlyChoiceQuestionsMayRaiseAWorkOrder() {
        Item texty = new Item("q2", "Remarks", null, QuestionType.TEXT, false, List.of(),
                null, null, null, true, "ap-std", null, null, null, List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(texty)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("only choice questions produce a result");
    }

    @Test
    void aSectionIsAHeadingAndNothingElse() {
        Item section = item("s1", "Fire exits", QuestionType.SECTION)
                .withFollow(List.of(item("q2", "Nested under a heading", QuestionType.TEXT)));

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(section)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("is a section and cannot have follow-ups");
    }

    @Test
    void oneSaveReportsEveryStructuralProblem() {
        Item backwards = new Item("q2", "Reading", null, QuestionType.INTEGER, false, List.of(),
                null, 300, 10, false, null, null, null, null, List.of());
        Item withAnswers = item("q3", "Remarks", QuestionType.TEXT)
                .withOptions(List.of(new Option("o4", "Yes", "PASS")));

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(backwards, withAnswers)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("minimum above its maximum")
                .hasMessageContaining("cannot have answers");
    }

    // --- readiness: refused at publish ---------------------------------------

    @Test
    void aDocumentWithNothingInItCannotBePublished() {
        assertThat(DefinitionValidator.publishBlockers(DefinitionDocument.empty(), PASS_FAIL))
                .containsExactly("Add at least one question");
    }

    @Test
    void aSectionAloneIsNotAQuestion() {
        assertThat(DefinitionValidator.publishBlockers(
                doc(item("s1", "Fire exits", QuestionType.SECTION)), PASS_FAIL))
                .containsExactly("Add at least one question");
    }

    @Test
    void aChoiceNeedsTwoAnswersAndOneResult() {
        Item single = item("q2", "Exit clear?", QuestionType.YES_NO)
                .withOptions(List.of(new Option("o3", "Yes", null)));

        assertThat(DefinitionValidator.publishBlockers(doc(single), PASS_FAIL))
                .hasSize(2)
                .anySatisfy(b -> assertThat(b).contains("needs at least two answers"))
                .anySatisfy(b -> assertThat(b).contains("needs at least one answer mapped to a result"));
    }

    @Test
    void anAnswerCannotBeMappedToAResultTypeTheOrganizationDoesNotHave() {
        Item amber = item("q2", "Exit clear?", QuestionType.YES_NO).withOptions(
                List.of(new Option("o3", "Yes", "PASS"), new Option("o4", "Partly", "AMBER")));

        assertThat(DefinitionValidator.publishBlockers(doc(amber), PASS_FAIL))
                .singleElement().asString()
                .contains("'Partly' is mapped to AMBER, which is not an active result type");
    }

    @Test
    void aFollowUpIsCheckedLikeAnyOtherQuestion() {
        Item follow = new Item("q3", "Which one?", null, QuestionType.DROPDOWN, false,
                List.of(new Option("o4", "Only answer", "PASS")),
                null, null, null, false, null, null, null, "o91", List.of());

        assertThat(DefinitionValidator.publishBlockers(
                doc(yesNo("q2", "Exit clear?").withFollow(List.of(follow))), PASS_FAIL))
                .singleElement().asString().contains("'Which one?' needs at least two answers");
    }

    @Test
    void aReadyDocumentHasNoBlockers() {
        assertThat(DefinitionValidator.publishBlockers(
                doc(item("s1", "Fire exits", QuestionType.SECTION), yesNo("q2", "Exit clear?")), PASS_FAIL))
                .isEmpty();
    }
}
