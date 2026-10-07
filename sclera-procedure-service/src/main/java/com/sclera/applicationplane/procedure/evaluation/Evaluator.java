package com.sclera.applicationplane.procedure.evaluation;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Group;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.RangeRule;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Scope;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Threshold;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.Rollup;
import com.sclera.applicationplane.procedure.evaluation.Verdict.Overall;
import com.sclera.applicationplane.procedure.evaluation.Verdict.QuestionVerdict;
import com.sclera.applicationplane.procedure.evaluation.Verdict.SectionVerdict;
import com.sclera.applicationplane.procedure.evaluation.Verdict.WorkOrder;
import com.sclera.controlplane.common.exception.ValidationException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a set of answers means against one version — the only code in the
 * product that reads an option's points, a question's weight, a reading's
 * bands or the score thresholds and decides anything from them.
 *
 * <p><b>Pure.</b> No Spring, no repository, no request context: the document,
 * the answers and the organization's result ranks come in, a {@link Verdict}
 * goes out. That is what makes it testable without a database, and it is why
 * a parsed version can be cached and a verdict cannot — the ranks are
 * organization state an admin can change.
 *
 * <p><b>Partial answers are the normal case.</b> An inspector sees a running
 * score, so evaluation is called again and again on a half-finished checklist.
 * It reports what is missing; it never refuses because something is.
 *
 * <p><b>Drafts degrade, they do not fail.</b> The author's preview evaluates a
 * draft, which has not been through the publish checks, so a reading may match
 * no band. That produces no result and a reason, not an error.
 */
public final class Evaluator {

    /** Enough digits that a percentage is exact for any procedure an author could write. */
    private static final MathContext PRECISION = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Evaluator() {
    }

