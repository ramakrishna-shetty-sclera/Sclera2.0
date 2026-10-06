package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.RangeRule;
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

    private DefinitionValidator() {
    }

    /**
     * @throws ValidationException listing every structural problem, so one save
     *         tells the author all of them
     */
    public static void validateStructure(DefinitionDocument document) {
        List<String> problems = new ArrayList<>();
        checkItems(document.items(), null, problems);
        if (!problems.isEmpty()) {
            throw new ValidationException(String.join("; ", problems));
        }
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

        checkCondition(item, parent, where, problems);
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
        }
        return blockers;
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

    /** Names an item the way the author sees it, falling back to its key. */
    private static String describe(Item item) {
        if (item.text() != null && !item.text().isBlank()) {
            String text = item.text().strip();
            return "'" + (text.length() <= 60 ? text : text.substring(0, 57) + "…") + "'";
        }
        return item.key() == null ? "An untitled item" : "Item " + item.key();
    }
}
