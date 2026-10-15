package com.sclera.applicationplane.procedure.export;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.VersionState;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything an exporter may render, already flattened and numbered the way
 * the read-only view shows the checklist — so each format only decides how a
 * row looks, never what a row is.
 */
public record TemplateExport(
        String name,
        String description,
        int versionNo,
        VersionState state,
        OffsetDateTime publishedAt,
        DefinitionDocument definition) {

    /** A section heading, or a question with its display number and nesting depth. */
    public record Row(String number, int depth, boolean section, String whenLabel, Item item) {
    }

    /** Display order with view-page numbering: 1, 2, 2.1 for follow-ups; sections unnumbered. */
    public List<Row> rows() {
        List<Row> out = new ArrayList<>();
        int top = 0;
        for (Item item : definition.items()) {
            if (item.type() == QuestionType.SECTION) {
                out.add(new Row("", 0, true, null, item));
                continue;
            }
            top++;
            collect(out, item, String.valueOf(top), 0, null);
        }
        return out;
    }

    private static void collect(List<Row> out, Item item, String number, int depth, String whenLabel) {
        out.add(new Row(number, depth, false, whenLabel, item));
        int child = 0;
        Map<String, String> optionLabels = item.options().stream()
                .collect(java.util.stream.Collectors.toMap(
                        o -> o.key() == null ? "" : o.key(),
                        DefinitionDocument.Option::label,
                        (a, b) -> a));
        for (Item follow : item.follow()) {
            child++;
            String label = follow.when() == null ? null : optionLabels.get(follow.when());
            collect(out, follow, number + "." + child, depth + 1, label);
        }
    }
}
