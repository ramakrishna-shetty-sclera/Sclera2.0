package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Scope;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Threshold;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.controlplane.common.exception.ValidationException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The rules the database can no longer enforce.
 *
 * Collapsing the form into one JSON document bought a great deal — one
 * representation, a stable hash, a cheap "new draft from v3" — and the price is
 * that nothing stops a malformed document being stored except this class. A weak
 * validator means corruption is written now and discovered when an inspector
 * cannot answer a question.
 *
 * Two kinds of rule, deliberately separated:
 *
 * <ul>
 *   <li><b>Structure</b> — a document breaking these is nonsense: a follow-up
 *       hanging off an option that does not exist, options on a free-text
 *       question. Checked on every write, and refused, because storing it is
 *       storing corruption.</li>
 *   <li><b>Readiness</b> — coherent but unfinished: nothing authored yet, a
 *       choice with one answer. Checked at publish and returned as a list, since
 *       an author wants every reason at once rather than one per attempt.</li>
 * </ul>
 *
 * An author must be able to save a half-finished draft. That is the whole reason
 * the two are not one check.
 */
public final class DefinitionValidator {

    /** Scores are percentages, so the bands have to tile exactly this range. */
    private static final int MIN_SCORE = 0;
    private static final int MAX_SCORE = 100;

    private DefinitionValidator() {
    }

    /**
     * @throws ValidationException listing every structural problem, so one save
     *         tells the author all of them
     */
    public static void validateStructure(DefinitionDocument document) {
        List<String> problems = new ArrayList<>();
        checkItems(document.items(), null, problems);
        checkThresholdShape(document, problems);
        if (!problems.isEmpty()) {
            throw new ValidationException(String.join("; ", problems));
        }
    }

    /**
     * What is nonsense about a band rather than unfinished: a range that runs
     * backwards, a bound outside the scale, and a section band in a document
     * with no sections. Whether the bands between them cover every score is a
     * readiness question, because a half-written set of bands is a normal thing
     * to save.
     */
    private static void checkThresholdShape(DefinitionDocument document, List<String> problems) {
        boolean hasSections = document.flatten().stream()
                .anyMatch(i -> i.type() == QuestionType.SECTION);

        for (Threshold band : document.thresholds()) {
            if (band.min() != null && band.max() != null && band.min() > band.max()) {
                problems.add("The band " + describe(band) + " has a minimum above its maximum");
            }
            // Refused here rather than left to the coverage check, which would
            // otherwise report a single out-of-range band as two bands
            // overlapping — true of the arithmetic and useless to the author.
            if (outsideScale(band.min()) || outsideScale(band.max())) {
                problems.add("The band " + describe(band) + " is outside the "
                        + MIN_SCORE + " to " + MAX_SCORE + " scale");
            }
            if (scopeOf(band) == Scope.SECTION && !hasSections) {
                problems.add("The band " + describe(band)
                        + " scores a section, but this procedure has none");
            }
        }
    }

    private static boolean outsideScale(Integer bound) {
        return bound != null && (bound < MIN_SCORE || bound > MAX_SCORE);
    }

    private static void checkItems(List<Item> items, Item parent, List<String> problems) {
        for (Item item : items) {
            check(item, parent, problems);
            checkItems(item.follow(), item, problems);
        }
    }

