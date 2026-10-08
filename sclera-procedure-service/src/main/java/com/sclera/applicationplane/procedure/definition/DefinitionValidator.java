package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.DocumentRef;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.RangeRule;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Scope;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetKind;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetType;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Threshold;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.controlplane.common.exception.ValidationException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
        checkTargetTypeShape(document, problems);
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

    /**
     * What is nonsense about a target type rather than unfinished: no key, or
     * the same one listed twice. Whether the key exists in the vocabulary is a
     * readiness question — a draft naming a key that has not been created yet
     * is a normal thing to save.
     */
    private static void checkTargetTypeShape(DefinitionDocument document, List<String> problems) {
        Set<TargetType> seen = new HashSet<>();
        for (TargetType target : document.targetTypes()) {
            if (target.kind() == null || target.key() == null || target.key().isBlank()) {
                problems.add("A target type needs both a kind and a key");
            } else if (!seen.add(new TargetType(target.kind(), target.key().strip()))) {
                problems.add("The target type " + describe(target) + " is listed more than once");
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
            if (!item.rules().isEmpty()) {
                problems.add(where + " is a section and cannot have bands");
            }
            if (item.critical()) {
                problems.add(where + " is a section and cannot be critical: a heading is never answered");
            }
            if (item.followRollup() != null) {
                problems.add(where + " is a section and has no follow-ups to roll up");
            }
            if (item.evidenceRequired()) {
                problems.add(where + " is a section and cannot require evidence: a heading is never answered");
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
            if (!item.rules().isEmpty()) {
                problems.add(where + " is not a number question, so it cannot have bands");
            }
        } else {
            if (item.min() != null && item.max() != null && item.min() > item.max()) {
                problems.add(where + " has a minimum above its maximum");
            }
            for (RangeRule band : item.rules()) {
                if (band.min() != null && band.max() != null && band.min() > band.max()) {
                    problems.add(where + " has a band that starts above where it ends ("
                            + band.min() + " to " + band.max() + ")");
                }
            }
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
     * @param activeResultKeys   the organization's usable result-type keys
     * @param citableDocumentIds the library documents this procedure may cite —
     *                           active, and either organization-wide or in this
     *                           procedure's own property, per the isolation rule
     */
    public static List<String> publishBlockers(DefinitionDocument document, Set<String> activeResultKeys) {
        return publishBlockers(document, activeResultKeys, List.of());
    }

    /**
     * As above, and every target type the organization's vocabulary does not
     * have is a reason too.
     *
     * <p>The validator stays pure: it cannot ask the vocabulary service, and a
     * remote call does not belong in a rule check. The caller reads the
     * vocabulary and passes in only the target types it did not find, which is
     * all this needs to word the message. A vocabulary that could not be read at
     * all is not something to pass here: that is a different problem, and
     * reporting it as "key not found" would send an author to fix a procedure
     * that is fine.
     *
     * @param unknownTargetTypes the document's target types missing from the
     *                           vocabulary, empty when all are present
     */
    public static List<String> publishBlockers(DefinitionDocument document, Set<String> activeResultKeys,
                                               List<TargetType> unknownTargetTypes) {
        List<String> blockers = new ArrayList<>();

        if (document.questionCount() == 0) {
            blockers.add("Add at least one question");
        }

        for (Item item : document.flatten()) {
            if (item.type() == QuestionType.INTEGER && !item.rules().isEmpty()) {
                // No bands is fine: the reading is recorded and decides
                // nothing. Bands that exist must say what every reading means.
                checkBands(item, describe(item), activeResultKeys, blockers);
                continue;
            }
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
        for (TargetType unknown : unknownTargetTypes) {
            blockers.add("The target type " + describe(unknown) + " is not one of this organization's "
                    + listName(unknown.kind()));
        }
        checkDocumentReferences(document, citableDocumentIds, blockers);
        return blockers;
    }

    /** "ASSET_CLASS" as the author sees the list: "asset classes". */
    private static String listName(TargetKind kind) {
        return switch (kind) {
            case HIERARCHY_LEVEL -> "hierarchy levels";
            case LOCATION_TYPE -> "location types";
            case ASSET_CLASS -> "asset classes";
            case ASSET_TAG -> "asset tags";
        };
    }

    /** 'EXTINGUISHER' (asset class) — the key, and which list it was meant for. */
    private static String describe(TargetType target) {
        String kind = target.kind() == null ? "no kind" : target.kind().name().toLowerCase().replace('_', ' ');
        return "'" + target.key() + "' (" + kind + ")";
    }

    /**
     * Every cited document must exist, be active, and be visible to this
     * procedure's scope — {@code citableDocumentIds} is already narrowed to
     * that, so a miss here is reported without needing to say which of the
     * three failed. A {@code questionKey} naming a question that was since
     * removed is caught the same way there is no foreign key to catch it in
     * the database. A document cited twice — at procedure level and again
     * against a question, or against two questions — is reported once: the
     * question is "is it still used", not "how many times".
     */
    private static void checkDocumentReferences(DefinitionDocument document, Set<String> citableDocumentIds,
                                                 List<String> blockers) {
        Set<String> questionKeys = new HashSet<>();
        for (Item item : document.flatten()) {
            questionKeys.add(item.key());
        }

        Set<String> reported = new HashSet<>();
        for (DocumentRef ref : document.documents()) {
            if (reported.add(ref.id()) && !citableDocumentIds.contains(ref.id())) {
                blockers.add("The cited document " + ref.id()
                        + " does not exist, is inactive, or is not visible to this procedure");
            }
            if (ref.questionKey() != null && !questionKeys.contains(ref.questionKey())) {
                blockers.add("The cited document " + ref.id() + " is attached to question '"
                        + ref.questionKey() + "', which does not exist");
            }
        }
    }

    /** Stands in for an open end. Readings are ints, so neither is ever typed. */
    private static final long BELOW_ALL = Long.MIN_VALUE;
    private static final long ABOVE_ALL = Long.MAX_VALUE;

    private static void checkBands(Item item, String where, Set<String> activeResultKeys,
                                   List<String> blockers) {
        for (RangeRule band : item.rules()) {
            String result = band.result();
            if (result == null || result.isBlank()) {
                blockers.add(where + ": the band " + describe(band) + " has no result");
            } else if (!activeResultKeys.contains(result)) {
                blockers.add(where + ": the band " + describe(band) + " is mapped to "
                        + result + ", which is not an active result type");
            }
        }
        checkCoverage(item, where, blockers);
    }

    /**
     * Every reading the inspector can type must land in exactly one band — the
     * same rule the score bands follow. What can be typed is the question's own
     * min/max, or every whole number where it sets none, so an open question's
     * bands must be open at both ends.
     *
     * Walks the bands in order of where they start, tracking the first reading
     * not yet covered: a band starting after it leaves a gap, one starting
     * before it overlaps. Reports each gap and overlap as a span of readings
     * rather than every number in it.
     */
    private static void checkCoverage(Item item, String where, List<String> blockers) {
        long scaleLo = item.min() == null ? BELOW_ALL : item.min();
        long scaleHi = item.max() == null ? ABOVE_ALL : item.max();
        if (scaleLo > scaleHi) {
            return;                              // a structural problem, reported on save
        }

        List<long[]> spans = new ArrayList<>();
        for (RangeRule band : item.rules()) {
            long lo = band.min() == null ? BELOW_ALL : band.min();
            long hi = band.max() == null ? ABOVE_ALL : band.max();
            if (lo > hi) {
                continue;                        // likewise structural
            }
            long from = Math.max(lo, scaleLo);
            long to = Math.min(hi, scaleHi);
            if (from > to) {
                blockers.add(where + ": the band " + describe(band)
                        + " is outside the question's range, " + range(scaleLo, scaleHi));
                continue;
            }
            spans.add(new long[] {from, to});
        }
        spans.sort(Comparator.comparingLong(s -> s[0]));

        List<long[]> gaps = new ArrayList<>();
        List<long[]> overlaps = new ArrayList<>();
        long next = scaleLo;                     // the first reading no band has covered yet
        boolean coveredToTop = false;
        for (long[] span : spans) {
            if (coveredToTop) {
                overlaps.add(span);
                continue;
            }
            if (span[0] > next) {
                gaps.add(new long[] {next, span[0] - 1});
            } else if (span[0] < next) {
                overlaps.add(new long[] {span[0], Math.min(span[1], next - 1)});
            }
            if (span[1] == ABOVE_ALL) {
                coveredToTop = true;
            } else {
                next = Math.max(next, span[1] + 1);
            }
        }
        if (!coveredToTop && next <= scaleHi) {
            gaps.add(new long[] {next, scaleHi});
        }

        // Three bands sharing 12 overlap twice there; the author needs telling once.
        overlaps.sort(Comparator.comparingLong(s -> s[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] overlap : overlaps) {
            long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && (last[1] == ABOVE_ALL || overlap[0] <= last[1] + 1)) {
                last[1] = Math.max(last[1], overlap[1]);
            } else {
                merged.add(overlap.clone());
            }
        }

        for (long[] gap : gaps) {
            blockers.add(where + ": no band covers " + readings(gap[0], gap[1]));
        }
        for (long[] overlap : merged) {
            blockers.add(where + ": more than one band covers " + readings(overlap[0], overlap[1]));
        }
    }

    /** "a reading of 12", "readings from 12 to 15", "readings below 5", "readings above 20". */
    private static String readings(long lo, long hi) {
        if (lo == BELOW_ALL && hi == ABOVE_ALL) {
            return "any reading";
        }
        if (lo == BELOW_ALL) {
            return "readings below " + (hi + 1);
        }
        if (hi == ABOVE_ALL) {
            return "readings above " + (lo - 1);
        }
        return lo == hi ? "a reading of " + lo : "readings from " + lo + " to " + hi;
    }

    /** A band the way the author wrote it: "up to 11", "12 to 15", "from 16". */
    private static String describe(RangeRule band) {
        return range(band.min() == null ? BELOW_ALL : band.min(),
                band.max() == null ? ABOVE_ALL : band.max());
    }

    private static String range(long lo, long hi) {
        if (lo == BELOW_ALL && hi == ABOVE_ALL) {
            return "with no limits";
        }
        if (lo == BELOW_ALL) {
            return "up to " + hi;
        }
        if (hi == ABOVE_ALL) {
            return "from " + lo;
        }
        return lo + " to " + hi;
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
