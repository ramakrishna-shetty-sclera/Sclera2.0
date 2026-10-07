package com.sclera.applicationplane.procedure.evaluation;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.RangeRule;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Scope;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Threshold;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.Rollup;
import com.sclera.applicationplane.procedure.evaluation.Verdict.QuestionVerdict;
import com.sclera.applicationplane.procedure.evaluation.Verdict.SectionVerdict;
import com.sclera.applicationplane.procedure.evaluation.Verdict.WorkOrder;
import com.sclera.controlplane.common.exception.ValidationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The evaluator, one rule per test, in roughly the order a mistake would show
 * up. No Spring and no database: a document, answers and the organization's
 * ranks go in, a verdict comes out.
 */
class EvaluatorTest {

    /** 1 is most severe. */
    private static final ResultRanks RANKS = ResultRanks.of(Map.of("FAIL", 1, "AMBER", 2, "PASS", 3));

    // --- builders ---------------------------------------------------------------

    /** A question, built up a field at a time; everything unset is the document's default. */
    private static final class Q {
        private final String key;
        private final QuestionType type;
        private List<Option> options = List.of();
        private boolean critical;
        private boolean workOrder;
        private Integer weight;
        private String when;
        private Rollup rollup;
        private List<Item> follow = List.of();
        private List<RangeRule> rules = List.of();

        private Q(String key, QuestionType type) {
            this.key = key;
            this.type = type;
        }

        Q options(Option... value) { options = List.of(value); return this; }
        Q critical() { critical = true; return this; }
        Q workOrder() { workOrder = true; return this; }
        Q weight(int value) { weight = value; return this; }
        Q when(String value) { when = value; return this; }
        Q rollup(Rollup value) { rollup = value; return this; }
        Q follow(Item... value) { follow = List.of(value); return this; }
        Q rules(RangeRule... value) { rules = List.of(value); return this; }

        Item build() {
            return new Item(key, "Question " + key, null, type, false, critical, options, null, null, null,
                    workOrder, workOrder ? "ap-" + key : null, false, weight, null, null, when, rollup, follow, rules);
        }
    }

    private static Q q(String key, QuestionType type) {
        return new Q(key, type);
    }

    /** Yes passes for 10 points, No fails for none — the usual scored Yes/No. */
    private static Q yesNo(String key) {
        return q(key, QuestionType.YES_NO).options(opt(key + "y", "PASS", 10), opt(key + "n", "FAIL", 0));
    }

    private static Option opt(String key, String result, Integer score) {
        return new Option(key, key, result, score, false);
    }

    private static Option notApplicable(String key) {
        return new Option(key, "N/A", null, null, true);
    }

    private static Item section(String key, Integer weight) {
        return new Item(key, "Section " + key, null, QuestionType.SECTION, false, false, List.of(), null, null,
                null, false, null, false, weight, null, null, null, null, List.of(), List.of());
    }

    private static Threshold band(Integer min, Integer max, String result) {
        return new Threshold(null, min, max, result);
    }

    /** Up to 59 fails, 60 to 89 is Amber, 90 and over passes. */
    private static final List<Threshold> BANDS =
            List.of(band(null, 59, "FAIL"), band(60, 89, "AMBER"), band(90, null, "PASS"));