    private static void check(Item item, Item parent, List<String> problems) {
        String where = describe(item);

        if (item.type() == QuestionType.SECTION) {
            // A section is a heading. Questions that come after it are its
            // siblings in the flat list, not its children — nesting under a
            // section would mean "shown only when the section is answered",
            // and a section is never answered.
            if (!item.follow().isEmpty()) {
                problems.add(where + " is a section and cannot have follow-ups");
            }
            if (!item.options().isEmpty()) {
                problems.add(where + " is a section and cannot have answers");
            }
            if (item.when() != null) {
                problems.add(where + " is a section and cannot be conditional");
            }
            if (item.critical()) {
                problems.add(where + " is a section and cannot be critical: a heading is never answered");
            }
            if (item.followRollup() != null) {
                problems.add(where + " is a section and has no follow-ups to roll up");
            }
            checkWeight(item, where, problems);
            return;
        }

        if (item.type().isChoice()) {
            for (Option option : item.options()) {
                if (option.label() == null || option.label().isBlank()) {
                    problems.add(where + " has an answer with no label");
                }
            }
        } else if (!item.options().isEmpty()) {
            problems.add(where + " is a " + item.type() + " question and cannot have answers");
        }

        if (item.type() != QuestionType.INTEGER) {
            if (item.unit() != null || item.min() != null || item.max() != null) {
                problems.add(where + " is not a number question, so it cannot have a unit or a range");
            }
        } else if (item.min() != null && item.max() != null && item.min() > item.max()) {
            problems.add(where + " has a minimum above its maximum");
        }

        if (item.workOrder() && !item.type().isChoice()) {
            // Work orders fire on a failed answer, and only a choice question
            // has answers that carry a result.
            problems.add(where + " cannot raise a work order: only choice questions produce a result");
        }

        if (item.followRollup() != null && item.follow().isEmpty()) {
            problems.add(where + " says how its follow-ups contribute but has none");
        }

        checkWeight(item, where, problems);
        checkScores(item, where, problems);
        checkCondition(item, parent, where, problems);
    }

    /**
     * Zero is refused, not treated as absent. A weight of zero would silently
     * remove a question from the score, which is a thing an author may well
     * want — but they should say so by not asking the question, not by asking
     * it and quietly discarding the answer.
     */
    private static void checkWeight(Item item, String where, List<String> problems) {
        if (item.weight() != null && item.weight() < 1) {
            problems.add(where + " has a weight below 1; leave it unset to count normally");
        }
    }

    private static void checkScores(Item item, String where, List<String> problems) {
        for (Option option : item.options()) {
            // Zero *is* legal here, and is the normal way to spell "this is the
            // wrong answer". Only a negative score is nonsense.
            if (option.score() != null && option.score() < 0) {
                problems.add(where + ": the answer '" + option.label() + "' cannot score below zero");
            }
            if (option.excludeFromScoring() && option.score() != null) {
                problems.add(where + ": the answer '" + option.label()
                        + "' is excluded from scoring, so it cannot carry a score");
            }
        }
    }

    private static void checkCondition(Item item, Item parent, String where, List<String> problems) {
        if (parent == null) {
            if (item.when() != null) {
                problems.add(where + " is top-level and cannot depend on an answer");
            }
            return;
        }
        if (item.when() == null || item.when().isBlank()) {
            problems.add(where + " is a follow-up and must say which answer shows it");
            return;
        }
        if (!parent.type().isChoice()) {
            problems.add(where + " follows a " + parent.type()
                    + " question, which has no answers to depend on");
            return;
        }
        boolean known = parent.options().stream().anyMatch(o -> item.when().equals(o.key()));
        if (!known) {
            problems.add(where + " depends on an answer that does not belong to "
                    + describe(parent));
        }
    }

    /**
     * Every reason this document cannot be published yet, in the order an author
     * would fix them. Empty means ready.
     *
     * @param activeResultKeys the organization's usable result-type keys
     */
    public static List<String> publishBlockers(DefinitionDocument document, Set<String> activeResultKeys) {
        List<String> blockers = new ArrayList<>();

        if (document.questionCount() == 0) {
            blockers.add("Add at least one question");
        }

        for (Item item : document.flatten()) {
            if (!item.type().isChoice()) {
                continue;
            }
            String where = describe(item);
            if (item.options().size() < 2) {
                blockers.add(where + " needs at least two answers");
            }
            if (item.options().stream().allMatch(o -> o.result() == null || o.result().isBlank())) {
                // Without one, answering it decides nothing and the question
                // cannot contribute to the inspection's result.
                blockers.add(where + " needs at least one answer mapped to a result");
            }
            for (Option option : item.options()) {
                String result = option.result();
                if (result != null && !result.isBlank() && !activeResultKeys.contains(result)) {
                    blockers.add(where + ": the answer '" + option.label() + "' is mapped to "
                            + result + ", which is not an active result type");
                }
            }
            checkScoredConsistently(item, where, blockers);
        }

        checkScoringReadiness(document, activeResultKeys, blockers);
        return blockers;
    }