    /**
     * Evaluates answers against a document, in plan order: each question,
     * follow-ups by {@code followRollup}, each section weighted by its
     * questions, the sections weighted into the whole, the whole read against
     * the version's bands — and a failed critical question over all of it.
     *
     * @param ranks the organization's result types, inactive ones included
     * @throws ValidationException naming every answer that cannot belong to
     *         this document
     */
    public static Verdict evaluate(DefinitionDocument document, Answers answers, ResultRanks ranks) {
        checkAnswers(document, answers);
        List<Assessed> assessed = assessAll(document, answers, ranks);

        // Every answered question's verdict in display order, and what each
        // top-level question contributes to its section.
        Map<String, QuestionVerdict> questions = new LinkedHashMap<>();
        Map<Item, List<Unit>> contributions = new IdentityHashMap<>();
        for (Assessed question : assessed) {
            contributions.put(question.item(), units(question, true, null, questions, ranks));
        }

        List<Threshold> sectionBands = bands(document, Scope.SECTION);
        List<Threshold> versionBands = bands(document, Scope.VERSION);

        List<SectionVerdict> sections = new ArrayList<>();
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightTotal = BigDecimal.ZERO;
        String mostSevereSection = null;
        int scored = 0;
        for (Group group : document.groups()) {
            List<Unit> units = group.questions().stream()
                    .flatMap(q -> contributions.getOrDefault(q, List.of()).stream())
                    .toList();
            BigDecimal earned = BigDecimal.ZERO;
            BigDecimal possible = BigDecimal.ZERO;
            String worst = null;
            for (Unit unit : units) {
                worst = ranks.mostSevere(worst, unit.result());
                if (unit.isScored()) {
                    BigDecimal weight = BigDecimal.valueOf(unit.weight());
                    earned = earned.add(weight.multiply(unit.earned()));
                    possible = possible.add(weight.multiply(unit.possible()));
                    scored++;
                }
            }
            BigDecimal percentage = possible.signum() > 0
                    ? earned.multiply(HUNDRED).divide(possible, PRECISION)
                    : null;

            // Its own SECTION band when one matches, else its worst question.
            String result = worst;
            if (percentage != null && !sectionBands.isEmpty()) {
                String banded = band(sectionBands, percentage);
                result = banded != null ? banded : worst;
            }
            Item heading = group.section();
            sections.add(new SectionVerdict(heading == null ? null : heading.key(),
                    heading == null ? null : heading.text(), result, cut(percentage)));
            mostSevereSection = ranks.mostSevere(mostSevereSection, result);

            if (percentage != null) {
                // Sections combine by their own weight, not by how many
                // questions they hold; the ungrouped questions weigh 1.
                BigDecimal weight = BigDecimal.valueOf(heading == null ? 1 : weightOf(heading));
                weightedSum = weightedSum.add(weight.multiply(percentage));
                weightTotal = weightTotal.add(weight);
            }
        }
        BigDecimal overallPercentage = weightTotal.signum() > 0
                ? weightedSum.divide(weightTotal, PRECISION)
                : null;

        // With version bands, the score decides; nothing to score means no
        // result from them rather than the bottom band. Without any, the
        // procedure still reaches a verdict: its most severe section.
        String overallResult;
        if (!versionBands.isEmpty()) {
            overallResult = overallPercentage == null ? null : band(versionBands, overallPercentage);
        } else {
            overallResult = mostSevereSection;
        }

        // A failed critical question decides the result whatever the score
        // said; the score is still reported, because "92% but failed on a
        // critical item" is exactly what the inspector needs to see.
        List<String> critical = new ArrayList<>();
        String criticalResult = null;
        List<WorkOrder> workOrders = new ArrayList<>();
        List<String> unanswered = new ArrayList<>();
        Set<String> showing = new HashSet<>();
        int answeredCount = 0;
        for (Assessed question : flatten(assessed)) {
            Item item = question.item();
            showing.add(item.key());
            if (!question.answered()) {
                unanswered.add(item.key());
                continue;
            }
            answeredCount++;
            String decided = questions.get(item.key()).result();
            if (item.critical() && ranks.isFailure(decided)) {
                critical.add(item.key());
                criticalResult = ranks.mostSevere(criticalResult, decided);
            }
            if (item.workOrder() && ranks.isFailure(question.result())) {
                workOrders.add(new WorkOrder(item.key(), item.alertProfile()));
            }
        }
        if (criticalResult != null) {
            overallResult = criticalResult;
        }

        List<String> ignored = answers.keys().stream()
                .filter(key -> !showing.contains(key))
                .sorted()
                .toList();

        Overall overall = new Overall(overallResult, cut(overallPercentage), answeredCount, scored,
                unanswered.isEmpty());
        return new Verdict(overall, sections, List.copyOf(questions.values()), critical, workOrders,
                unanswered, ignored);
    }

    // --- follow-ups and contributions --------------------------------------------

    /**
     * What one question adds to its section: its own result and points, with
     * a weight. A question whose follow-ups fold into it contributes one unit
     * for the lot.
     */
    private record Unit(String result, BigDecimal earned, BigDecimal possible, int weight) {
        boolean isScored() {
            return possible != null && possible.signum() > 0;
        }
    }

