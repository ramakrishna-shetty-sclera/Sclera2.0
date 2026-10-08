package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What changed between two versions of one procedure, matched on stable keys.
 * Because keys never change, "q7 was modified" is exact; a reworded question is
 * a modification rather than a removal plus an addition the reader has to pair
 * up.
 *
 * One list, not two: sections and questions live in one list in the document, so
 * they do too here. {@code parentKey} says where an item sits — the section or
 * the question it hangs under — which is what makes "moved" meaningful.
 *
 * Reordering is reported without noise. Only a change in the relative order of
 * items present in both versions counts, so inserting one at the top does not
 * mark everything below it as moved.
 *
 * @param thresholdsChanged scoring bands are version-wide rather than attached
 *                          to any item, so a change to them belongs to the
 *                          document and not to a row in the list. It is
 *                          reported separately for the same reason
 *                          {@code identical} has to account for it: two
 *                          versions whose items match exactly are still
 *                          different versions if one of them scores 90 as a
 *                          Pass and the other does not, and the hash already
 *                          knows that.
 * @param targetTypesChanged what the procedure applies to is version-wide in the
 *                           same way: widening v1's extinguishers to v2's
 *                           extinguishers and hose reels changes the version
 *                           without touching a single question.
 * @param documentsChanged  which documents are cited, and which question each
 *                          hangs off, is version content — the same reason
 *                          {@code thresholdsChanged} is reported separately
 *                          rather than folded into a row in the list.
 */
public record DefinitionDiff(
        boolean identical,
        boolean orderChanged,
        boolean thresholdsChanged,
        boolean targetTypesChanged,
        boolean documentsChanged,
        List<ItemChange> items
) {
    public enum Kind { ADDED, REMOVED, MODIFIED }

    /**
     * @param changedFields any of: text, help, type, required, critical,
     *                      options, unit, min, max, rules, workOrder,
     *                      alertProfile, evidenceRequired, weight, followRollup,
     *                      standard, when, parent
     */
    public record ItemChange(
            String key,
            Kind kind,
            String text,
            String parentKey,
            List<String> changedFields) {}

    public static DefinitionDiff between(DefinitionDocument from, DefinitionDocument to) {
        Map<String, Located> before = index(from);
        Map<String, Located> after = index(to);

        List<ItemChange> changes = new ArrayList<>();
        for (Located now : after.values()) {
            Located then = before.get(now.item().key());
            if (then == null) {
                changes.add(change(now, Kind.ADDED, List.of()));
                continue;
            }
            List<String> fields = changedFields(then, now);
            if (!fields.isEmpty()) {
                changes.add(change(now, Kind.MODIFIED, fields));
            }
        }
        for (Located then : before.values()) {
            if (!after.containsKey(then.item().key())) {
                changes.add(change(then, Kind.REMOVED, List.of()));
            }
        }

        boolean orderChanged = relativeOrderChanged(
                List.copyOf(before.keySet()), List.copyOf(after.keySet()));
        boolean thresholdsChanged = !from.thresholds().equals(to.thresholds());
        boolean targetTypesChanged = !from.targetTypes().equals(to.targetTypes());
        boolean documentsChanged = !from.documents().equals(to.documents());

        return new DefinitionDiff(
                changes.isEmpty() && !orderChanged && !thresholdsChanged
                        && !targetTypesChanged && !documentsChanged,
                orderChanged,
                thresholdsChanged,
                targetTypesChanged,
                documentsChanged,
                List.copyOf(changes));
    }

    /** An item plus the key of whatever it hangs under, or null at the top level. */
    private record Located(Item item, String parentKey) {}

    private static ItemChange change(Located located, Kind kind, List<String> fields) {
        return new ItemChange(located.item().key(), kind, located.item().text(),
                located.parentKey(), List.copyOf(fields));
    }

    private static List<String> changedFields(Located before, Located after) {
        Item b = before.item();
        Item a = after.item();
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(b.text(), a.text())) fields.add("text");
        if (!Objects.equals(b.help(), a.help())) fields.add("help");
        if (b.type() != a.type()) fields.add("type");
        if (b.required() != a.required()) fields.add("required");
        if (b.critical() != a.critical()) fields.add("critical");
        if (!sameOptions(b.options(), a.options())) fields.add("options");
        if (!Objects.equals(b.unit(), a.unit())) fields.add("unit");
        if (!Objects.equals(b.min(), a.min())) fields.add("min");
        if (!Objects.equals(b.max(), a.max())) fields.add("max");
        if (!b.rules().equals(a.rules())) fields.add("rules");
        if (b.workOrder() != a.workOrder()) fields.add("workOrder");
        if (!Objects.equals(b.alertProfile(), a.alertProfile())) fields.add("alertProfile");
        if (b.evidenceRequired() != a.evidenceRequired()) fields.add("evidenceRequired");
        if (!Objects.equals(b.weight(), a.weight())) fields.add("weight");
        if (b.followRollup() != a.followRollup()) fields.add("followRollup");
        if (!Objects.equals(b.standard(), a.standard())) fields.add("standard");
        if (!Objects.equals(b.when(), a.when())) fields.add("when");
        if (!Objects.equals(before.parentKey(), after.parentKey())) fields.add("parent");
        return fields;
    }

    /**
     * Options compare by key, label, result and scoring together. Reporting
     * which option changed would need a diff of its own; "the answers changed"
     * is enough to send a reader to look, and the keys make it obvious once
     * they do.
     */
    private static boolean sameOptions(List<Option> before, List<Option> after) {
        if (before.size() != after.size()) {
            return false;
        }
        for (int i = 0; i < before.size(); i++) {
            Option b = before.get(i);
            Option a = after.get(i);
            if (!Objects.equals(b.key(), a.key())
                    || !Objects.equals(b.label(), a.label())
                    || !Objects.equals(b.result(), a.result())
                    || !Objects.equals(b.score(), a.score())
                    || b.excludeFromScoring() != a.excludeFromScoring()) {
                return false;
            }
        }
        return true;
    }

    /** True if the items present in both lists appear in a different order. */
    private static boolean relativeOrderChanged(List<String> before, List<String> after) {
        Set<String> common = new HashSet<>(before);
        common.retainAll(after);
        List<String> b = before.stream().filter(common::contains).toList();
        List<String> a = after.stream().filter(common::contains).toList();
        return !b.equals(a);
    }

    private static Map<String, Located> index(DefinitionDocument document) {
        Map<String, Located> map = new LinkedHashMap<>();
        walk(document.items(), null, map);
        return map;
    }

    private static void walk(List<Item> items, String parentKey, Map<String, Located> into) {
        for (Item item : items) {
            into.put(item.key(), new Located(item, parentKey));
            walk(item.follow(), item.key(), into);
        }
    }
}
