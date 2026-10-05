package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.controlplane.common.exception.ValidationException;

import java.util.ArrayList;
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

    /** Names an item the way the author sees it, falling back to its key. */
    private static String describe(Item item) {
        if (item.text() != null && !item.text().isBlank()) {
            String text = item.text().strip();
            return "'" + (text.length() <= 60 ? text : text.substring(0, 57) + "…") + "'";
        }
        return item.key() == null ? "An untitled item" : "Item " + item.key();
    }
}
