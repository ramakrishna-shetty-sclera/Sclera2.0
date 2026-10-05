package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Threshold;
import com.sclera.applicationplane.procedure.domain.Rollup;
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
        return new Item(key, text, null, type, false, false, List.of(), null, null, null,
                false, null, null, null, null, null, null, List.of());
    }

    private static Item yesNo(String key, String text) {
        return item(key, text, QuestionType.YES_NO).withOptions(
                List.of(new Option("o90", "Yes", "PASS", null, false), new Option("o91", "No", "FAIL", null, false)));
    }

    private static DefinitionDocument doc(Item... items) {
        return new DefinitionDocument(2, List.of(items), List.of());
    }

    private static DefinitionDocument scored(List<Threshold> bands, Item... items) {
        return new DefinitionDocument(2, List.of(items), bands);
    }

    /** A band on the whole inspection, which is the common case. */
    private static Threshold band(Integer min, Integer max, String result) {
        return new Threshold(null, min, max, result);
    }

    /** Pass/Amber/Fail as the guide writes it, minus whichever band a test drops. */
    private static final Threshold FAIL_BAND = band(null, 69, "FAIL");
    private static final Threshold MID_BAND = band(70, 89, "PASS");
    private static final Threshold TOP_BAND = band(90, null, "PASS");
    private static final List<Threshold> FULL_SCALE = List.of(FAIL_BAND, MID_BAND, TOP_BAND);

    private static Item scoredYesNo(String key, String text, Integer yes, Integer no) {
        return item(key, text, QuestionType.YES_NO).withOptions(List.of(
                new Option("o90", "Yes", "PASS", yes, false),
                new Option("o91", "No", "FAIL", no, false)));
    }

    // --- structure: refused on save ------------------------------------------

    @Test
    void aWellFormedDocumentPasses() {
        Item follow = item("q3", "Describe the obstruction", QuestionType.TEXT);
        assertThatCode(() -> DefinitionValidator.validateStructure(doc(
                item("s1", "Fire exits", QuestionType.SECTION),
                yesNo("q2", "Exit clear?").withFollow(List.of(
                        new Item(follow.key(), follow.text(), null, follow.type(), false, false, List.of(),
                                null, null, null, false, null, null, null, null, "o91", null, List.of()))))))
                .doesNotThrowAnyException();
    }

    @Test
    void aFollowUpMustNameAnAnswerOfItsOwnParent() {
        Item stray = new Item("q3", "Why?", null, QuestionType.TEXT, false, false, List.of(),
                null, null, null, false, null, null, null, null, "o77", null, List.of());

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
        Item conditional = new Item("q2", "Why?", null, QuestionType.TEXT, false, false, List.of(),
                null, null, null, false, null, null, null, null, "o90", null, List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(conditional)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("top-level and cannot depend on an answer");
    }

    @Test
    void aFollowUpCannotHangOffAQuestionWithNoAnswers() {
        Item follow = new Item("q3", "Why?", null, QuestionType.TEXT, false, false, List.of(),
                null, null, null, false, null, null, null, null, "o90", null, List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                doc(item("q2", "Remarks", QuestionType.TEXT).withFollow(List.of(follow)))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("has no answers to depend on");
    }

    @Test
    void onlyChoiceQuestionsMayHaveAnswers() {
        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                doc(item("q2", "Remarks", QuestionType.TEXT)
                        .withOptions(List.of(new Option("o3", "Yes", "PASS", null, false))))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot have answers");
    }

    @Test
    void onlyNumberQuestionsMayHaveAUnitOrRange() {
        Item texty = new Item("q2", "Remarks", null, QuestionType.TEXT, false, false, List.of(),
                "psi", null, null, false, null, null, null, null, null, null, List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(texty)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot have a unit or a range");
    }

    @Test
    void aRangeCannotRunBackwards() {
        Item backwards = new Item("q2", "Reading", null, QuestionType.INTEGER, false, false, List.of(),
                "psi", 300, 10, false, null, null, null, null, null, null, List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(backwards)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("minimum above its maximum");
    }

    @Test
    void onlyChoiceQuestionsMayRaiseAWorkOrder() {
        Item texty = new Item("q2", "Remarks", null, QuestionType.TEXT, false, false, List.of(),
                null, null, null, true, "ap-std", null, null, null, null, null, List.of());

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
        Item backwards = new Item("q2", "Reading", null, QuestionType.INTEGER, false, false, List.of(),
                null, 300, 10, false, null, null, null, null, null, null, List.of());
        Item withAnswers = item("q3", "Remarks", QuestionType.TEXT)
                .withOptions(List.of(new Option("o4", "Yes", "PASS", null, false)));

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
                .withOptions(List.of(new Option("o3", "Yes", null, null, false)));

        assertThat(DefinitionValidator.publishBlockers(doc(single), PASS_FAIL))
                .hasSize(2)
                .anySatisfy(b -> assertThat(b).contains("needs at least two answers"))
                .anySatisfy(b -> assertThat(b).contains("needs at least one answer mapped to a result"));
    }

    @Test
    void anAnswerCannotBeMappedToAResultTypeTheOrganizationDoesNotHave() {
        Item amber = item("q2", "Exit clear?", QuestionType.YES_NO).withOptions(
                List.of(new Option("o3", "Yes", "PASS", null, false), new Option("o4", "Partly", "AMBER", null, false)));

        assertThat(DefinitionValidator.publishBlockers(doc(amber), PASS_FAIL))
                .singleElement().asString()
                .contains("'Partly' is mapped to AMBER, which is not an active result type");
    }

    @Test
    void aFollowUpIsCheckedLikeAnyOtherQuestion() {
        Item follow = new Item("q3", "Which one?", null, QuestionType.DROPDOWN, false, false,
                List.of(new Option("o4", "Only answer", "PASS", null, false)),
                null, null, null, false, null, null, null, null, "o91", null, List.of());

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

    // --- scoring: structure ---------------------------------------------------

    @Test
    void aSectionCannotBeCriticalOrRollUpFollowUps() {
        Item section = new Item("s1", "Fire exits", null, QuestionType.SECTION, false, true, List.of(),
                null, null, null, false, null, null, null, null, null, Rollup.WORST, List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(section)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot be critical")
                .hasMessageContaining("no follow-ups to roll up");
    }

    @Test
    void aQuestionCannotSayHowFollowUpsContributeWhenItHasNone() {
        Item lonely = new Item("q2", "Exit clear?", null, QuestionType.TEXT, false, false, List.of(),
                null, null, null, false, null, null, null, null, null, Rollup.WORST, List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(lonely)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("says how its follow-ups contribute but has none");
    }

    @Test
    void weightIsAcceptedOnBothASectionAndAQuestion() {
        // The rule it would be natural to write as "sections only", and then
        // have to undo: guide §5's scoring panel weights questions too.
        Item section = weighted(item("s1", "Fire exits", QuestionType.SECTION), 3);
        Item question = weighted(yesNo("q2", "Exit clear?"), 2);

        assertThatCode(() -> DefinitionValidator.validateStructure(doc(section, question)))
                .doesNotThrowAnyException();
    }

    @Test
    void aWeightBelowOneIsRefused() {
        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                doc(weighted(yesNo("q2", "Exit clear?"), 0))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("weight below 1");
    }

    @Test
    void anAnswerCannotScoreBelowZeroButZeroItselfIsFine() {
        assertThatCode(() -> DefinitionValidator.validateStructure(
                doc(scoredYesNo("q2", "Exit clear?", 10, 0))))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                doc(scoredYesNo("q2", "Exit clear?", 10, -1))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot score below zero");
    }

    @Test
    void anExcludedAnswerCannotAlsoCarryAScore() {
        Item naWithScore = item("q2", "Exit clear?", QuestionType.YES_NO_NA).withOptions(List.of(
                new Option("o90", "Yes", "PASS", 10, false),
                new Option("o91", "No", "FAIL", 0, false),
                new Option("o92", "N/A", null, 5, true)));

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(naWithScore)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("excluded from scoring, so it cannot carry a score");
    }

    @Test
    void aBandCannotRunBackwards() {
        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                scored(List.of(band(90, 10, "PASS")), yesNo("q2", "Exit clear?"))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("minimum above its maximum");
    }

    @Test
    void aBandOutsideTheScaleIsRefusedOnSave() {
        // Caught here rather than left to the coverage check, which would
        // report one out-of-range band as two bands overlapping — true of the
        // arithmetic and useless to the author.
        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                scored(List.of(band(-10, 69, "FAIL"), band(70, null, "PASS")),
                        yesNo("q2", "Exit clear?"))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("is outside the 0 to 100 scale");

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                scored(List.of(band(0, 200, "PASS")), yesNo("q2", "Exit clear?"))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("is outside the 0 to 100 scale");
    }

    @Test
    void sectionBandsAloneDoNotSayWhatTheInspectionScored() {
        // A section band scores one section and cannot decide the inspection,
        // so a procedure carrying only those has still not finished. It looked
        // complete until the check counted version bands rather than all bands.
        Threshold forSection = new Threshold(DefinitionDocument.Scope.SECTION, null, null, "PASS");

        assertThat(DefinitionValidator.publishBlockers(
                scored(List.of(forSection),
                        item("s1", "Fire exits", QuestionType.SECTION),
                        scoredYesNo("q2", "Exit clear?", 10, 0)), PASS_FAIL))
                .containsExactly("Scoring is configured, but no thresholds say what a score means");
    }

    @Test
    void aSectionBandNeedsASection() {
        Threshold forSection = new Threshold(DefinitionDocument.Scope.SECTION, 0, 100, "PASS");

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                scored(List.of(forSection), yesNo("q2", "Exit clear?"))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("scores a section, but this procedure has none");
    }

    // --- scoring: readiness ---------------------------------------------------

    @Test
    void aProcedureThatScoresNothingStillPublishes() {
        // The rule every other rule here risks breaking. Guide §5's "Empty"
        // state: no scoring configured, questions have default equal weight.
        assertThat(DefinitionValidator.publishBlockers(doc(yesNo("q2", "Exit clear?")), PASS_FAIL))
                .isEmpty();
    }

    @Test
    void markingAQuestionCriticalIsNotScoring() {
        // A critical question fails the inspection whatever the arithmetic
        // says, so it is a result rule and not a score. Demanding thresholds
        // for it would hold an author to finishing something they never began.
        Item mustPass = critical(yesNo("q2", "Fire exit blocked?"));

        assertThat(DefinitionValidator.publishBlockers(doc(mustPass), PASS_FAIL)).isEmpty();
    }

    @Test
    void sayingHowFollowUpsContributeIsNotScoringEither() {
        Item parent = yesNo("q2", "Exit clear?").withFollow(List.of(
                new Item("q3", "Why not?", null, QuestionType.TEXT, false, false, List.of(),
                        null, null, null, false, null, null, null, null, "o91", null, List.of())));
        Item rolled = new Item(parent.key(), parent.text(), null, parent.type(), false, false,
                parent.options(), null, null, null, false, null, null, null, null, null,
                Rollup.WORST, parent.follow());

        assertThat(DefinitionValidator.publishBlockers(doc(rolled), PASS_FAIL)).isEmpty();
    }

    @Test
    void scoringWithoutThresholdsCannotBePublished() {
        assertThat(DefinitionValidator.publishBlockers(
                doc(scoredYesNo("q2", "Exit clear?", 10, 0)), PASS_FAIL))
                .containsExactly("Scoring is configured, but no thresholds say what a score means");
    }

    @Test
    void aScaleWithAGapCannotBePublished() {
        assertThat(DefinitionValidator.publishBlockers(
                scored(List.of(FAIL_BAND, TOP_BAND), scoredYesNo("q2", "Exit clear?", 10, 0)), PASS_FAIL))
                .containsExactly("No band covers a score of 70");
    }

    @Test
    void aScaleWithAnOverlapCannotBePublished() {
        assertThat(DefinitionValidator.publishBlockers(
                scored(List.of(FAIL_BAND, band(60, null, "PASS")),
                        scoredYesNo("q2", "Exit clear?", 10, 0)), PASS_FAIL))
                .containsExactly("Two bands both cover a score of 60");
    }

    @Test
    void aScaleThatStopsShortCannotBePublished() {
        assertThat(DefinitionValidator.publishBlockers(
                scored(List.of(FAIL_BAND, band(70, 89, "PASS")),
                        scoredYesNo("q2", "Exit clear?", 10, 0)), PASS_FAIL))
                .containsExactly("No band covers a score of 90");
    }

    @Test
    void aBandCannotNameAResultTypeTheOrganizationDoesNotHave() {
        assertThat(DefinitionValidator.publishBlockers(
                scored(List.of(FAIL_BAND, band(70, 89, "AMBER"), TOP_BAND),
                        scoredYesNo("q2", "Exit clear?", 10, 0)), PASS_FAIL))
                .singleElement().asString()
                .contains("The band 70 to 89 is mapped to AMBER, which is not an active result type");
    }

    @Test
    void aBandMustSayWhatItMeans() {
        assertThat(DefinitionValidator.publishBlockers(
                scored(List.of(FAIL_BAND, band(70, 89, null), TOP_BAND),
                        scoredYesNo("q2", "Exit clear?", 10, 0)), PASS_FAIL))
                .singleElement().asString().contains("The band 70 to 89 is not mapped to a result");
    }

    @Test
    void halfScoringAQuestionCannotBePublished() {
        Item half = item("q2", "Exit clear?", QuestionType.YES_NO).withOptions(List.of(
                new Option("o90", "Yes", "PASS", 10, false),
                new Option("o91", "No", "FAIL", null, false)));

        assertThat(DefinitionValidator.publishBlockers(scored(FULL_SCALE, half), PASS_FAIL))
                .containsExactly("'Exit clear?' scores some of its answers and not others");
    }

    @Test
    void anExcludedAnswerDoesNotCountAsUnscored() {
        // The whole point of excluding it: Yes and No are both scored, N/A is
        // neither scored nor missing a score.
        Item withNa = item("q2", "Exit clear?", QuestionType.YES_NO_NA).withOptions(List.of(
                new Option("o90", "Yes", "PASS", 10, false),
                new Option("o91", "No", "FAIL", 0, false),
                new Option("o92", "N/A", null, null, true)));

        assertThat(DefinitionValidator.publishBlockers(scored(FULL_SCALE, withNa), PASS_FAIL)).isEmpty();
    }

    @Test
    void aFullyScoredProcedureHasNoBlockers() {
        assertThat(DefinitionValidator.publishBlockers(
                scored(FULL_SCALE,
                        weighted(item("s1", "Fire exits", QuestionType.SECTION), 2),
                        critical(scoredYesNo("q2", "Exit clear?", 10, 0))), PASS_FAIL))
                .isEmpty();
    }

    private static Item weighted(Item item, int weight) {
        return new Item(item.key(), item.text(), item.help(), item.type(), item.required(),
                item.critical(), item.options(), item.unit(), item.min(), item.max(),
                item.workOrder(), item.alertProfile(), weight, item.source(), item.standard(),
                item.when(), item.followRollup(), item.follow());
    }

    private static Item critical(Item item) {
        return new Item(item.key(), item.text(), item.help(), item.type(), item.required(),
                true, item.options(), item.unit(), item.min(), item.max(),
                item.workOrder(), item.alertProfile(), item.weight(), item.source(), item.standard(),
                item.when(), item.followRollup(), item.follow());
    }
}
