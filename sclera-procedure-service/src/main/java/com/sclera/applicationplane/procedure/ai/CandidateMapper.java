package com.sclera.applicationplane.procedure.ai;

import com.sclera.applicationplane.procedure.ai.CandidateQuestion.CandidateBand;
import com.sclera.applicationplane.procedure.ai.CandidateQuestion.CandidateFollowUp;
import com.sclera.applicationplane.procedure.ai.CandidateQuestion.CandidateOption;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.RangeRule;
import com.sclera.applicationplane.procedure.definition.DefinitionValidator;
import com.sclera.applicationplane.procedure.domain.ItemSource;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.controlplane.common.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The gate between LLM output and anything persisted. Structured outputs
 * guarantee the shape; this class guarantees the semantics, by deterministic
 * repair where the fix is obvious (a TEXT question carrying options loses the
 * options) and rejection where it is not (no question text). What survives has
 * passed {@link DefinitionValidator#validateStructure} — the same rules a
 * hand-authored save passes — so a candidate is importable by construction.
 *
 * Policy decisions enforced here, not just prompted for: YES_NO / YES_NO_NA
 * options are forced to their canonical labels with Yes→PASS / No→FAIL
 * mapping; every other result mapping is stripped for the author to make;
 * follow-ups nest one level at most (the shape already cannot express more).
 */
@Component
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
public class CandidateMapper {

    private static final Logger log = LoggerFactory.getLogger(CandidateMapper.class);

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";

    /** One rejected item and why — surfaced in logs, never silently dropped. */
    public record Rejected(String text, String reason) {
    }

    /** A survivor paired with the raw LLM item it came from (provenance fields). */
    public record Accepted(CandidateQuestion question, LlmSchemas.LlmQuestion raw) {
    }

    public record SanitizeOutcome(List<Accepted> accepted, List<Rejected> rejected) {
    }

    public SanitizeOutcome sanitizeAll(List<LlmSchemas.LlmQuestion> raw, Set<String> activeResultKeys) {
        List<Accepted> accepted = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        for (LlmSchemas.LlmQuestion question : raw) {
            try {
                accepted.add(new Accepted(sanitize(question.toCandidate(), activeResultKeys), question));
            } catch (ValidationException e) {
                log.warn("Rejected generated question '{}': {}", question.text(), e.getMessage());
                rejected.add(new Rejected(question.text(), e.getMessage()));
            }
        }
        return new SanitizeOutcome(accepted, rejected);
    }

    /**
     * Repairs what can be repaired, rejects what cannot, and proves the result
     * against the structure validator before returning it.
     *
     * @throws ValidationException when the candidate is not salvageable
     */
    public CandidateQuestion sanitize(CandidateQuestion question, Set<String> activeResultKeys) {
        if (isBlank(question.text())) {
            throw new ValidationException("Generated question has no text");
        }
        if (question.type() == null) {
            throw new ValidationException("Generated question has no type");
        }

        QuestionType type = question.type();
        List<CandidateOption> options = sanitizeOptions(type, question.options(), activeResultKeys);
        List<CandidateFollowUp> followUps = type.isChoice()
                ? sanitizeFollowUps(question.followUps(), options.size(), activeResultKeys)
                : List.of();

        CandidateQuestion sanitized = new CandidateQuestion(
                truncate(question.text(), 1000),
                truncate(question.help(), 1000),
                type,
                type != QuestionType.SECTION && question.required(),
                options,
                type == QuestionType.INTEGER ? truncate(question.unit(), 20) : null,
                type == QuestionType.INTEGER ? lower(question.min(), question.max()) : null,
                type == QuestionType.INTEGER ? upper(question.min(), question.max()) : null,
                type == QuestionType.INTEGER ? sanitizeBands(question.rules()) : List.of(),
                truncate(question.standard(), 50),
                followUps);

        // The same gate a hand-authored save passes. Placeholder option keys
        // stand in for the real ones (minted only at import); the validator
        // only cares that a follow-up's `when` names one of them.
        DefinitionValidator.validateStructure(
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA,
                        List.of(toItem(sanitized, null, placeholderKeys())), List.of(), List.of()));
        return sanitized;
    }

    /**
     * The candidate as a definition {@link Item}. Import passes the template's
     * real key minter (under the template lock); validation passes placeholder
     * keys. Item keys stay null either way — {@code KeyMinter.assignKeys}
     * mints those inside the shared draft-save pipeline.
     */
    public Item toItem(CandidateQuestion question, ItemSource source, Supplier<String> optionKeys) {
        // Options only get keys up front when a follow-up needs to point at
        // one; otherwise the pipeline mints them with everything else.
        boolean keyed = !question.followUps().isEmpty();
        List<Option> options = new ArrayList<>();
        for (CandidateOption option : question.options()) {
            options.add(new Option(keyed ? optionKeys.get() : null, option.label(), option.result(), null, false));
        }

        List<Item> follow = new ArrayList<>();
        for (CandidateFollowUp followUp : question.followUps()) {
            follow.add(Item.builder()
                    .text(followUp.text())
                    .help(followUp.help())
                    .type(followUp.type())
                    .required(followUp.required())
                    .options(followUp.options().stream()
                            .map(o -> new Option(null, o.label(), o.result(), null, false)).toList())
                    .unit(followUp.unit())
                    .min(followUp.min())
                    .max(followUp.max())
                    .source(source)
                    .standard(followUp.standard())
                    .when(options.get(followUp.whenOptionIndex()).key())
                    .rules(followUp.rules().stream().map(b -> new RangeRule(b.min(), b.max(), b.result())).toList())
                    .build());
        }

        return Item.builder()
                .text(question.text())
                .help(question.help())
                .type(question.type())
                .required(question.required())
                .options(options)
                .unit(question.unit())
                .min(question.min())
                .max(question.max())
                .source(source)
                .standard(question.standard())
                .follow(follow)
                .rules(question.rules().stream().map(b -> new RangeRule(b.min(), b.max(), b.result())).toList())
                .build();
    }

    // --- repair rules -------------------------------------------------------

    private List<CandidateOption> sanitizeOptions(QuestionType type, List<CandidateOption> options,
                                                  Set<String> activeResultKeys) {
        if (!type.isChoice()) {
            return List.of();
        }
        if (type == QuestionType.YES_NO || type == QuestionType.YES_NO_NA) {
            // Canonical labels, Yes→PASS / No→FAIL — unless the LLM mapped them
            // the other way round (a question phrased so "No" is the good
            // answer), which is kept when both keys are real.
            String yes = resultFor(options, "yes", PASS, activeResultKeys);
            String no = resultFor(options, "no", FAIL, activeResultKeys);
            List<CandidateOption> fixed = new ArrayList<>();
            fixed.add(new CandidateOption("Yes", yes));
            fixed.add(new CandidateOption("No", no));
            if (type == QuestionType.YES_NO_NA) {
                fixed.add(new CandidateOption("N/A", null));
            }
            return fixed;
        }
        // Other choice types: keep labels, drop blank ones; result mapping is
        // the author's decision, so whatever the LLM guessed is stripped.
        return options.stream()
                .filter(o -> !isBlank(o.label()))
                .map(o -> new CandidateOption(truncate(o.label(), 200), null))
                .toList();
    }

    private String resultFor(List<CandidateOption> options, String label, String fallback,
                             Set<String> activeResultKeys) {
        String mapped = options.stream()
                .filter(o -> o.label() != null && o.label().strip().equalsIgnoreCase(label))
                .map(CandidateOption::result)
                .filter(r -> r != null && activeResultKeys.contains(r))
                .findFirst()
                .orElse(fallback);
        return activeResultKeys.contains(mapped) ? mapped : null;
    }

    private List<CandidateFollowUp> sanitizeFollowUps(List<CandidateFollowUp> followUps, int optionCount,
                                                      Set<String> activeResultKeys) {
        List<CandidateFollowUp> out = new ArrayList<>();
        for (CandidateFollowUp followUp : followUps) {
            if (isBlank(followUp.text()) || followUp.type() == null
                    || followUp.type() == QuestionType.SECTION
                    || followUp.whenOptionIndex() < 0 || followUp.whenOptionIndex() >= optionCount) {
                log.warn("Dropped generated follow-up '{}': blank, a section, or pointing at no option",
                        followUp.text());
                continue;
            }
            QuestionType type = followUp.type();
            // A follow-up's own options go through the same repair; its
            // follow-ups do not exist — the shape ends at one level.
            List<CandidateOption> options = sanitizeOptions(type, followUp.options(), activeResultKeys);
            out.add(new CandidateFollowUp(followUp.whenOptionIndex(),
                    truncate(followUp.text(), 1000),
                    truncate(followUp.help(), 1000),
                    type,
                    followUp.required(),
                    options,
                    type == QuestionType.INTEGER ? truncate(followUp.unit(), 20) : null,
                    type == QuestionType.INTEGER ? lower(followUp.min(), followUp.max()) : null,
                    type == QuestionType.INTEGER ? upper(followUp.min(), followUp.max()) : null,
                    type == QuestionType.INTEGER ? sanitizeBands(followUp.rules()) : List.of(),
                    truncate(followUp.standard(), 50)));
        }
        return out;
    }

    private List<CandidateBand> sanitizeBands(List<CandidateBand> bands) {
        return bands.stream()
                .filter(b -> b.min() == null || b.max() == null || b.min() <= b.max())
                // Band results are the author's to map, like option results.
                .map(b -> new CandidateBand(b.min(), b.max(), null))
                .toList();
    }

    /** An inverted range is repaired by swapping rather than discarded. */
    private static Integer lower(Integer min, Integer max) {
        return (min != null && max != null && min > max) ? max : min;
    }

    private static Integer upper(Integer min, Integer max) {
        return (min != null && max != null && min > max) ? min : max;
    }

    private static Supplier<String> placeholderKeys() {
        int[] next = {0};
        return () -> "o" + (++next[0]);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String stripped = s.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
