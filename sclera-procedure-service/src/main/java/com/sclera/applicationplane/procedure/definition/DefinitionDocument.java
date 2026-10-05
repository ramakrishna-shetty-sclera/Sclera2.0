package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.domain.ItemSource;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.Rollup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole content of one procedure version: what it asks, in what order,
 * what each answer means, and what the answers add up to.
 *
 * <p><b>One flat list.</b> A section is an {@link Item} of type
 * {@code SECTION}, not a level that wraps questions. Array order is display
 * order — there is no order field, so two items cannot claim the same position.
 *
 * <p><b>Follow-ups nest.</b> An item's {@code follow} list holds questions that
 * appear only when the parent is answered a particular way, and {@code when}
 * names the parent option that triggers it. They nest to any depth: a follow-up
 * may have follow-ups of its own.
 *
 * <p><b>Options carry meaning.</b> Each option on a choice question holds a
 * {@code result} — a result-type key such as {@code PASS} or {@code FAIL}, or
 * none at all for an answer that decides nothing. A key rather than a literal,
 * so an organization that later adds its own result type needs no migration
 * here.
 *
 * <p><b>Scoring is declared, not computed.</b> Options carry points, items
 * carry a weight and a critical flag, and {@link Threshold}s turn a final score
 * back into a result type. Nothing here does arithmetic — the evaluation
 * endpoint is a pure function over a published version, and it is the only
 * thing that reads these and decides anything.
 *
 * <p>Stored as the canonical JSON that {@code definition_hash} is taken over,
 * so <b>every field in this record is part of a version's identity</b>. Adding
 * one changes the bytes of anything that uses it; the canonical form omits
 * empty values, so a field left unset costs nothing.
 */