    /**
     * Half a question's answers scored and half not. The guide calls this the
     * "Partial" state, and it is the one a real author hits — not a document
     * that is wrong, a document that is unfinished. Answers excluded from
     * scoring are not counted either way: that is the whole point of excluding
     * them.
     */
    private static void checkScoredConsistently(Item item, String where, List<String> blockers) {
        List<Option> counted = item.options().stream()
                .filter(o -> !o.excludeFromScoring())
                .toList();
        boolean any = counted.stream().anyMatch(o -> o.score() != null);
        boolean all = counted.stream().allMatch(o -> o.score() != null);
        if (any && !all) {
            blockers.add(where + " scores some of its answers and not others");
        }
    }

    /**
     * What a configured score has to have before it means anything.
     *
     * <b>Silence is not a failure.</b> A procedure that configures no scoring at
     * all publishes exactly as it did before scoring existed — guide §5's
     * "Empty" state, where "questions have default equal weight". Only a
     * procedure that has started scoring is held to finishing.
     */
    private static void checkScoringReadiness(
            DefinitionDocument document, Set<String> activeResultKeys, List<String> blockers) {

        List<Threshold> bands = document.thresholds();

        for (Threshold band : bands) {
            String result = band.result();
            if (result == null || result.isBlank()) {
                blockers.add("The band " + describe(band) + " is not mapped to a result");
            } else if (!activeResultKeys.contains(result)) {
                blockers.add("The band " + describe(band) + " is mapped to " + result
                        + ", which is not an active result type");
            }
        }

        // Specifically the version-wide bands. Section bands score one section
        // and cannot decide the inspection, so a procedure carrying only those
        // has still not said what its total means — which looked like a
        // finished configuration until this counted the right list.
        List<Threshold> whole = bands.stream().filter(b -> scopeOf(b) == Scope.VERSION).toList();
        if (whole.isEmpty() && document.hasScoring()) {
            blockers.add("Scoring is configured, but no thresholds say what a score means");
        }

        for (Scope scope : Scope.values()) {
            checkBandsCover(scope, bands.stream().filter(b -> scopeOf(b) == scope).toList(), blockers);
        }
    }

    /**
     * Every score from 0 to 100 has to land in exactly one band.
     *
     * A gap means a score that maps to no result at all, and an overlap means
     * one that maps to two. Both are reported against the score that exposes
     * them, because "63 falls into no band" is something an author can act on
     * and "your thresholds are wrong" is not.
     */
    private static void checkBandsCover(Scope scope, List<Threshold> bands, List<String> blockers) {
        if (bands.isEmpty()) {
            return;
        }
        List<Threshold> sorted = new ArrayList<>(bands);
        sorted.sort(Comparator.comparingInt(DefinitionValidator::lowOf));

        String which = scope == Scope.SECTION ? " for a section" : "";
        int next = MIN_SCORE;
        for (Threshold band : sorted) {
            int low = lowOf(band);
            int high = highOf(band);
            if (low > next) {
                blockers.add("No band" + which + " covers a score of " + next);
                return;
            }
            if (low < next) {
                blockers.add("Two bands" + which + " both cover a score of " + low);
                return;
            }
            next = high + 1;
        }
        if (next <= MAX_SCORE) {
            blockers.add("No band" + which + " covers a score of " + next);
        }
    }

    /** An open-ended band runs to the end of the scale rather than to infinity. */
    private static int lowOf(Threshold band) {
        return band.min() == null ? MIN_SCORE : band.min();
    }

    private static int highOf(Threshold band) {
        return band.max() == null ? MAX_SCORE : band.max();
    }

    private static Scope scopeOf(Threshold band) {
        return band.scope() == null ? Scope.VERSION : band.scope();
    }

    /** Names a band the way the author wrote it. */
    private static String describe(Threshold band) {
        if (band.min() == null && band.max() == null) {
            return "covering every score";
        }
        if (band.min() == null) {
            return "up to " + band.max();
        }
        if (band.max() == null) {
            return "from " + band.min();
        }
        return band.min() + " to " + band.max();
    }

    /** Names an item the way the author sees it, falling back to its key. */
    private static String describe(Item item) {
        if (item.text() != null && !item.text().isBlank()) {
            String text = item.text().strip();
            return "'" + (text.length() <= 60 ? text : text.substring(0, 57) + "…") + "'";
        }
        return item.key() == null ? "An untitled item" : "Item " + item.key();
    }
}