    private static DefinitionDocument doc(List<Threshold> thresholds, Object... items) {
        List<Item> built = new ArrayList<>();
        for (Object item : items) {
            built.add(item instanceof Q question ? question.build() : (Item) item);
        }
        return new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, built, thresholds);
    }

    private static Verdict evaluate(DefinitionDocument document, Object... keyValuePairs) {
        Map<String, Object> answers = new java.util.HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            answers.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return Evaluator.evaluate(document, Answers.of(answers), RANKS);
    }

    private static QuestionVerdict question(Verdict verdict, String key) {
        return verdict.questions().stream().filter(q -> q.key().equals(key)).findFirst().orElseThrow();
    }

    private static BigDecimal pct(String value) {
        return new BigDecimal(value);
    }

    // --- the verdict as a whole -----------------------------------------------------

    @Nested
    class Overall {

        @Test
        void aProcedureThatNeverScoresStillReachesAVerdict() {
            // First, because every scoring rule risks breaking it: no points
            // and no bands, so the most severe answer decides.
            DefinitionDocument document = doc(List.of(),
                    q("q1", QuestionType.YES_NO).options(opt("o1", "PASS", null), opt("o2", "FAIL", null)),
                    q("q3", QuestionType.RADIO).options(opt("o4", "PASS", null), opt("o5", "AMBER", null)));

            Verdict verdict = evaluate(document, "q1", "o1", "q3", "o5");

            assertThat(verdict.overall().result()).isEqualTo("AMBER");
            assertThat(verdict.overall().percentage()).isNull();
            assertThat(verdict.overall().scored()).isZero();
        }

        @Test
        void nothingAnsweredIsNoScoreRatherThanZero() {
            // 0% would read the bottom band and fail an inspection that has
            // not started.
            Verdict verdict = evaluate(doc(BANDS, yesNo("q1"), yesNo("q2")));

            assertThat(verdict.overall().percentage()).isNull();
            assertThat(verdict.overall().result()).isNull();
            assertThat(verdict.overall().complete()).isFalse();
            assertThat(verdict.unanswered()).containsExactly("q1", "q2");
        }

        @Test
        void aRunningScoreCountsOnlyWhatWasAnswered() {
            DefinitionDocument document = doc(BANDS,
                    yesNo("q1"), yesNo("q2"), yesNo("q3"), yesNo("q4"), yesNo("q5"), yesNo("q6"));

            Verdict verdict = evaluate(document, "q1", "q1y", "q2", "q2y", "q3", "q3y");

            // How you are doing, not how far along you are: 100%, not 50%.
            assertThat(verdict.overall().percentage()).isEqualByComparingTo(pct("100"));
            assertThat(verdict.overall().result()).isEqualTo("PASS");
            assertThat(verdict.overall().answered()).isEqualTo(3);
            assertThat(verdict.overall().complete()).isFalse();
        }

        @Test
        void aPercentageBetweenTwoBandsIsRoundedDown() {
            // 179 of 200 is 89.5: "up to 89" and "from 90" share no edge, and
            // a score only reaches a band once it gets there.
            DefinitionDocument document = doc(List.of(band(null, 89, "AMBER"), band(90, null, "PASS")),
                    q("q1", QuestionType.RADIO).options(opt("o2", "PASS", 179), opt("o3", "PASS", 200)));

            Verdict verdict = evaluate(document, "q1", "o2");

            assertThat(verdict.overall().percentage()).isEqualByComparingTo(pct("89.50"));
            assertThat(verdict.overall().result()).isEqualTo("AMBER");
        }

        @Test
        void aPercentageIsCutToTwoDecimalsNotRounded() {
            // 2 of 3 is 66.666…; reported as 66.66 so it never shows a figure
            // a band would read differently.
            DefinitionDocument document = doc(BANDS,
                    q("q1", QuestionType.RADIO).options(opt("o2", "PASS", 2), opt("o3", "PASS", 3)));

            assertThat(evaluate(document, "q1", "o2").overall().percentage()).isEqualTo(pct("66.66"));
        }

        @Test
        void aDraftWhoseBandsHaveAGapGivesNoResultRatherThanAnError() {
            // The author's preview evaluates drafts, which publishing has not
            // checked. 55 sits in the gap between "up to 50" and "from 60".
            DefinitionDocument document = doc(List.of(band(null, 50, "FAIL"), band(60, null, "PASS")),
                    q("q1", QuestionType.RADIO).options(opt("o2", "PASS", 55), opt("o3", "PASS", 100)));

            Verdict verdict = evaluate(document, "q1", "o2");

            assertThat(verdict.overall().percentage()).isEqualByComparingTo(pct("55"));
            assertThat(verdict.overall().result()).isNull();
        }

        @Test
        void completeOnceEveryShowingQuestionIsAnswered() {
            Verdict verdict = evaluate(doc(BANDS, yesNo("q1"), yesNo("q2")), "q1", "q1y", "q2", "q2n");

            assertThat(verdict.overall().complete()).isTrue();
            assertThat(verdict.unanswered()).isEmpty();
        }
    }

    // --- one question --------------------------------------------------------------

    @Nested
    class Questions {

        @Test
        void aSingleChoiceTakesTheChosenOptionsResultAndPoints() {
            QuestionVerdict verdict = question(evaluate(doc(BANDS, yesNo("q1")), "q1", "q1n"), "q1");

            assertThat(verdict.result()).isEqualTo("FAIL");
            assertThat(verdict.score()).isEqualByComparingTo("0");
            assertThat(verdict.possible()).isEqualByComparingTo("10");
        }

        @Test
        void anOptionWithoutPointsOnAScoredQuestionEarnsZero() {
            // Scored or not is a property of the question; "not counted" is
            // what not-applicable is for.
            DefinitionDocument document = doc(BANDS,
                    q("q1", QuestionType.YES_NO).options(opt("o2", "PASS", 10), opt("o3", "FAIL", null)));

            Verdict verdict = evaluate(document, "q1", "o3");

            assertThat(question(verdict, "q1").score()).isEqualByComparingTo("0");
            assertThat(verdict.overall().percentage()).isEqualByComparingTo("0");
        }

        @Test
        void aCheckboxTakesTheWorstOfItsSelectionsOnResultAndScore() {
            DefinitionDocument document = doc(BANDS, q("q1", QuestionType.CHECKBOX)
                    .options(opt("o2", "PASS", 10), opt("o3", "FAIL", 0), opt("o4", "PASS", 8)));

            QuestionVerdict verdict = question(evaluate(document, "q1", List.of("o2", "o3")), "q1");

            assertThat(verdict.result()).isEqualTo("FAIL");
            assertThat(verdict.score()).isEqualByComparingTo("0");
            assertThat(verdict.possible()).isEqualByComparingTo("10");
        }

        @Test
        void notApplicableLeavesBothSidesOfTheFraction() {
            DefinitionDocument document = doc(BANDS, yesNo("q1"),
                    q("q2", QuestionType.DROPDOWN).options(opt("o3", "PASS", 10), notApplicable("o4")));

            Verdict verdict = evaluate(document, "q1", "q1y", "q2", "o4");

            // 10 of 10, not 10 of 20: choosing N/A cannot drag a score down.
            assertThat(verdict.overall().percentage()).isEqualByComparingTo("100");
            assertThat(question(verdict, "q2").reason()).isEqualTo("excluded from scoring");
            assertThat(verdict.overall().scored()).isEqualTo(1);
        }

        @Test
        void aProcedureAnsweredOnlyWithNotApplicableHasNoScore() {
            DefinitionDocument document = doc(BANDS,
                    q("q1", QuestionType.DROPDOWN).options(opt("o2", "PASS", 10), notApplicable("o3")));

            Verdict verdict = evaluate(document, "q1", "o3");

            assertThat(verdict.overall().percentage()).isNull();
            assertThat(verdict.overall().result()).isNull();
        }

        @Test
        void aReadingTakesItsBandsResultAndNoPoints() {
            DefinitionDocument document = doc(List.of(), q("q1", QuestionType.INTEGER).rules(
                    new RangeRule(null, 149, "PASS"), new RangeRule(150, 160, "AMBER"), new RangeRule(161, null, "FAIL")));

            QuestionVerdict verdict = question(evaluate(document, "q1", 155), "q1");

            assertThat(verdict.result()).isEqualTo("AMBER");
            assertThat(verdict.score()).isNull();
            assertThat(verdict.possible()).isNull();
        }

        @Test
        void aReadingInNoBandSaysSoOnADraft() {
            DefinitionDocument document = doc(List.of(),
                    q("q1", QuestionType.INTEGER).rules(new RangeRule(null, 11, "PASS"), new RangeRule(16, null, "FAIL")));

            QuestionVerdict verdict = question(evaluate(document, "q1", 12), "q1");

            assertThat(verdict.result()).isNull();
            assertThat(verdict.reason()).isEqualTo("no band covers a reading of 12");
        }

        @Test
        void withScoreBandsAReadingDoesNotMoveTheOverallResult() {
            // Bands carry no points, and with score bands the score alone
            // decides. A reading that must be able to fail the inspection is
            // marked critical.
            Object reading = q("q2", QuestionType.INTEGER).rules(new RangeRule(null, 160, "PASS"), new RangeRule(161, null, "FAIL"));

            Verdict scored = evaluate(doc(BANDS, yesNo("q1"), reading), "q1", "q1y", "q2", 200);
            assertThat(scored.overall().result()).isEqualTo("PASS");
            assertThat(question(scored, "q2").result()).isEqualTo("FAIL");

            // Without score bands the most severe result decides, readings included.
            Verdict unscored = evaluate(doc(List.of(), yesNo("q1"), reading), "q1", "q1y", "q2", 200);
            assertThat(unscored.overall().result()).isEqualTo("FAIL");
        }

        @Test
        void textAndMediaAreRecordedAndDecideNothing() {
            Verdict verdict = evaluate(doc(List.of(), q("q1", QuestionType.TEXT), q("q2", QuestionType.IMAGE)),
                    "q1", "Looks fine", "q2", Map.of("evidence", "e-1"));

            assertThat(verdict.questions()).extracting(QuestionVerdict::result).containsOnlyNulls();
            assertThat(verdict.overall().answered()).isEqualTo(2);
            assertThat(verdict.overall().result()).isNull();
        }
    }

    // --- follow-ups -------------------------------------------------------------------

    @Nested
    class FollowUps {

        /** "Done with notes" shows a follow-up worth 5 points; Broken fails it. */
        private DefinitionDocument withFollowUp(Rollup rollup) {
            Q parent = q("q1", QuestionType.RADIO)
                    .options(opt("o2", "PASS", 10), opt("o3", "PASS", 10))
                    .follow(q("q4", QuestionType.RADIO).when("o3")
                            .options(opt("o5", "PASS", 5), opt("o6", "FAIL", 0)).build());
            if (rollup != null) {
                parent.rollup(rollup);
            }
            return doc(BANDS, parent);
        }

        @Test
        void aFollowUpThatIsNotShowingCountsForNothingAndItsAnswerIsIgnored() {
            Verdict verdict = evaluate(withFollowUp(Rollup.WORST), "q1", "o2", "q4", "o6");

            assertThat(verdict.ignored()).containsExactly("q4");
            assertThat(verdict.questions()).extracting(QuestionVerdict::key).containsExactly("q1");
            assertThat(verdict.overall().percentage()).isEqualByComparingTo("100");
            assertThat(verdict.overall().complete()).isTrue();
        }

        @Test
        void underWorstOneFailedFollowUpFailsItsParent() {
            Verdict verdict = evaluate(withFollowUp(Rollup.WORST), "q1", "o3", "q4", "o6");

            QuestionVerdict parent = question(verdict, "q1");
            assertThat(parent.result()).isEqualTo("FAIL");
            assertThat(parent.score()).isEqualByComparingTo("0");
            assertThat(parent.possible()).isEqualByComparingTo("10");
            assertThat(verdict.overall().percentage()).isEqualByComparingTo("0");

            // Still listed with what it said, but not counted twice.
            QuestionVerdict child = question(verdict, "q4");
            assertThat(child.counted()).isFalse();
            assertThat(child.result()).isEqualTo("FAIL");
        }

        @Test
        void underAverageTheRatiosAverageAndTheResultIsStillTheMostSevere() {
            Verdict verdict = evaluate(withFollowUp(Rollup.AVERAGE), "q1", "o3", "q4", "o6");

            // (10/10 + 0/5) / 2 = 0.5, on the parent's 10-point scale.
            QuestionVerdict parent = question(verdict, "q1");
            assertThat(parent.score()).isEqualByComparingTo("5");
            assertThat(parent.result()).isEqualTo("FAIL");
            assertThat(verdict.overall().percentage()).isEqualByComparingTo("50");
        }

        @Test
        void independentFollowUpsScoreBesideTheirParent() {
            Verdict verdict = evaluate(withFollowUp(null), "q1", "o3", "q4", "o6");

            // 10 + 0 of 10 + 5: 66.66%, and the parent keeps its own result.
            assertThat(verdict.overall().percentage()).isEqualByComparingTo("66.66");
            assertThat(question(verdict, "q1").result()).isEqualTo("PASS");
            assertThat(question(verdict, "q4").counted()).isTrue();
            assertThat(verdict.overall().scored()).isEqualTo(2);
        }
    }

    // --- sections ---------------------------------------------------------------------

    @Nested
    class Sections {

        @Test
        void sectionsCombineByTheirWeightNotByHowManyQuestionsTheyHold() {
            DefinitionDocument document = doc(BANDS,
                    section("s1", 3), yesNo("q2"),
                    section("s3", 1), yesNo("q4"), yesNo("q5"), yesNo("q6"));

            Verdict verdict = evaluate(document, "q2", "q2y", "q4", "q4n", "q5", "q5n", "q6", "q6n");

            // (3 × 100 + 1 × 0) / 4 = 75. One flat sum would say 10/40 = 25.
            assertThat(verdict.sections()).extracting(SectionVerdict::percentage)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(pct("100"), pct("0"));
            assertThat(verdict.overall().percentage()).isEqualByComparingTo("75");
            assertThat(verdict.overall().result()).isEqualTo("AMBER");
        }

        @Test
        void questionWeightsCountInsideTheirSection() {
            DefinitionDocument document = doc(BANDS, section("s1", null), yesNo("q2").weight(3), yesNo("q3"));

            Verdict verdict = evaluate(document, "q2", "q2y", "q3", "q3n");

            assertThat(verdict.overall().percentage()).isEqualByComparingTo("75");
        }

        @Test
        void questionsBeforeTheFirstHeadingAreASectionOfWeightOne() {
            DefinitionDocument document = doc(BANDS, yesNo("q1"), section("s2", 3), yesNo("q3"));

            Verdict verdict = evaluate(document, "q1", "q1n", "q3", "q3y");

            assertThat(verdict.sections()).extracting(SectionVerdict::key).containsExactly(null, "s2");
            assertThat(verdict.overall().percentage()).isEqualByComparingTo("75");
        }

        @Test
        void aSectionsOwnBandDecidesItsResult() {
            // Every answer passes, but the section scored 0 of 10 and its
            // own band says that is a failure.
            List<Threshold> thresholds = List.of(band(null, 100, "PASS"),
                    new Threshold(Scope.SECTION, null, 49, "FAIL"), new Threshold(Scope.SECTION, 50, null, "PASS"));
            DefinitionDocument document = doc(thresholds, section("s1", null),
                    q("q2", QuestionType.RADIO).options(opt("o3", "PASS", 0), opt("o4", "PASS", 10)));

            Verdict verdict = evaluate(document, "q2", "o3");

            assertThat(verdict.sections()).singleElement()
                    .extracting(SectionVerdict::result).isEqualTo("FAIL");
            assertThat(question(verdict, "q2").result()).isEqualTo("PASS");
        }

        @Test
        void withoutItsOwnBandASectionTakesItsMostSevereQuestion() {
            DefinitionDocument document = doc(List.of(), section("s1", null),
                    q("q2", QuestionType.RADIO).options(opt("o3", "PASS", null), opt("o4", "AMBER", null)),
                    q("q5", QuestionType.RADIO).options(opt("o6", "PASS", null)));

            Verdict verdict = evaluate(document, "q2", "o4", "q5", "o6");

            assertThat(verdict.sections()).singleElement()
                    .extracting(SectionVerdict::result).isEqualTo("AMBER");
        }
    }

    // --- critical questions and work orders ---------------------------------------------

    @Nested
    class CriticalAndWorkOrders {

        @Test
        void aCriticalFailureOverridesAPassingScoreAndTheScoreIsStillReported() {
            // q1 fails but still earns 2 of 10; q2 weighs 9 and passes:
            // (1 × 2 + 9 × 10) / (1 × 10 + 9 × 10) = 92%.
            DefinitionDocument document = doc(BANDS,
                    q("q1", QuestionType.YES_NO).critical().options(opt("o2", "PASS", 10), opt("o3", "FAIL", 2)),
                    yesNo("q4").weight(9));

            Verdict verdict = evaluate(document, "q1", "o3", "q4", "q4y");

            assertThat(verdict.overall().percentage()).isEqualByComparingTo("92");
            assertThat(verdict.overall().result()).isEqualTo("FAIL");
            assertThat(verdict.critical()).containsExactly("q1");
        }

        @Test
        void aCriticalQuestionThatPassesChangesNothing() {
            Verdict verdict = evaluate(doc(BANDS, yesNo("q1").critical()), "q1", "q1y");

            assertThat(verdict.critical()).isEmpty();
            assertThat(verdict.overall().result()).isEqualTo("PASS");
        }

        @Test
        void amberIsNotAFailure() {
            // Failure means at least as severe as FAIL; AMBER ranks below it.
            DefinitionDocument document = doc(List.of(), q("q1", QuestionType.RADIO).critical().workOrder()
                    .options(opt("o2", "AMBER", null)));

            Verdict verdict = evaluate(document, "q1", "o2");

            assertThat(verdict.critical()).isEmpty();
            assertThat(verdict.workOrders()).isEmpty();
        }

        @Test
        void aWorkOrderIsRaisedOnlyWhenItsAnswerFails() {
            DefinitionDocument document = doc(BANDS, yesNo("q1").workOrder());

            assertThat(evaluate(document, "q1", "q1y").workOrders()).isEmpty();
            assertThat(evaluate(document, "q1", "q1n").workOrders())
                    .containsExactly(new WorkOrder("q1", "ap-q1"));
        }
    }

    // --- answers that cannot belong ------------------------------------------------------

    @Nested
    class Refusals {

        @Test
        void everyBadAnswerIsNamedAtOnce() {
            DefinitionDocument document = doc(BANDS, section("s1", null), yesNo("q2"),
                    q("q3", QuestionType.INTEGER), q("q4", QuestionType.CHECKBOX).options(opt("o5", "PASS", null)));

            assertThatThrownBy(() -> evaluate(document,
                    "q99", "x", "s1", "anything", "q2", "o77", "q3", "12.5", "q4", List.of("o5", "o6")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("These answers do not fit this version: "
                            + "q2 has no option o77; q3 needs a whole number; q4 has no option o6; "
                            + "q99 is not a question in this version; s1 is a section, which is never answered");
        }

        @Test
        void aChoiceNeedsOneOptionKeyAndACheckboxAList() {
            DefinitionDocument document = doc(BANDS, yesNo("q1"),
                    q("q2", QuestionType.CHECKBOX).options(opt("o3", "PASS", null)));

            assertThatThrownBy(() -> evaluate(document, "q1", List.of("q1y", "q1n"), "q2", Map.of("x", 1)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("q1 needs one option key")
                    .hasMessageContaining("q2 needs a list of option keys");
        }
    }

    // --- the inputs ------------------------------------------------------------------------

    @Nested
    class Ranks {

        @Test
        void aFailureIsAnythingAtLeastAsSevereAsFail() {
            ResultRanks ranks = ResultRanks.of(Map.of("CRITICAL", 1, "FAIL", 2, "AMBER", 3, "PASS", 4));

            // An organization that ranks CRITICAL above Fail gets it treated
            // as a failure, without the evaluator knowing the word.
            assertThat(ranks.isFailure("CRITICAL")).isTrue();
            assertThat(ranks.isFailure("FAIL")).isTrue();
            assertThat(ranks.isFailure("AMBER")).isFalse();
            assertThat(ranks.isFailure("UNKNOWN")).isFalse();
            assertThat(ranks.isFailure(null)).isFalse();
        }

        @Test
        void theMostSevereWinsAndNoResultLosesToAny() {
            assertThat(RANKS.mostSevere("PASS", "AMBER")).isEqualTo("AMBER");
            assertThat(RANKS.mostSevere(null, "PASS")).isEqualTo("PASS");
            assertThat(RANKS.mostSevere("PASS", null)).isEqualTo("PASS");
            // A key the organization does not have ranks below every real one.
            assertThat(RANKS.mostSevere("UNKNOWN", "PASS")).isEqualTo("PASS");
        }

        @Test
        void aDeactivatedTypeStillRanks() {
            // A published version may name a type that was deactivated since,
            // and it must keep deciding what it decided.
            ResultRanks ranks = ResultRanks.of(List.of(type("FAIL", 1, true), type("AMBER", 2, false),
                    type("PASS", 3, true)));

            assertThat(ranks.mostSevere("PASS", "AMBER")).isEqualTo("AMBER");
        }

        @Test
        void theRanksMustIncludeFail() {
            assertThatThrownBy(() -> ResultRanks.of(Map.of("PASS", 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        private com.sclera.applicationplane.procedure.domain.ResultType type(String key, int severity, boolean active) {
            var type = new com.sclera.applicationplane.procedure.domain.ResultType();
            type.setKey(key);
            type.setSeverityOrder(severity);
            type.setActive(active);
            return type;
        }
    }

    @Nested
    class AnswerValues {

        @Test
        void blankMeansUnanswered() {
            Map<String, Object> values = new java.util.HashMap<>();
            values.put("q1", null);
            values.put("q2", "");
            values.put("q3", "   ");
            values.put("q4", List.of());
            values.put("q5", "o6");

            Answers answers = Answers.of(values);

            assertThat(answers.keys()).containsExactly("q5");
        }

        @Test
        void aWholeNumberMayArriveAsANumberOrAString() {
            Answers answers = Answers.of(Map.of("a", 140, "b", 140.0, "c", "140", "d", 140.5, "e", "abc"));

            assertThat(Arrays.asList(answers.wholeNumber("a"), answers.wholeNumber("b"), answers.wholeNumber("c")))
                    .allSatisfy(n -> assertThat(n).contains(140L));
            assertThat(answers.wholeNumber("d")).isEmpty();
            assertThat(answers.wholeNumber("e")).isEmpty();
        }

        @Test
        void aLoneOptionKeyCountsAsOneTick() {
            assertThat(Answers.of(Map.of("q1", "o2")).optionKeys("q1")).contains(List.of("o2"));
        }
    }
}
