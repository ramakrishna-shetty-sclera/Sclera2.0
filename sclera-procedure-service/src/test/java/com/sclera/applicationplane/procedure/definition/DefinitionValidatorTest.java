package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.RangeRule;
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
                false, null, null, null, null, List.of(), List.of());
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
                                null, null, null, false, null, null, null, "o91", List.of(), List.of()))))))
                .doesNotThrowAnyException();
    }

    @Test
    void aFollowUpMustNameAnAnswerOfItsOwnParent() {
        Item stray = new Item("q3", "Why?", null, QuestionType.TEXT, false, List.of(),
                null, null, null, false, null, null, null, "o77", List.of(), List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                doc(yesNo("q2", "Exit clear?").withFollow(List.of(stray)))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("depends on an answer that does not belong to");
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
                null, null, null, false, null, null, null, "o90", List.of(), List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(conditional)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("top-level and cannot depend on an answer");
    }

    @Test
    void aFollowUpCannotHangOffAQuestionWithNoAnswers() {
        Item follow = new Item("q3", "Why?", null, QuestionType.TEXT, false, List.of(),
                null, null, null, false, null, null, null, "o90", List.of(), List.of());

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
                "psi", null, null, false, null, null, null, null, List.of(), List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(texty)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot have a unit or a range");
    }

    @Test
    void aRangeCannotRunBackwards() {
        Item backwards = new Item("q2", "Reading", null, QuestionType.INTEGER, false, List.of(),
                "psi", 300, 10, false, null, null, null, null, List.of(), List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(backwards)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("minimum above its maximum");
    }

    @Test
    void onlyChoiceQuestionsMayRaiseAWorkOrder() {
        Item texty = new Item("q2", "Remarks", null, QuestionType.TEXT, false, List.of(),
                null, null, null, true, "ap-std", null, null, null, List.of(), List.of());

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
                null, 300, 10, false, null, null, null, null, List.of(), List.of());
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
                null, null, null, false, null, null, null, "o91", List.of(), List.of());

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

    // --- bands on a number question ------------------------------------------

    private static final Set<String> WITH_AMBER = Set.of("PASS", "FAIL", "AMBER");

    private static RangeRule band(Integer min, Integer max, String result) {
        return new RangeRule(min, max, result);
    }

    /** A "Pressure" reading, bounded by {@code min}/{@code max} where set. */
    private static Item pressure(Integer min, Integer max, RangeRule... bands) {
        return new Item("q2", "Pressure", null, QuestionType.INTEGER, true, List.of(),
                "psi", min, max, false, null, null, null, null, List.of(), List.of(bands));
    }

    private static List<String> blockers(Item item) {
        return DefinitionValidator.publishBlockers(doc(item), WITH_AMBER);
    }

    @Test
    void onlyNumberQuestionsMayHaveBands() {
        Item texty = new Item("q2", "Remarks", null, QuestionType.TEXT, false, List.of(),
                null, null, null, false, null, null, null, null, List.of(), List.of(band(null, 5, "PASS")));

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(texty)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("'Remarks' is not a number question, so it cannot have bands");
    }

    @Test
    void aSectionCannotHaveBands() {
        Item section = new Item("s1", "Fire exits", null, QuestionType.SECTION, false, List.of(),
                null, null, null, false, null, null, null, null, List.of(), List.of(band(null, 5, "PASS")));

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(section)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("'Fire exits' is a section and cannot have bands");
    }

    @Test
    void aBandCannotRunBackwards() {
        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                doc(pressure(null, null, band(null, 9, "PASS"), band(20, 10, "FAIL")))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("'Pressure' has a band that starts above where it ends (20 to 10)");
    }

    @Test
    void aBandWithAGapCanStillBeSaved() {
        // Coverage is readiness, not structure: a half-finished set of bands is
        // a draft, and an author must be able to save one.
        assertThatCode(() -> DefinitionValidator.validateStructure(
                doc(pressure(null, null, band(null, 11, "PASS")))))
                .doesNotThrowAnyException();
    }

    @Test
    void aNumberQuestionWithoutBandsIsReady() {
        // Recorded and deciding nothing — a meter reading is allowed to do that.
        assertThat(blockers(pressure(0, 300))).isEmpty();
    }

    @Test
    void threeOpenEndedBandsCoverEveryReading() {
        assertThat(blockers(pressure(null, null,
                band(null, 11, "PASS"), band(12, 15, "AMBER"), band(16, null, "FAIL"))))
                .isEmpty();
    }

    @Test
    void theOrderBandsAreWrittenInDoesNotMatter() {
        assertThat(blockers(pressure(null, null,
                band(16, null, "FAIL"), band(null, 11, "PASS"), band(12, 15, "AMBER"))))
                .isEmpty();
    }

    @Test
    void withinTheQuestionsRangeBandsNeedNotBeOpen() {
        // 0 to 300 is all that can be typed, so bands that cover exactly that
        // cover everything — and open ones covering more are fine too.
        assertThat(blockers(pressure(0, 300, band(0, 100, "PASS"), band(101, 300, "FAIL")))).isEmpty();
        assertThat(blockers(pressure(0, 300, band(null, 100, "PASS"), band(101, null, "FAIL")))).isEmpty();
    }

    @Test
    void aGapBetweenBandsIsReported() {
        assertThat(blockers(pressure(null, null, band(null, 11, "PASS"), band(16, null, "FAIL"))))
                .containsExactly("'Pressure': no band covers readings from 12 to 15");
    }

    @Test
    void aSingleMissingReadingIsNamed() {
        assertThat(blockers(pressure(null, null, band(null, 11, "PASS"), band(13, null, "FAIL"))))
                .containsExactly("'Pressure': no band covers a reading of 12");
    }

    @Test
    void bandsSharingAnEdgeOverlap() {
        // Inclusive at both ends, so "up to 11" and "from 11" both claim 11.
        assertThat(blockers(pressure(null, null, band(null, 11, "PASS"), band(11, null, "FAIL"))))
                .containsExactly("'Pressure': more than one band covers a reading of 11");
    }

    @Test
    void withNoRangeTheBandsMustBeOpenAtBothEnds() {
        assertThat(blockers(pressure(null, null, band(5, 10, "PASS"), band(11, 20, "FAIL"))))
                .containsExactly(
                        "'Pressure': no band covers readings below 5",
                        "'Pressure': no band covers readings above 20");
    }

    @Test
    void withARangeTheBandsMustReachBothOfItsEnds() {
        assertThat(blockers(pressure(0, 300, band(10, 100, "PASS"), band(101, 299, "FAIL"))))
                .containsExactly(
                        "'Pressure': no band covers readings from 0 to 9",
                        "'Pressure': no band covers a reading of 300");
    }

    @Test
    void threeBandsSharingOneReadingAreReportedOnce() {
        assertThat(blockers(pressure(null, null,
                band(null, 12, "PASS"), band(12, 12, "AMBER"), band(12, null, "FAIL"))))
                .containsExactly("'Pressure': more than one band covers a reading of 12");
    }

    @Test
    void twoBandsOpenAtTheTopOverlapFromWhereTheSecondStarts() {
        assertThat(blockers(pressure(null, null,
                band(null, 9, "PASS"), band(10, null, "AMBER"), band(15, null, "FAIL"))))
                .containsExactly("'Pressure': more than one band covers readings above 14");
    }

    @Test
    void aBandOutsideTheQuestionsRangeIsReported() {
        assertThat(blockers(pressure(0, 100, band(0, 100, "PASS"), band(200, null, "FAIL"))))
                .containsExactly("'Pressure': the band from 200 is outside the question's range, 0 to 100");
    }

    @Test
    void everyBandNeedsAnActiveResult() {
        assertThat(blockers(pressure(null, null, band(null, 11, null), band(12, null, "GONE"))))
                .containsExactly(
                        "'Pressure': the band up to 11 has no result",
                        "'Pressure': the band from 12 is mapped to GONE, which is not an active result type");
    }

    @Test
    void aBandOnAFollowUpIsCheckedLikeAnyOther() {
        Item reading = new Item("q3", "Reading", null, QuestionType.INTEGER, false, List.of(),
                null, null, null, false, null, null, null, "o91", List.of(), List.of(band(null, 11, "PASS")));

        assertThat(DefinitionValidator.publishBlockers(
                doc(yesNo("q2", "Gauge fitted?").withFollow(List.of(reading))), WITH_AMBER))
                .containsExactly("'Reading': no band covers readings above 11");
    }
}
