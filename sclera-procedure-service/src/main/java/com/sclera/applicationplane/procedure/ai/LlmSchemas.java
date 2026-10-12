package com.sclera.applicationplane.procedure.ai;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.sclera.applicationplane.procedure.ai.CandidateQuestion.CandidateBand;
import com.sclera.applicationplane.procedure.ai.CandidateQuestion.CandidateFollowUp;
import com.sclera.applicationplane.procedure.ai.CandidateQuestion.CandidateOption;
import com.sclera.applicationplane.procedure.domain.QuestionType;

import java.util.List;

/**
 * The structured-output shapes the LLM must return. These are the API contract
 * with the model: the Anthropic SDK derives a JSON schema from them and the
 * response is constrained to it, so parsing cannot fail — only semantic
 * validation (CandidateMapper) can still reject an item.
 *
 * Deliberately non-recursive (structured outputs do not support recursive
 * schemas): a follow-up has no follow-ups, which is also the agreed one-level
 * nesting limit.
 */
public final class LlmSchemas {

    private LlmSchemas() {
    }

    @JsonClassDescription("A batch of generated checklist questions")
    public record GeneratedBatch(
            @JsonPropertyDescription("The generated questions, in the order they should appear")
            List<LlmQuestion> questions) {

        public GeneratedBatch {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    public record LlmQuestion(
            @JsonPropertyDescription("The question text as an inspector reads it (max 1000 chars)")
            String text,
            @JsonPropertyDescription("Optional guidance shown under the question (max 1000 chars)")
            String help,
            QuestionType type,
            @JsonPropertyDescription("Whether the inspector must answer before submitting")
            boolean required,
            @JsonPropertyDescription("Options for choice-type questions only; empty otherwise")
            List<LlmOption> options,
            @JsonPropertyDescription("INTEGER only: unit shown beside the field, e.g. 'psi' (max 20 chars)")
            String unit,
            @JsonPropertyDescription("INTEGER only: inclusive lower bound of what may be typed")
            Integer min,
            @JsonPropertyDescription("INTEGER only: inclusive upper bound of what may be typed")
            Integer max,
            @JsonPropertyDescription("INTEGER only: bands mapping reading ranges to a result key; leave result null")
            List<LlmBand> rules,
            @JsonPropertyDescription("The standard this came from, e.g. 'NFPA 10' (max 50 chars); null unless the source names one")
            String standard,
            @JsonPropertyDescription("Follow-up questions shown when a particular option is chosen; one level only")
            List<LlmFollowUp> followUps,
            @JsonPropertyDescription("Confidence 0.0-1.0 that this question is a correct reading of the source")
            Double confidence,
            @JsonPropertyDescription("The source phrase/sentence this came from, or the user ask it answers (max 500 chars)")
            String sourceSpan) {

        public LlmQuestion {
            options = options == null ? List.of() : List.copyOf(options);
            rules = rules == null ? List.of() : List.copyOf(rules);
            followUps = followUps == null ? List.of() : List.copyOf(followUps);
        }

        public CandidateQuestion toCandidate() {
            return new CandidateQuestion(text, help, type, required,
                    options.stream().map(LlmOption::toCandidate).toList(),
                    unit, min, max,
                    rules.stream().map(LlmBand::toCandidate).toList(),
                    standard,
                    followUps.stream().map(LlmFollowUp::toCandidate).toList());
        }
    }

    public record LlmOption(
            @JsonPropertyDescription("The answer label, e.g. 'Yes' (max 200 chars)")
            String label,
            @JsonPropertyDescription("Result-type key from the organization's vocabulary, or null for an answer that decides nothing")
            String result) {

        CandidateOption toCandidate() {
            return new CandidateOption(label, result);
        }
    }

    public record LlmBand(
            @JsonPropertyDescription("Inclusive lower bound; null means unbounded below")
            Integer min,
            @JsonPropertyDescription("Inclusive upper bound; null means unbounded above")
            Integer max,
            @JsonPropertyDescription("Leave null; the author maps bands to results during review")
            String result) {

        CandidateBand toCandidate() {
            return new CandidateBand(min, max, result);
        }
    }

    public record LlmFollowUp(
            @JsonPropertyDescription("Zero-based index of the parent option that shows this follow-up")
            int whenOptionIndex,
            String text,
            String help,
            QuestionType type,
            boolean required,
            List<LlmOption> options,
            String unit,
            Integer min,
            Integer max,
            List<LlmBand> rules,
            String standard) {

        public LlmFollowUp {
            options = options == null ? List.of() : List.copyOf(options);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }

        CandidateFollowUp toCandidate() {
            return new CandidateFollowUp(whenOptionIndex, text, help, type, required,
                    options.stream().map(LlmOption::toCandidate).toList(),
                    unit, min, max,
                    rules.stream().map(LlmBand::toCandidate).toList(),
                    standard);
        }
    }

    @JsonClassDescription("One turn of the question-authoring conversation")
    public record ChatTurn(
            @JsonPropertyDescription("Conversational reply to the author; clarifying questions go here")
            String reply,
            @JsonPropertyDescription("New candidate questions to add; empty when none")
            List<LlmQuestion> newQuestions,
            @JsonPropertyDescription("Replacements for existing candidates the author asked to change")
            List<ChatRevision> revisions,
            @JsonPropertyDescription("Candidate numbers the author asked to drop")
            List<Integer> removeCandidateNumbers) {

        public ChatTurn {
            newQuestions = newQuestions == null ? List.of() : List.copyOf(newQuestions);
            revisions = revisions == null ? List.of() : List.copyOf(revisions);
            removeCandidateNumbers = removeCandidateNumbers == null ? List.of() : List.copyOf(removeCandidateNumbers);
        }
    }

    public record ChatRevision(
            @JsonPropertyDescription("The number of the candidate being replaced, as shown in the candidate list")
            int candidateNumber,
            LlmQuestion question) {
    }
}
