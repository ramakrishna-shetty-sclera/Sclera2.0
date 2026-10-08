package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.DocumentRef;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.RangeRule;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Threshold;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.Rollup;
import com.sclera.controlplane.common.exception.ValidationException;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetType;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefinitionValidatorTest {

    private static final Set<String> PASS_FAIL = Set.of("PASS", "FAIL");

    private static Item item(String key, String text, QuestionType type) {
        return Item.builder().key(key).text(text).type(type).build();
    }

    private static Item yesNo(String key, String text) {
        return item(key, text, QuestionType.YES_NO).withOptions(
                List.of(new Option("o90", "Yes", "PASS", null, false), new Option("o91", "No", "FAIL", null, false)));
    }

    private static DefinitionDocument doc(Item... items) {
        return new DefinitionDocument(2, List.of(items), List.of(), List.of(), List.of());
    }

    // --- structure: refused on save ------------------------------------------

    @Test
    void aWellFormedDocumentPasses() {
        Item follow = item("q3", "Describe the obstruction", QuestionType.TEXT);
        assertThatCode(() -> DefinitionValidator.validateStructure(doc(
                item("s1", "Fire exits", QuestionType.SECTION),
                yesNo("q2", "Exit clear?").withFollow(List.of(
                        Item.builder().key(follow.key()).text(follow.text()).type(follow.type())
                                .when("o91").build())))))
                .doesNotThrowAnyException();
    }

    @Test
    void aFollowUpMustNameAnAnswerOfItsOwnParent() {
        Item stray = Item.builder().key("q3").text("Why?").type(QuestionType.TEXT).when("o77").build();

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
        Item conditional = Item.builder().key("q2").text("Why?").type(QuestionType.TEXT).when("o90").build();

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(conditional)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("top-level and cannot depend on an answer");
    }

    @Test
    void aFollowUpCannotHangOffAQuestionWithNoAnswers() {
        Item follow = Item.builder().key("q3").text("Why?").type(QuestionType.TEXT).when("o90").build();

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
        Item texty = Item.builder().key("q2").text("Remarks").type(QuestionType.TEXT).unit("psi").build();

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(texty)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot have a unit or a range");
    }

    @Test
    void aRangeCannotRunBackwards() {
        Item backwards = Item.builder().key("q2").text("Reading").type(QuestionType.INTEGER)
                .unit("psi").min(300).max(10).build();

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(backwards)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("minimum above its maximum");
    }

    @Test
    void onlyChoiceQuestionsMayRaiseAWorkOrder() {
        Item texty = Item.builder().key("q2").text("Remarks").type(QuestionType.TEXT)
                .workOrder(true).alertProfile("ap-std").build();

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
        Item backwards = Item.builder().key("q2").text("Reading").type(QuestionType.INTEGER)
                .min(300).max(10).build();
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
        Item follow = Item.builder().key("q3").text("Which one?").type(QuestionType.DROPDOWN)
                .options(List.of(new Option("o4", "Only answer", "PASS", null, false)))
                .when("o91").build();

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
        return Item.builder().key("q2").text("Pressure").type(QuestionType.INTEGER).required(true)
                .unit("psi").min(min).max(max).rules(List.of(bands)).build();
    }

    private static List<String> blockers(Item item) {
        return DefinitionValidator.publishBlockers(doc(item), WITH_AMBER, Set.of());
    }

    @Test
    void onlyNumberQuestionsMayHaveBands() {
        Item texty = Item.builder().key("q2").text("Remarks").type(QuestionType.TEXT)
                .rules(List.of(band(null, 5, "PASS"))).build();

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(texty)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("'Remarks' is not a number question, so it cannot have bands");
    }

    @Test
    void aSectionCannotHaveBands() {
        Item section = Item.builder().key("s1").text("Fire exits").type(QuestionType.SECTION)
                .rules(List.of(band(null, 5, "PASS"))).build();

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
        Item reading = Item.builder().key("q3").text("Reading").type(QuestionType.INTEGER)
                .when("o91").rules(List.of(band(null, 11, "PASS"))).build();

        assertThat(DefinitionValidator.publishBlockers(
                doc(yesNo("q2", "Gauge fitted?").withFollow(List.of(reading))), WITH_AMBER, Set.of()))
                .containsExactly("'Reading': no band covers readings above 11");
    }

    private static DefinitionDocument scored(List<Threshold> bands, Item... items) {
        return new DefinitionDocument(2, List.of(items), bands, List.of(), List.of());
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
        Item section = Item.builder().key("s1").text("Fire exits").type(QuestionType.SECTION)
                .critical(true).followRollup(Rollup.WORST).build();

        assertThatThrownBy(() -> DefinitionValidator.validateStructure(doc(section)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot be critical")
                .hasMessageContaining("no follow-ups to roll up");
    }

    @Test
    void aQuestionCannotSayHowFollowUpsContributeWhenItHasNone() {
        Item lonely = Item.builder().key("q2").text("Exit clear?").type(QuestionType.TEXT)
                .followRollup(Rollup.WORST).build();

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
                Item.builder().key("q3").text("Why not?").type(QuestionType.TEXT).when("o91").build()));
        Item rolled = Item.builder().key(parent.key()).text(parent.text()).type(parent.type())
                .options(parent.options()).followRollup(Rollup.WORST).follow(parent.follow()).build();

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
        return item.toBuilder().weight(weight).rules(List.of()).build();
    }

    private static Item critical(Item item) {
        return item.toBuilder().critical(true).rules(List.of()).build();
    }

    // --- target types ----------------------------------------------------------

    private static DefinitionDocument withTargets(TargetType... targets) {
        return new DefinitionDocument(2, List.of(yesNo("q1", "Extinguisher present?")), List.of(), List.of(targets), List.of());
    }

    private static TargetType asset(String key) {
        return new TargetType(TargetKind.ASSET_CLASS, key);
    }

    @Test
    void aTargetTypeListedTwiceIsRefusedOnSave() {
        assertThatThrownBy(() -> DefinitionValidator.validateStructure(withTargets(asset("EXTINGUISHER"), asset("EXTINGUISHER"))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("The target type 'EXTINGUISHER' (asset class) is listed more than once");
    }

    @Test
    void aTargetTypeWithNoKeyIsRefusedOnSave() {
        assertThatThrownBy(() -> DefinitionValidator.validateStructure(withTargets(asset("  "))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("A target type needs both a kind and a key");
    }

    @Test
    void theSameKeyInTwoDifferentListsIsNotADuplicate() {
        assertThatCode(() -> DefinitionValidator.validateStructure(withTargets(
                asset("ROOM"), new TargetType(TargetKind.LOCATION_TYPE, "ROOM"))))
                .doesNotThrowAnyException();
    }

    @Test
    void aKeyTheVocabularyDoesNotHaveStillSavesBecauseThatIsAPublishQuestion() {
        assertThatCode(() -> DefinitionValidator.validateStructure(withTargets(asset("NOT_CREATED_YET"))))
                .doesNotThrowAnyException();
    }

    @Test
    void everyTargetTypeTheVocabularyLacksIsAPublishBlockerNamingItAndItsList() {
        TargetType extinguisher = asset("EXTINGUISHER");
        TargetType floor = new TargetType(TargetKind.HIERARCHY_LEVEL, "FLOOR_9");
        TargetType tag = new TargetType(TargetKind.ASSET_TAG, "OUTDOOR");
        TargetType room = new TargetType(TargetKind.LOCATION_TYPE, "VAULT");

        assertThat(DefinitionValidator.publishBlockers(withTargets(extinguisher, floor, tag, room), PASS_FAIL,
                List.of(extinguisher, floor, tag, room)))
                .containsExactly(
                        "The target type 'EXTINGUISHER' (asset class) is not one of this organization's asset classes",
                        "The target type 'FLOOR_9' (hierarchy level) is not one of this organization's hierarchy levels",
                        "The target type 'OUTDOOR' (asset tag) is not one of this organization's asset tags",
                        "The target type 'VAULT' (location type) is not one of this organization's location types");
    }

    @Test
    void whenTheVocabularyHasEveryKeyThereIsNothingToBlock() {
        assertThat(DefinitionValidator.publishBlockers(withTargets(asset("EXTINGUISHER")), PASS_FAIL, List.of())).isEmpty();
    }

    @Test
    void unknownTargetTypesAreListedBesideTheOtherReasonsNotInsteadOfThem() {
        DefinitionDocument unfinished = new DefinitionDocument(2,
                List.of(item("q1", "Exit clear?", QuestionType.YES_NO)), List.of(), List.of(asset("EXTINGUISHER")), List.of());

        assertThat(DefinitionValidator.publishBlockers(unfinished, PASS_FAIL, List.of(asset("EXTINGUISHER"))))
                .anySatisfy(b -> assertThat(b).contains("needs at least two answers"))
                .anySatisfy(b -> assertThat(b).contains("'EXTINGUISHER' (asset class)"));
    }

    // --- documents a version cites -------------------------------------------

    private static DefinitionDocument docWithRefs(List<DocumentRef> refs, Item... items) {
        return new DefinitionDocument(2, List.of(items), List.of(), List.of(), refs);
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