    /**
     * The units a question and its showing follow-ups contribute, recording
     * each answered one's verdict on the way.
     *
     * <ul>
     *   <li><b>INDEPENDENT</b> (also null) — the follow-ups count as ordinary
     *       questions beside their parent, each with its own weight.</li>
     *   <li><b>WORST</b> — one unit: the most severe result among parent and
     *       follow-ups, and the lowest score ratio.</li>
     *   <li><b>AVERAGE</b> — one unit: the mean score ratio, and still the most
     *       severe result. There is no band at question level to turn a mean
     *       back into a result, and inventing one would be worse than saying
     *       so.</li>
     * </ul>
     *
     * Folded follow-ups still get a verdict with their own result, marked not
     * counted, so an author sees what each one said.
     */
    private static List<Unit> units(Assessed node, boolean counted, String foldedInto,
                                    Map<String, QuestionVerdict> verdicts, ResultRanks ranks) {
        Item item = node.item();
        String key = item.key();
        if (node.answered()) {
            verdicts.put(key, new QuestionVerdict(key, node.result(), node.earned(), node.possible(),
                    counted, foldedInto != null ? foldedInto : node.reason()));
        }

        Rollup rollup = item.followRollup() == null ? Rollup.INDEPENDENT : item.followRollup();
        boolean folds = rollup != Rollup.INDEPENDENT && !node.follow().isEmpty();

        List<Unit> members = new ArrayList<>();
        Unit own = node.answered()
                ? new Unit(node.result(), node.earned(), node.possible(), weightOf(item))
                : null;
        if (own != null) {
            members.add(own);
        }
        for (Assessed child : node.follow()) {
            members.addAll(units(child, counted && !folds,
                    folds ? "folded into " + key + " (" + rollup + ")" : foldedInto, verdicts, ranks));
        }
        if (!folds) {
            return members;
        }

        Unit folded = fold(own, members, rollup, weightOf(item), ranks);
        verdicts.put(key, new QuestionVerdict(key, folded.result(), folded.earned(), folded.possible(),
                counted, "includes its follow-ups (" + rollup + ")"));
        return List.of(folded);
    }

