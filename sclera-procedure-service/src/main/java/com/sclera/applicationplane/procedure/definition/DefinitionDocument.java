package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.domain.ItemSource;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.Rollup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

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
 * <p><b>What it applies to.</b> {@code targetTypes} names the kinds of place and
 * thing the procedure is for — an asset class, a hierarchy level, a location
 * type, a tag — each a key from the property vocabulary. Empty means it applies
 * to anything, which is what every version written before this field existed
 * means and must keep meaning. It belongs to the document and not the template:
 * a procedure can legitimately widen what it applies to between versions, and
 * that is a change to the version's content, so it is part of the hash.
 *
 * <p><b>Documents are cited by id, not copied in.</b> {@link DocumentRef} names
 * a row in the organization's (or one property's) reference-document library —
 * never a name, a size or a location, all of which resolve live from that row.
 * Renaming a document in the library reaches every version citing it; the
 * citation itself, and which question it hangs off, is what changes the hash.
 *
 * <p>Stored as the canonical JSON that {@code definition_hash} is taken over,
 * so <b>every field in this record is part of a version's identity</b>. Adding
 * one changes the bytes of anything that uses it; the canonical form omits
 * empty values, so a field left unset costs nothing.
 */
public record DefinitionDocument(
        int schema,
        List<@Valid Item> items,
        List<@Valid Threshold> thresholds,
        List<@Valid TargetType> targetTypes,
        List<@Valid DocumentRef> documents) {

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
        targetTypes = targetTypes == null ? List.of() : List.copyOf(targetTypes);
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public static DefinitionDocument empty() {
        return new DefinitionDocument(CURRENT_SCHEMA, List.of(), List.of(), List.of(), List.of());
    }

    /** Questions at every depth, sections excluded — what "12 questions" means on screen. */
    public int questionCount() {
        return (int) flatten().stream().filter(i -> i.type() != QuestionType.SECTION).count();
    }

    /**
     * Every result-type key the document names, once each and in sorted order —
     * what publishing records in {@code version_result_type_ref}. Answers,
     * reading bands and score thresholds all count: a type only a number
     * question's band uses, or only the score scale uses, is as much in use as
     * one an answer names. Whatever decides nothing names no key and
     * contributes nothing.
     *
     * <p>Missing one of the three is not a cosmetic slip. This set is the whole
     * input to the guard that refuses deleting a result type a published
     * version depends on, so a key left out here is a key that can be deleted
     * out from under a frozen version — the unreadable record the guard exists
     * to prevent.
     */
    public SortedSet<String> resultTypeKeys() {
        SortedSet<String> keys = new TreeSet<>();
        for (Item item : flatten()) {
            for (Option option : item.options()) {
                addIfNamed(keys, option.result());
            }
            for (RangeRule band : item.rules()) {
                addIfNamed(keys, band.result());
            }
        }
        for (Threshold band : thresholds) {
            addIfNamed(keys, band.result());
        }
        return keys;
    }

    /**
     * Every target type the document names, once each and trimmed, in the order
     * the author wrote them — what publishing records in
     * {@code version_target_type}. An empty list means the procedure applies to
     * anything and records nothing, which is not the same as applying to nothing.
     * A target type with no kind or no key is left out: it is refused on save,
     * so none reaches a publish.
     */
    public Set<TargetType> targetTypeKeys() {
        Set<TargetType> distinct = new LinkedHashSet<>();
        for (TargetType target : targetTypes) {
            if (target.kind() != null && target.key() != null && !target.key().isBlank()) {
                distinct.add(new TargetType(target.kind(), target.key().strip()));
            }
        }
        return distinct;
    }

    private static void addIfNamed(SortedSet<String> keys, String result) {
        if (result != null && !result.isBlank()) {
            keys.add(result);
        }
    }

    /**
     * Every document id this document cites, once each — what publishing
     * records in {@code version_document_ref}. A document cited twice, or
     * cited once at procedure level and again against a question, counts once:
     * the index answers "is this document still used", not "how many times".
     */
    public SortedSet<String> citedDocumentIds() {
        SortedSet<String> ids = new TreeSet<>();
        for (DocumentRef ref : documents) {
            ids.add(ref.id());
        }
        return ids;
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

    /**
     * Top-level items grouped by the section heading they sit under, in display
     * order.
     *
     * <p><b>Membership is positional.</b> A section does not contain its
     * questions — the validator refuses {@code follow} on a {@code SECTION} — so
     * a question belongs to the nearest section heading above it, and a section
     * runs until the next one. Nothing in the document records this; it is
     * worked out here, once, so the evaluator and anything else that asks get
     * the same answer. The screen that shows a procedure groups it the same
     * way ({@code DefinitionView.tsx}).
     *
     * <p>Questions before the first heading form one group with no section.
     * That group exists only when there are such questions, and always comes
     * first. A section with nothing under it is still a group, with no
     * questions. Follow-ups are not grouped separately: they stay inside their
     * parent question, wherever it sits.
     */
    public List<Group> groups() {
        List<Group> groups = new ArrayList<>();
        Item section = null;
        List<Item> questions = new ArrayList<>();
        boolean open = false;
        for (Item item : items) {
            if (item.type() == QuestionType.SECTION) {
                if (open) {
                    groups.add(new Group(section, questions));
                }
                section = item;
                questions = new ArrayList<>();
                open = true;
            } else {
                questions.add(item);
                open = true;
            }
        }
        if (open) {
            groups.add(new Group(section, questions));
        }
        return groups;
    }

    /**
     * True when this procedure computes a number — points on an answer, a
     * weight, or bands to read the total against. Silence is a valid procedure
     * and must stay publishable.
     *
     * <p><b>{@code critical} and {@code followRollup} are deliberately not
     * counted.</b> Both decide a <em>result</em> rather than a score: a critical
     * question fails the inspection whatever the arithmetic says, and a rollup
     * says which of several results wins. Either is perfectly sensible on a
     * procedure that never scores anything, so counting them here would demand
     * thresholds from an author who asked for none.
     */
    public boolean hasScoring() {
        if (!thresholds.isEmpty()) {
            return true;
        }
        return flatten().stream().anyMatch(item ->
                item.weight() != null
                        || item.options().stream().anyMatch(o -> o.score() != null));
    }

    /**
     * A section heading and the top-level questions under it — what
     * {@link #groups()} returns.
     *
     * @param section   the {@code SECTION} item, or null for the questions that
     *                  come before the first heading
     * @param questions in display order, each still carrying its follow-ups
     */
    public record Group(Item section, List<Item> questions) {
        public Group {
            questions = List.copyOf(questions);
        }
    }

    /**
     * One entry in the list: a section, a question, or a follow-up question.
     *
     * Most fields apply to some types and not others — {@code options} only to
     * choice types, {@code unit}/{@code min}/{@code max}/{@code rules} only to INTEGER, and a
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
             * Submit is blocked until at least one piece of evidence is
             * attached to this question — a rule, not a question type, so a
             * {@code YES_NO} question can demand a photo without becoming an
             * {@code IMAGE} question. Never set on a section: a heading is
             * never answered, so nothing can be attached to it.
             */
            boolean evidenceRequired,

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
            List<@Valid Item> follow,

            /**
             * INTEGER only. What a reading means — bands, each mapping a range
             * to a result type, so 9 can be Pass, 13 Amber and 20 Required.
             * Empty means the reading is recorded and decides nothing, which is
             * a legitimate thing for a meter reading to do.
             *
             * {@code min}/{@code max} above are different: they bound what the
             * inspector may type. These say what the typed value means.
             */
            List<@Valid RangeRule> rules) {

        public Item {
            options = options == null ? List.of() : List.copyOf(options);
            follow = follow == null ? List.of() : List.copyOf(follow);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }

        public Item withKey(String newKey) {
            return new Item(newKey, text, help, type, required, critical, options, unit, min, max,
                    workOrder, alertProfile, evidenceRequired, weight, source, standard, when, followRollup,
                    follow, rules);
        }

        public Item withOptions(List<Option> newOptions) {
            return new Item(key, text, help, type, required, critical, newOptions, unit, min, max,
                    workOrder, alertProfile, evidenceRequired, weight, source, standard, when, followRollup,
                    follow, rules);
        }

        public Item withFollow(List<Item> newFollow) {
            return new Item(key, text, help, type, required, critical, options, unit, min, max,
                    workOrder, alertProfile, evidenceRequired, weight, source, standard, when, followRollup,
                    newFollow, rules);
        }
    }

    /**
     * A band of readings mapping to a result type — "12 to 15 is Amber".
     *
     * Open-ended at both ends: a null {@code min} is "anything up to
     * {@code max}", a null {@code max} is "anything from {@code min}", so three
     * bands cover every reading without the author doing boundary arithmetic.
     * Both bounds are inclusive and bands share no number: after a band ending at
     * 11 the next starts at 12. The same convention as the score bands that turn
     * a percentage into a result, so the two read the same way round.
     *
     * @param result a result-type key, the same vocabulary an option's
     *               {@code result} uses.
     */
    public record RangeRule(
            Integer min,
            Integer max,
            @Size(max = 50) String result) {
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

    /**
     * One kind of target the procedure applies to: a key from one of the four
     * property-vocabulary lists. Only the shape is checked on save; whether the
     * key exists is a publish question, because a draft naming a key that has
     * not been created yet is a normal thing to save.
     */
    public record TargetType(
            @NotNull TargetKind kind,
            @NotBlank @Size(max = 50) String key) {
    }

    /**
     * Which vocabulary list a {@link TargetType} key belongs to. The names match
     * the vocabulary service's and are stored on every published version that
     * names one, so none is ever renamed.
     */
    public enum TargetKind {
        HIERARCHY_LEVEL,
        LOCATION_TYPE,
        ASSET_CLASS,
        ASSET_TAG
    }

    /** What a {@link Threshold} is measured against. */
    public enum Scope {
        /** The whole inspection. */
        VERSION,
        /** One section's own score. */
        SECTION
    }

    /**
     * Cites one row of the reference-document library by id — never its name,
     * size or location, all of which resolve live so a rename in the library
     * reaches every version citing it instead of freezing a stale copy.
     *
     * @param id          the library row's id. Not validated against anything
     *                    at structure time: a draft citing a document that does
     *                    not exist yet, or no longer resolves, is a normal
     *                    intermediate state, checked only at publish.
     * @param questionKey which question this document is attached to, or null
     *                    for a document attached to the whole procedure.
     */
    public record DocumentRef(
            @NotBlank @Size(max = 50) String id,
            @Size(max = 20) String questionKey) {
    }
}
