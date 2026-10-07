package com.sclera.applicationplane.procedure.evaluation;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a set of answers means against one version.
 *
 * <p><b>Keys only, never names or colours.</b> A result type's name and colour
 * resolve live everywhere; putting them here is how a consumer ends up copying
 * them. A caller that shows a verdict already has the organization's result
 * types to colour it with.
 *
 * <p><b>Absent is not zero.</b> A null score or percentage means there was
 * nothing to score yet — nothing answered, or nothing answered that carries
 * points — and a null result means nothing decided one. Reporting 0% instead
 * would read the bottom band and say an inspection that has barely started
 * has failed.
 *
 * @param overall    the inspection as a whole
 * @param sections   one per section, in display order, the questions before the
 *                   first heading included as a section with no key
 * @param questions  every reachable question that was answered, in display order
 * @param critical   critical questions whose failure decided the overall result
 * @param workOrders answered questions flagged for a work order whose result failed
 * @param unanswered reachable questions not answered yet
 * @param ignored    answers given for questions that are not showing — a follow-up
 *                   whose parent was answered differently. Reported, not refused:
 *                   it is what changing the parent's answer leaves behind
 */
public record Verdict(
        Overall overall,
        List<SectionVerdict> sections,
        List<QuestionVerdict> questions,
        List<String> critical,
        List<WorkOrder> workOrders,
        List<String> unanswered,
        List<String> ignored) {

    public Verdict {
        sections = List.copyOf(sections);
        questions = List.copyOf(questions);
        critical = List.copyOf(critical);
        workOrders = List.copyOf(workOrders);
        unanswered = List.copyOf(unanswered);
        ignored = List.copyOf(ignored);
    }

    /**
     * No overall earned/possible points: sections combine by their own weight,
     * after each has become a percentage, so the overall figure is a weighted
     * mean of percentages and no single sum of points would agree with it.
     *
     * @param result     the version-level band the percentage falls in; with no
     *                   such thresholds, the most severe section result;
     *                   overridden by a critical failure either way
     * @param percentage the score, 0 to 100, cut (not rounded) to two decimals;
     *                   null when nothing scorable has been answered
     * @param answered   reachable questions answered
     * @param scored     how many contributions the percentage is made of — a
     *                   question whose follow-ups fold into it counts once
     * @param complete   true when every reachable question is answered — a final
     *                   verdict rather than a running one
     */
    public record Overall(
            String result,
            BigDecimal percentage,
            int answered,
            int scored,
            boolean complete) {
    }

    /**
     * @param key        the section's key, or null for the questions before the
     *                   first heading
     * @param text       its heading, or null with the key — document content, so
     *                   unlike a result type's name it is safe to carry
     * @param result     its own SECTION band if one matches, else the most severe
     *                   of its questions' results
     * @param percentage null when nothing in it was scorable
     */
    public record SectionVerdict(String key, String text, String result, BigDecimal percentage) {
    }

    /**
     * @param result   the result this answer produced, or null when it decides
     *                 nothing
     * @param score    points earned, or null when the question carries no points
     *                 or the answer was excluded from scoring
     * @param possible the most this question could have earned, under the same rule
     * @param counted  false when a follow-up folded into its parent ({@code WORST}
     *                 or {@code AVERAGE}) — still listed, so an author sees what
     *                 each one said
     * @param reason   why a result or score is missing, or why the question was
     *                 not counted; null when there is nothing to explain
     */
    public record QuestionVerdict(
            String key,
            String result,
            BigDecimal score,
            BigDecimal possible,
            boolean counted,
            String reason) {
    }

    /** A failed answer on a question flagged to raise one. */
    public record WorkOrder(String key, String alertProfile) {
    }
}
