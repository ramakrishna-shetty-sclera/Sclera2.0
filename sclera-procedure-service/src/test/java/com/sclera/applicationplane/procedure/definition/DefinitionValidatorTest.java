package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.DocumentRef;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.RangeRule;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Threshold;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.Rollup;
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
                false, null, false, null, null, null, null, null, List.of(), List.of());
    }

    private static Item yesNo(String key, String text) {
        return item(key, text, QuestionType.YES_NO).withOptions(
                List.of(new Option("o90", "Yes", "PASS", null, false), new Option("o91", "No", "FAIL", null, false)));
    }

    private static DefinitionDocument doc(Item... items) {
        return new DefinitionDocument(2, List.of(items), List.of(), List.of());
    }

    // --- structure: refused on save ------------------------------------------

    @Test
    void aWellFormedDocumentPasses() {
        Item follow = item("q3", "Describe the obstruction", QuestionType.TEXT);
        assertThatCode(() -> DefinitionValidator.validateStructure(doc(
                item("s1", "Fire exits", QuestionType.SECTION),
                yesNo("q2", "Exit clear?").withFollow(List.of(
                        new Item(follow.key(), follow.text(), null, follow.type(), false, false, List.of(),
                                null, null, null, false, null, false, null, null, null, "o91", null, List.of(), List.of()))))))
                .doesNotThrowAnyException();
    }

    @Test
    void aFollowUpMustNameAnAnswerOfItsOwnParent() {
        Item stray = new Item("q3", "Why?", null, QuestionType.TEXT, false, false, List.of(),
                null, null, null, false, null, false, null, null, null, "o77", null, List.of(), List.of());

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
                null, null, null, false, null, false, null, null, null, "o90", null, List.of(), List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(conditional)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("top-level and cannot depend on an answer");
    }

    @Test
    void aFollowUpCannotHangOffAQuestionWithNoAnswers() {
        Item follow = new Item("q3", "Why?", null, QuestionType.TEXT, false, false, List.of(),
                null, null, null, false, null, false, null, null, null, "o90", null, List.of(), List.of());

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
                "psi", null, null, false, null, false, null, null, null, null, null, List.of(), List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(texty)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot have a unit or a range");
    }

    @Test
    void aRangeCannotRunBackwards() {
        Item backwards = new Item("q2", "Reading", null, QuestionType.INTEGER, false, false, List.of(),
                "psi", 300, 10, false, null, false, null, null, null, null, null, List.of(), List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(backwards)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("minimum above its maximum");
    }

    @Test
    void onlyChoiceQuestionsMayRaiseAWorkOrder() {
        Item texty = new Item("q2", "Remarks", null, QuestionType.TEXT, false, false, List.of(),
                null, null, null, true, "ap-std", false, null, null, null, null, null, List.of(), List.of());

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
                null, 300, 10, false, null, false, null, null, null, null, null, List.of(), List.of());
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
        assertThat(DefinitionValidator.publishBlockers(DefinitionDocument.empty(), PASS_FAIL, Set.of()))
                .containsExactly("Add at least one question");
    }

    @Test
    void aSectionAloneIsNotAQuestion() {
        assertThat(DefinitionValidator.publishBlockers(
                doc(item("s1", "Fire exits", QuestionType.SECTION)), PASS_FAIL, Set.of()))
                .containsExactly("Add at least one question");
    }

    @Test
    void aChoiceNeedsTwoAnswersAndOneResult() {
        Item single = item("q2", "Exit clear?", QuestionType.YES_NO)
                .withOptions(List.of(new Option("o3", "Yes", null, null, false)));

        assertThat(DefinitionValidator.publishBlockers(doc(single), PASS_FAIL, Set.of()))
                .hasSize(2)
                .anySatisfy(b -> assertThat(b).contains("needs at least two answers"))
                .anySatisfy(b -> assertThat(b).contains("needs at least one answer mapped to a result"));
    }

    @Test
    void anAnswerCannotBeMappedToAResultTypeTheOrganizationDoesNotHave() {
        Item amber = item("q2", "Exit clear?", QuestionType.YES_NO).withOptions(
                List.of(new Option("o3", "Yes", "PASS", null, false), new Option("o4", "Partly", "AMBER", null, false)));

        assertThat(DefinitionValidator.publishBlockers(doc(amber), PASS_FAIL, Set.of()))
                .singleElement().asString()
                .contains("'Partly' is mapped to AMBER, which is not an active result type");
    }

    @Test
    void aFollowUpIsCheckedLikeAnyOtherQuestion() {
        Item follow = new Item("q3", "Which one?", null, QuestionType.DROPDOWN, false, false,
                List.of(new Option("o4", "Only answer", "PASS", null, false)),
                null, null, null, false, null, false, null, null, null, "o91", null, List.of(), List.of());

        assertThat(DefinitionValidator.publishBlockers(
                doc(yesNo("q2", "Exit clear?").withFollow(List.of(follow))), PASS_FAIL, Set.of()))
                .singleElement().asString().contains("'Which one?' needs at least two answers");
    }

    @Test
    void aReadyDocumentHasNoBlockers() {
        assertThat(DefinitionValidator.publishBlockers(
                doc(item("s1", "Fire exits", QuestionType.SECTION), yesNo("q2", "Exit clear?")), PASS_FAIL, Set.of()))
                .isEmpty();
    }

    // --- bands on a number question ------------------------------------------

    private static final Set<String> WITH_AMBER = Set.of("PASS", "FAIL", "AMBER");

    private static RangeRule band(Integer min, Integer max, String result) {
        return new RangeRule(min, max, result);
    }

    /** A "Pressure" reading, bounded by {@code min}/{@code max} where set. */
    private static Item pressure(Integer min, Integer max, RangeRule... bands) {
        return new Item("q2", "Pressure", null, QuestionType.INTEGER, true, false, List.of(),
                "psi", min, max, false, null, false, null, null, null, null, null, List.of(), List.of(bands));
    }

    private static List<String> blockers(Item item) {
        return DefinitionValidator.publishBlockers(doc(item), WITH_AMBER, Set.of());
    }

    @Test
    void onlyNumberQuestionsMayHaveBands() {
        Item texty = new Item("q2", "Remarks", null, QuestionType.TEXT, false, false, List.of(),
                null, null, null, false, null, false, null, null, null, null, null, List.of(), List.of(band(null, 5, "PASS")));

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(texty)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("'Remarks' is not a number question, so it cannot have bands");
    }

    @Test
    void aSectionCannotHaveBands() {
        Item section = new Item("s1", "Fire exits", null, QuestionType.SECTION, false, false, List.of(),
                null, null, null, false, null, false, null, null, null, null, null, List.of(), List.of(band(null, 5, "PASS")));

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
        Item reading = new Item("q3", "Reading", null, QuestionType.INTEGER, false, false, List.of(),
                null, null, null, false, null, false, null, null, null, "o91", null, List.of(), List.of(band(null, 11, "PASS")));

        assertThat(DefinitionValidator.publishBlockers(
                doc(yesNo("q2", "Gauge fitted?").withFollow(List.of(reading))), WITH_AMBER, Set.of()))
                .containsExactly("'Reading': no band covers readings above 11");
    }

    private static DefinitionDocument scored(List<Threshold> bands, Item... items) {
        return new DefinitionDocument(2, List.of(items), bands, List.of());
    }

    /**
     * A score band on the whole inspection, which is the common case.
     *
     * Named apart from {@link #band} deliberately: that one is a reading band on
     * a number question and returns a RangeRule, this one is a score band on the
     * document and returns a Threshold. The two have identical parameters, so
     * one name could not carry both.
     */
    private static Threshold scoreBand(Integer min, Integer max, String result) {
        return new Threshold(null, min, max, result);
    }

    /** Pass/Amber/Fail as the guide writes it, minus whichever band a test drops. */
    private static final Threshold FAIL_BAND = scoreBand(null, 69, "FAIL");
    private static final Threshold MID_BAND = scoreBand(70, 89, "PASS");
    private static final Threshold TOP_BAND = scoreBand(90, null, "PASS");
    private static final List<Threshold> FULL_SCALE = List.of(FAIL_BAND, MID_BAND, TOP_BAND);

    private static Item scoredYesNo(String key, String text, Integer yes, Integer no) {
        return item(key, text, QuestionType.YES_NO).withOptions(List.of(
                new Option("o90", "Yes", "PASS", yes, false),
                new Option("o91", "No", "FAIL", no, false)));
    }

    @Test
    void aSectionCannotBeCriticalOrRollUpFollowUps() {
        Item section = new Item("s1", "Fire exits", null, QuestionType.SECTION, false, true, List.of(),
                null, null, null, false, null, false, null, null, null, null, Rollup.WORST, List.of(), List.of());

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(section)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot be critical")
                .hasMessageContaining("no follow-ups to roll up");
    }

    @Test
    void aQuestionCannotSayHowFollowUpsContributeWhenItHasNone() {
        Item lonely = new Item("q2", "Exit clear?", null, QuestionType.TEXT, false, false, List.of(),
                null, null, null, false, null, false, null, null, null, null, Rollup.WORST, List.of(), List.of());

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
    void aBandOutsideTheScaleIsRefusedOnSave() {
        // Caught here rather than left to the coverage check, which would
        // report one out-of-range band as two bands overlapping — true of the
        // arithmetic and useless to the author.
        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                scored(List.of(scoreBand(-10, 69, "FAIL"), scoreBand(70, null, "PASS")),
                        yesNo("q2", "Exit clear?"))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("is outside the 0 to 100 scale");

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(
                scored(List.of(scoreBand(0, 200, "PASS")), yesNo("q2", "Exit clear?"))))
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
                        scoredYesNo("q2", "Exit clear?", 10, 0)), PASS_FAIL, Set.of()))
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

    @Test
    void aProcedureThatScoresNothingStillPublishes() {
        // The rule every other rule here risks breaking. Guide §5's "Empty"
        // state: no scoring configured, questions have default equal weight.
        assertThat(DefinitionValidator.publishBlockers(doc(yesNo("q2", "Exit clear?")), PASS_FAIL, Set.of()))
                .isEmpty();
    }

    @Test
    void markingAQuestionCriticalIsNotScoring() {
        // A critical question fails the inspection whatever the arithmetic
        // says, so it is a result rule and not a score. Demanding thresholds
        // for it would hold an author to finishing something they never began.
        Item mustPass = critical(yesNo("q2", "Fire exit blocked?"));

        assertThat(DefinitionValidator.publishBlockers(doc(mustPass), PASS_FAIL, Set.of())).isEmpty();
    }

    @Test
    void sayingHowFollowUpsContributeIsNotScoringEither() {
        Item parent = yesNo("q2", "Exit clear?").withFollow(List.of(
                new Item("q3", "Why not?", null, QuestionType.TEXT, false, false, List.of(),
                        null, null, null, false, null, false, null, null, null, "o91", null, List.of(), List.of())));
        Item rolled = new Item(parent.key(), parent.text(), null, parent.type(), false, false,
                parent.options(), null, null, null, false, null, false, null, null, null, null,
                Rollup.WORST, parent.follow(), List.of());

        assertThat(DefinitionValidator.publishBlockers(doc(rolled), PASS_FAIL, Set.of())).isEmpty();
    }

    @Test
    void scoringWithoutThresholdsCannotBePublished() {
        assertThat(DefinitionValidator.publishBlockers(
                doc(scoredYesNo("q2", "Exit clear?", 10, 0)), PASS_FAIL, Set.of()))
                .containsExactly("Scoring is configured, but no thresholds say what a score means");
    }

    @Test
    void aScaleWithAGapCannotBePublished() {
        assertThat(DefinitionValidator.publishBlockers(
                scored(List.of(FAIL_BAND, TOP_BAND), scoredYesNo("q2", "Exit clear?", 10, 0)), PASS_FAIL, Set.of()))
                .containsExactly("No band covers a score of 70");
    }

    @Test
    void aScaleWithAnOverlapCannotBePublished() {
        assertThat(DefinitionValidator.publishBlockers(
                scored(List.of(FAIL_BAND, scoreBand(60, null, "PASS")),
                        scoredYesNo("q2", "Exit clear?", 10, 0)), PASS_FAIL, Set.of()))
                .containsExactly("Two bands both cover a score of 60");
    }

    @Test
    void aScaleThatStopsShortCannotBePublished() {
        assertThat(DefinitionValidator.publishBlockers(
                scored(List.of(FAIL_BAND, scoreBand(70, 89, "PASS")),
                        scoredYesNo("q2", "Exit clear?", 10, 0)), PASS_FAIL, Set.of()))
                .containsExactly("No band covers a score of 90");
    }

    @Test
    void aBandCannotNameAResultTypeTheOrganizationDoesNotHave() {
        assertThat(DefinitionValidator.publishBlockers(
                scored(List.of(FAIL_BAND, scoreBand(70, 89, "AMBER"), TOP_BAND),
                        scoredYesNo("q2", "Exit clear?", 10, 0)), PASS_FAIL, Set.of()))
                .singleElement().asString()
                .contains("The band 70 to 89 is mapped to AMBER, which is not an active result type");
    }

    @Test
    void aBandMustSayWhatItMeans() {
        assertThat(DefinitionValidator.publishBlockers(
                scored(List.of(FAIL_BAND, scoreBand(70, 89, null), TOP_BAND),
                        scoredYesNo("q2", "Exit clear?", 10, 0)), PASS_FAIL, Set.of()))
                .singleElement().asString().contains("The band 70 to 89 is not mapped to a result");
    }

    @Test
    void halfScoringAQuestionCannotBePublished() {
        Item half = item("q2", "Exit clear?", QuestionType.YES_NO).withOptions(List.of(
                new Option("o90", "Yes", "PASS", 10, false),
                new Option("o91", "No", "FAIL", null, false)));

        assertThat(DefinitionValidator.publishBlockers(scored(FULL_SCALE, half), PASS_FAIL, Set.of()))
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

        assertThat(DefinitionValidator.publishBlockers(scored(FULL_SCALE, withNa), PASS_FAIL, Set.of())).isEmpty();
    }

    @Test
    void aFullyScoredProcedureHasNoBlockers() {
        assertThat(DefinitionValidator.publishBlockers(
                scored(FULL_SCALE,
                        weighted(item("s1", "Fire exits", QuestionType.SECTION), 2),
                        critical(scoredYesNo("q2", "Exit clear?", 10, 0))), PASS_FAIL, Set.of()))
                .isEmpty();
    }

    private static Item weighted(Item item, int weight) {
        return new Item(item.key(), item.text(), item.help(), item.type(), item.required(),
                item.critical(), item.options(), item.unit(), item.min(), item.max(),
                item.workOrder(), item.alertProfile(), item.evidenceRequired(), weight, item.source(), item.standard(),
                item.when(), item.followRollup(), item.follow(), List.of());
    }

    private static Item critical(Item item) {
        return new Item(item.key(), item.text(), item.help(), item.type(), item.required(),
                true, item.options(), item.unit(), item.min(), item.max(),
                item.workOrder(), item.alertProfile(), item.evidenceRequired(), item.weight(), item.source(), item.standard(),
                item.when(), item.followRollup(), item.follow(), List.of());
    }

    // --- documents a version cites -------------------------------------------

    private static DefinitionDocument docWithRefs(List<DocumentRef> refs, Item... items) {
        return new DefinitionDocument(2, List.of(items), List.of(), refs);
    }

    @Test
    void aCitedDocumentMustBeInTheCitableSet() {
        DefinitionDocument doc = docWithRefs(
                List.of(new DocumentRef("doc-1", null)), yesNo("q2", "Exit clear?"));

        assertThat(DefinitionValidator.publishBlockers(doc, PASS_FAIL, Set.of()))
                .containsExactly("The cited document doc-1 does not exist, is inactive, or is not visible to this procedure");
    }

    @Test
    void aCitedDocumentInTheCitableSetIsNotABlocker() {
        DefinitionDocument doc = docWithRefs(
                List.of(new DocumentRef("doc-1", null)), yesNo("q2", "Exit clear?"));

        assertThat(DefinitionValidator.publishBlockers(doc, PASS_FAIL, Set.of("doc-1"))).isEmpty();
    }

    @Test
    void aDocumentsQuestionKeyMustResolve() {
        DefinitionDocument doc = docWithRefs(
                List.of(new DocumentRef("doc-1", "q99")), yesNo("q2", "Exit clear?"));

        assertThat(DefinitionValidator.publishBlockers(doc, PASS_FAIL, Set.of("doc-1")))
                .containsExactly("The cited document doc-1 is attached to question 'q99', which does not exist");
    }

    @Test
    void aDocumentAttachedToARealQuestionIsFine() {
        DefinitionDocument doc = docWithRefs(
                List.of(new DocumentRef("doc-1", "q2")), yesNo("q2", "Exit clear?"));

        assertThat(DefinitionValidator.publishBlockers(doc, PASS_FAIL, Set.of("doc-1"))).isEmpty();
    }

    @Test
    void aDocumentCitedTwiceIsReportedOnce() {
        // Once at procedure level, once against a question — the index this
        // feeds answers "is it still used", not "how many times".
        DefinitionDocument doc = docWithRefs(
                List.of(new DocumentRef("missing-id", null), new DocumentRef("missing-id", "q2")),
                yesNo("q2", "Exit clear?"));

        assertThat(DefinitionValidator.publishBlockers(doc, PASS_FAIL, Set.of()))
                .containsExactly("The cited document missing-id does not exist, is inactive, or is not visible to this procedure");
    }
}