    /**
     * Parent and follow-ups as one unit. The score is a ratio — lowest under
     * WORST, mean under AVERAGE — put back on the parent's own points scale,
     * or on the largest scale among the follow-ups when the parent carries no
     * points, so a folded question weighs what an unfolded one would.
     */
    private static Unit fold(Unit own, List<Unit> members, Rollup rollup, int weight, ResultRanks ranks) {
        String result = null;
        List<BigDecimal> ratios = new ArrayList<>();
        BigDecimal scale = own != null && own.isScored() ? own.possible() : null;
        for (Unit member : members) {
            result = ranks.mostSevere(result, member.result());
            if (member.isScored()) {
                ratios.add(member.earned().divide(member.possible(), PRECISION));
                if (own == null || !own.isScored()) {
                    scale = scale == null || member.possible().compareTo(scale) > 0 ? member.possible() : scale;
                }
            }
        }
        if (ratios.isEmpty()) {
            return new Unit(result, null, null, weight);
        }
        BigDecimal ratio = rollup == Rollup.WORST
                ? ratios.stream().min(BigDecimal::compareTo).orElseThrow()
                : ratios.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(ratios.size()), PRECISION);
        return new Unit(result, ratio.multiply(scale, PRECISION), scale, weight);
    }

    // --- bands -----------------------------------------------------------------

    /** The document's score bands for one scope; a band with no scope is VERSION. */
    private static List<Threshold> bands(DefinitionDocument document, Scope scope) {
        return document.thresholds().stream()
                .filter(t -> (t.scope() == null ? Scope.VERSION : t.scope()) == scope)
                .toList();
    }

    /**
     * The band a percentage falls in. Bands are whole numbers that share no
     * edge — "up to 89", "from 90" — so a percentage is <b>rounded down</b>
     * before matching: 89.5 reads as 89. A score reaches a band only once it
     * actually gets there; rounding never promotes it to a better result.
     * Null when no band matches, which publishing rules out and a draft may not.
     */
    private static String band(List<Threshold> bands, BigDecimal percentage) {
        long whole = percentage.setScale(0, RoundingMode.FLOOR).longValueExact();
        for (Threshold band : bands) {
            boolean aboveMin = band.min() == null || whole >= band.min();
            boolean belowMax = band.max() == null || whole <= band.max();
            if (aboveMin && belowMax) {
                return blankToNull(band.result());
            }
        }
        return null;
    }

    /** Two decimals, cut rather than rounded, so a reported 89.99 never sits in the 90 band. */
    private static BigDecimal cut(BigDecimal percentage) {
        return percentage == null ? null : percentage.setScale(2, RoundingMode.DOWN);
    }

    private static int weightOf(Item item) {
        return item.weight() == null ? 1 : item.weight();
    }

    private static List<Assessed> flatten(List<Assessed> questions) {
        List<Assessed> out = new ArrayList<>();
        for (Assessed question : questions) {
            out.add(question);
            out.addAll(flatten(question.follow()));
        }
        return out;
    }

    // --- checking the answers --------------------------------------------------

    /**
     * Refuses answers that cannot belong to this version, naming every one at
     * once — the only caller is a service that pinned the version it answers
     * against, so an unknown key is a bug worth hearing about in full.
     *
     * <p>Checked for every answer, reachable or not: a follow-up answered and
     * then hidden by changing its parent is fine, but it still has to be a
     * well-formed answer to that follow-up.
     *
     * @throws ValidationException listing every problem
     */
    static void checkAnswers(DefinitionDocument document, Answers answers) {
        Map<String, Item> byKey = new HashMap<>();
        for (Item item : document.flatten()) {
            if (item.key() != null) {
                byKey.put(item.key(), item);
            }
        }

        List<String> problems = new ArrayList<>();
        for (String key : answers.keys().stream().sorted().toList()) {
            Item item = byKey.get(key);
            if (item == null) {
                problems.add(key + " is not a question in this version");
                continue;
            }
            QuestionType type = item.type();
            if (type == QuestionType.SECTION) {
                problems.add(key + " is a section, which is never answered");
            } else if (type == QuestionType.CHECKBOX) {
                Optional<List<String>> chosen = answers.optionKeys(key);
                if (chosen.isEmpty()) {
                    problems.add(key + " needs a list of option keys");
                } else {
                    chosen.get().stream().filter(o -> option(item, o).isEmpty())
                            .forEach(o -> problems.add(key + " has no option " + o));
                }
            } else if (type.isChoice()) {
                Optional<String> chosen = answers.optionKey(key);
                if (chosen.isEmpty()) {
                    problems.add(key + " needs one option key");
                } else if (option(item, chosen.get()).isEmpty()) {
                    problems.add(key + " has no option " + chosen.get());
                }
            } else if (type == QuestionType.INTEGER && answers.wholeNumber(key).isEmpty()) {
                problems.add(key + " needs a whole number");
            }
            // Text and media take any value: they decide nothing.
        }
        if (!problems.isEmpty()) {
            throw new ValidationException("These answers do not fit this version: "
                    + String.join("; ", problems));
        }
    }

    // --- one question at a time ------------------------------------------------

    /**
     * A question that is showing, what its answer produced, and its showing
     * follow-ups. Questions that are not showing never become one of these.
     *
     * @param earned   points earned, or null when this question is not scored
     * @param possible the most it could have earned, null under the same rule
     */
    record Assessed(
            Item item,
            boolean answered,
            String result,
            BigDecimal earned,
            BigDecimal possible,
            String reason,
            List<Assessed> follow) {
    }

    /**
     * Every top-level question, with the follow-ups that are showing. A
     * top-level question is always showing; a follow-up shows only when its
     * parent shows <em>and</em> was answered with the option its
     * {@code when} names. One that is not showing counts for nothing at all —
     * the reason {@code followRollup} exists is that counting a conditional
     * question like an ordinary one makes a procedure score differently
     * depending on answers nobody controls.
     */
    static List<Assessed> assessAll(DefinitionDocument document, Answers answers, ResultRanks ranks) {
        List<Assessed> out = new ArrayList<>();
        for (Item item : document.items()) {
            if (item.type() != QuestionType.SECTION) {
                out.add(assess(item, answers, ranks));
            }
        }
        return out;
    }

    static Assessed assess(Item item, Answers answers, ResultRanks ranks) {
        Assessed own = answerOf(item, answers, ranks);

        List<String> selected = selected(item, answers);
        List<Assessed> follow = new ArrayList<>();
        for (Item child : item.follow()) {
            if (child.when() != null && selected.contains(child.when())) {
                follow.add(assess(child, answers, ranks));
            }
        }
        return new Assessed(item, own.answered(), own.result(), own.earned(), own.possible(),
                own.reason(), List.copyOf(follow));
    }

    /** The option keys an answer picked — what a follow-up's {@code when} is matched against. */
    private static List<String> selected(Item item, Answers answers) {
        if (item.type() == QuestionType.CHECKBOX) {
            return answers.optionKeys(item.key()).orElse(List.of());
        }
        if (item.type().isChoice()) {
            return answers.optionKey(item.key()).map(List::of).orElse(List.of());
        }
        return List.of();
    }

    /**
     * One question's own result and score, before any follow-up folds in.
     *
     * <ul>
     *   <li><b>Single choice</b> — the chosen option's result and points.</li>
     *   <li><b>{@code CHECKBOX}</b> — the most severe result among the
     *       selections and the fewest points, so result and score tell the
     *       same story: one bad tick is not averaged away.</li>
     *   <li><b>{@code INTEGER}</b> — the result of the one band the reading
     *       falls in, and no points: a band has no score, and a reading that
     *       should affect the score is authored as a choice question.</li>
     *   <li><b>Text and media</b> — recorded, deciding nothing.</li>
     * </ul>
     *
     * <p>A question is scored only when one of its options carries points. Its
     * possible score is then the best points on offer, and an option without
     * points earns zero of them. An option excluded from scoring — "not
     * applicable" — still produces its result but leaves the question out of
     * both earned and possible, so choosing it cannot drag a percentage down.
     */
    private static Assessed answerOf(Item item, Answers answers, ResultRanks ranks) {
        String key = item.key();
        if (!answers.isAnswered(key)) {
            return new Assessed(item, false, null, null, null, null, List.of());
        }

        QuestionType type = item.type();
        if (type == QuestionType.INTEGER) {
            return reading(item, answers.wholeNumber(key).orElseThrow());
        }
        if (!type.isChoice()) {
            return new Assessed(item, true, null, null, null, null, List.of());
        }

        List<Option> chosen = selected(item, answers).stream()
                .map(o -> option(item, o).orElseThrow())
                .toList();

        String result = null;
        for (Option option : chosen) {
            result = ranks.mostSevere(result, resultOf(option));
        }

        BigDecimal possible = possible(item);
        List<Option> scored = chosen.stream().filter(o -> !o.excludeFromScoring()).toList();
        if (scored.isEmpty()) {
            return new Assessed(item, true, result, null, null, "excluded from scoring", List.of());
        }
        if (possible == null) {
            return new Assessed(item, true, result, null, null, null, List.of());
        }
        BigDecimal earned = null;
        for (Option option : scored) {
            BigDecimal points = BigDecimal.valueOf(option.score() == null ? 0 : option.score());
            earned = earned == null || points.compareTo(earned) < 0 ? points : earned;
        }
        return new Assessed(item, true, result, earned, possible, null, List.of());
    }

    /** A number question's band, or a reason when the reading falls in none. */
    private static Assessed reading(Item item, long value) {
        if (item.rules().isEmpty()) {
            // No bands: the reading is recorded and decides nothing.
            return new Assessed(item, true, null, null, null, null, List.of());
        }
        for (RangeRule band : item.rules()) {
            boolean aboveMin = band.min() == null || value >= band.min();
            boolean belowMax = band.max() == null || value <= band.max();
            if (aboveMin && belowMax) {
                return new Assessed(item, true, blankToNull(band.result()), null, null, null, List.of());
            }
        }
        // Publishing guarantees a band for every reading, so this is a draft.
        return new Assessed(item, true, null, null, null, "no band covers a reading of " + value, List.of());
    }

    /** The best points any option offers, ignoring excluded ones; null when none carries points. */
    private static BigDecimal possible(Item item) {
        return item.options().stream()
                .filter(o -> !o.excludeFromScoring() && o.score() != null)
                .map(o -> BigDecimal.valueOf(o.score()))
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    private static Optional<Option> option(Item item, String optionKey) {
        return item.options().stream().filter(o -> optionKey.equals(o.key())).findFirst();
    }

    private static String resultOf(Option option) {
        return blankToNull(option.result());
    }

    private static String blankToNull(String result) {
        return result == null || result.isBlank() ? null : result;
    }
}