public record DefinitionDocument(
        int schema,
        List<@Valid Item> items,
        List<@Valid Threshold> thresholds) {

    /**
     * 2 — the flat item list. Schema 1 was {@code categories[] -> questions[]}
     * and is not readable here. There was no data worth carrying when the shape
     * changed; once a real organization has published a version there will be,
     * and the next change will have to read both.
     *
     * <p>Scoring did not bump it: every scoring field is optional and absent
     * from a document that does not use one, so a schema-2 document written
     * before scoring existed still reads correctly.
     */
    public static final int CURRENT_SCHEMA = 2;

    public DefinitionDocument {
        items = items == null ? List.of() : List.copyOf(items);
        thresholds = thresholds == null ? List.of() : List.copyOf(thresholds);
    }

    public static DefinitionDocument empty() {
        return new DefinitionDocument(CURRENT_SCHEMA, List.of(), List.of());
    }

    /** Questions at every depth, sections excluded — what "12 questions" means on screen. */
    public int questionCount() {
        return (int) flatten().stream().filter(i -> i.type() != QuestionType.SECTION).count();
    }

    /** Every item, parents before their follow-ups, in display order. */
    public List<Item> flatten() {
        List<Item> out = new ArrayList<>();
        collect(items, out);
        return out;
    }

    private static void collect(List<Item> source, List<Item> out) {
        for (Item item : source) {
            out.add(item);
            collect(item.follow(), out);
        }
    }

    /** True when any scoring at all has been configured. Silence is a valid procedure. */
    public boolean hasScoring() {
        if (!thresholds.isEmpty()) {
            return true;
        }
        return flatten().stream().anyMatch(item ->
                item.weight() != null
                        || item.critical()
                        || item.followRollup() != null
                        || item.options().stream().anyMatch(o -> o.score() != null));
    }

    /**
     * One entry in the list: a section, a question, or a follow-up question.
     *
     * Most fields apply to some types and not others — {@code options} only to
     * choice types, {@code unit}/{@code min}/{@code max} only to INTEGER, and a
     * SECTION uses almost none of them. The document does not try to express
     * that in its shape; the validator enforces it, which keeps one record
     * readable instead of six that mostly repeat each other.
     */
    public record Item(
            @Size(max = 20) String key,

            /** A question, or a section's heading. */
            @NotBlank @Size(max = 1000) String text,

            /** Shown under the question while answering. */
            @Size(max = 1000) String help,

            @NotNull QuestionType type,

            /** Submit is blocked until this is answered. Never set on a section. */
            boolean required,

            /**
             * A failure here fails the whole inspection, whatever the score
             * says. Never set on a section: a heading is never answered, so it
             * can never fail.
             */
            boolean critical,

            /** Choice types only; seeded for YES_NO and YES_NO_NA. */
            List<@Valid Option> options,

            /** INTEGER only — shown beside the field, e.g. "psi". */
            @Size(max = 20) String unit,

            /** INTEGER only. Inclusive. */
            Integer min,
            Integer max,

            /** Raise a work order when this answer fails. */
            boolean workOrder,

            /**
             * Which alert profile the work order uses. A reference only: alert
             * profiles are a later feature, so this is stored and not checked
             * against anything that exists yet.
             */
            @Size(max = 50) String alertProfile,

            /**
             * How much this counts against its siblings. On a SECTION it
             * weights the whole group; on a question it weights that question.
             * Null means 1 — an unweighted procedure scores everything equally,
             * which is what "no scoring configured" has to mean.
             */
            Integer weight,

            /** Null means {@link ItemSource#MANUAL}. */
            ItemSource source,

            /** The standard this came from, e.g. "NFPA 10". */
            @Size(max = 50) String standard,

            /**
             * The parent option key that makes this item appear. Set on
             * follow-ups and on nothing else; a top-level item is always shown.
             */
            @Size(max = 20) String when,

            /** How this item's follow-ups contribute. Null means INDEPENDENT. */
            Rollup followRollup,

            /** Questions shown only when this one is answered a particular way. */
            List<@Valid Item> follow) {

        public Item {
            options = options == null ? List.of() : List.copyOf(options);
            follow = follow == null ? List.of() : List.copyOf(follow);
        }

        public Item withKey(String newKey) {
            return new Item(newKey, text, help, type, required, critical, options, unit, min, max,
                    workOrder, alertProfile, weight, source, standard, when, followRollup, follow);
        }

        public Item withOptions(List<Option> newOptions) {
            return new Item(key, text, help, type, required, critical, newOptions, unit, min, max,
                    workOrder, alertProfile, weight, source, standard, when, followRollup, follow);
        }

        public Item withFollow(List<Item> newFollow) {
            return new Item(key, text, help, type, required, critical, options, unit, min, max,
                    workOrder, alertProfile, weight, source, standard, when, followRollup, newFollow);
        }
    }

    /**
     * One answer a choice question offers.
     *
     * @param result             a result-type key — {@code PASS}, {@code FAIL}, or whatever
     *                           else the organization has defined. Null for an answer that
     *                           decides nothing, such as "Not applicable".
     * @param score              points this answer is worth. Null means the answer scores
     *                           nothing, which is not the same as scoring zero: zero is a
     *                           real score an author chose, and the canonical form keeps it.
     * @param excludeFromScoring the "not applicable" answer. It earns no points
     *                           <em>and</em> does not count towards the total, so choosing it
     *                           cannot drag a score down. This replaced a per-question
     *                           {@code naAllowed} flag: as an answer it records that the
     *                           inspector chose N/A, which a flag could not distinguish from
     *                           nobody answering at all.
     */
    public record Option(
            @Size(max = 20) String key,
            @NotBlank @Size(max = 200) String label,
            @Size(max = 50) String result,
            Integer score,
            boolean excludeFromScoring) {

        public Option withKey(String newKey) {
            return new Option(newKey, label, result, score, excludeFromScoring);
        }
    }

    /**
     * A band of score mapping to a result type — "90 to 100 is a Pass".
     *
     * Open-ended at both ends: a null {@code min} is "anything up to
     * {@code max}", a null {@code max} is "anything from {@code min}". That is
     * what lets three bands cover every score without the author doing boundary
     * arithmetic, and it is why neither bound is {@code @NotNull}.
     *
     * @param scope  what the band is read against. Null means
     *               {@link Scope#VERSION}, the whole inspection, which is the
     *               common case and therefore costs no bytes.
     *               {@link Scope#SECTION} is a single section's own score —
     *               accepted and stored now because the brief rolls results up
     *               through sections, but nothing evaluates it until the
     *               evaluation endpoint does.
     * @param result a result-type key, the same vocabulary an option's
     *               {@code result} uses.
     */
    public record Threshold(
            Scope scope,
            Integer min,
            Integer max,
            @Size(max = 50) String result) {
    }

    /** What a {@link Threshold} is measured against. */
    public enum Scope {
        /** The whole inspection. */
        VERSION,
        /** One section's own score. */
        SECTION
    }
}
