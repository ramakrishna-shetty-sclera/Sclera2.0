package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Category;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Question;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What changed between two versions of one procedure, matched on stable keys.
 * Because keys never change, "q7 was modified" is exact; a renamed question is
 * a modification, not a removal plus an addition.
 *
 * Reordering is reported without noise: a question inserted at the top does
 * not mark every question below it as moved. Only a change in the relative
 * order of items present in both versions counts.
 */
public record DefinitionDiff(
        boolean identical,
        boolean categoryOrderChanged,
        List<CategoryChange> categories,
        List<QuestionChange> questions
) {
    public enum Kind { ADDED, REMOVED, MODIFIED }

    /** changedFields: name, questionOrder. */
    public record CategoryChange(String key, Kind kind, String name, List<String> changedFields) {}

    /** changedFields: text, helpText, type, required, category. */
    public record QuestionChange(String key, Kind kind, String text, String categoryKey, List<String> changedFields) {}

    public static DefinitionDiff between(DefinitionDocument from, DefinitionDocument to) {
        Map<String, Category> fromCats = categoriesByKey(from);
        Map<String, Category> toCats = categoriesByKey(to);
        Map<String, Located> fromQs = questionsByKey(from);
        Map<String, Located> toQs = questionsByKey(to);

        List<CategoryChange> categoryChanges = new ArrayList<>();
        for (Category after : toCats.values()) {
            Category before = fromCats.get(after.key());
            if (before == null) {
                categoryChanges.add(new CategoryChange(after.key(), Kind.ADDED, after.name(), List.of()));
                continue;
            }
            List<String> fields = new ArrayList<>();
            if (!Objects.equals(before.name(), after.name())) {
                fields.add("name");
            }
            if (relativeOrderChanged(questionKeys(before), questionKeys(after))) {
                fields.add("questionOrder");
            }
            if (!fields.isEmpty()) {
                categoryChanges.add(new CategoryChange(after.key(), Kind.MODIFIED, after.name(), fields));
            }
        }
        for (Category before : fromCats.values()) {
            if (!toCats.containsKey(before.key())) {
                categoryChanges.add(new CategoryChange(before.key(), Kind.REMOVED, before.name(), List.of()));
            }
        }

        List<QuestionChange> questionChanges = new ArrayList<>();
        for (Located after : toQs.values()) {
            Located before = fromQs.get(after.question().key());
            if (before == null) {
                questionChanges.add(change(after, Kind.ADDED, List.of()));
                continue;
            }
            List<String> fields = changedFields(before, after);
            if (!fields.isEmpty()) {
                questionChanges.add(change(after, Kind.MODIFIED, fields));
            }
        }
        for (Located before : fromQs.values()) {
            if (!toQs.containsKey(before.question().key())) {
                questionChanges.add(change(before, Kind.REMOVED, List.of()));
            }
        }

        boolean categoryOrderChanged = relativeOrderChanged(
                List.copyOf(fromCats.keySet()), List.copyOf(toCats.keySet()));

        return new DefinitionDiff(
                categoryChanges.isEmpty() && questionChanges.isEmpty() && !categoryOrderChanged,
                categoryOrderChanged,
                List.copyOf(categoryChanges),
                List.copyOf(questionChanges));
    }

    private record Located(Question question, String categoryKey) {}

    private static QuestionChange change(Located q, Kind kind, List<String> fields) {
        return new QuestionChange(q.question().key(), kind, q.question().text(), q.categoryKey(), List.copyOf(fields));
    }

    private static List<String> changedFields(Located before, Located after) {
        Question b = before.question();
        Question a = after.question();
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(b.text(), a.text())) fields.add("text");
        if (!Objects.equals(b.helpText(), a.helpText())) fields.add("helpText");
        if (b.type() != a.type()) fields.add("type");
        if (b.required() != a.required()) fields.add("required");
        if (!Objects.equals(before.categoryKey(), after.categoryKey())) fields.add("category");
        return fields;
    }

    /** True if the items present in both lists appear in a different order. */
    private static boolean relativeOrderChanged(List<String> before, List<String> after) {
        Set<String> common = new HashSet<>(before);
        common.retainAll(after);
        List<String> b = before.stream().filter(common::contains).toList();
        List<String> a = after.stream().filter(common::contains).toList();
        return !b.equals(a);
    }

    private static List<String> questionKeys(Category category) {
        return category.questions().stream().map(Question::key).toList();
    }

    private static Map<String, Category> categoriesByKey(DefinitionDocument doc) {
        Map<String, Category> map = new LinkedHashMap<>();
        for (Category c : doc.categories()) {
            map.put(c.key(), c);
        }
        return map;
    }

    private static Map<String, Located> questionsByKey(DefinitionDocument doc) {
        Map<String, Located> map = new LinkedHashMap<>();
        for (Category c : doc.categories()) {
            for (Question q : c.questions()) {
                map.put(q.key(), new Located(q, c.key()));
            }
        }
        return map;
    }
}
