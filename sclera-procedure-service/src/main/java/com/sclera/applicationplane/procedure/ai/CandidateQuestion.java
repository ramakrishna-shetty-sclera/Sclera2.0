package com.sclera.applicationplane.procedure.ai;

import com.sclera.applicationplane.procedure.domain.QuestionType;

import java.util.List;

/**
 * One candidate question in its reviewable, key-less form — what
 * {@code generated_question.question_json} stores and what the review UI
 * renders. Deliberately NOT a {@code DefinitionDocument.Item}:
 *
 * <ul>
 *   <li>No keys anywhere. Keys are minted from the template's counter at
 *       import, never before, and never by the LLM.</li>
 *   <li>A follow-up names its triggering option by position
 *       ({@code whenOptionIndex}) because the option it points at has no key
 *       yet either. Import resolves the index to the freshly minted key.</li>
 *   <li>Follow-ups are flat (no {@code followUps} of their own) — one level of
 *       nesting is the agreed ceiling for generated questions, and the shape
 *       enforcing it is also what keeps the LLM's output schema non-recursive.</li>
 *   <li>No {@code workOrder}/{@code alertProfile}: author decisions, so the
 *       shape cannot even express them.</li>
 * </ul>
 */
public record CandidateQuestion(
        String text,
        String help,
        QuestionType type,
        boolean required,
        List<CandidateOption> options,
        String unit,
        Integer min,
        Integer max,
        List<CandidateBand> rules,
        String standard,
        List<CandidateFollowUp> followUps) {

    public CandidateQuestion {
        options = options == null ? List.of() : List.copyOf(options);
        rules = rules == null ? List.of() : List.copyOf(rules);
        followUps = followUps == null ? List.of() : List.copyOf(followUps);
    }

    public record CandidateOption(String label, String result) {
    }

    public record CandidateBand(Integer min, Integer max, String result) {
    }

    /** A one-level follow-up; shown when the parent option at {@code whenOptionIndex} is chosen. */
    public record CandidateFollowUp(
            int whenOptionIndex,
            String text,
            String help,
            QuestionType type,
            boolean required,
            List<CandidateOption> options,
            String unit,
            Integer min,
            Integer max,
            List<CandidateBand> rules,
            String standard) {

        public CandidateFollowUp {
            options = options == null ? List.of() : List.copyOf(options);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }
}
