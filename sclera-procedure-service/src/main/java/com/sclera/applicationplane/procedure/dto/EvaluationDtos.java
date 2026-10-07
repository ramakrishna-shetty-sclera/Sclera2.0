package com.sclera.applicationplane.procedure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.evaluation.Verdict;
import com.sclera.applicationplane.procedure.evaluation.Verdict.Overall;
import com.sclera.applicationplane.procedure.evaluation.Verdict.QuestionVerdict;
import com.sclera.applicationplane.procedure.evaluation.Verdict.SectionVerdict;
import com.sclera.applicationplane.procedure.evaluation.Verdict.WorkOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response shapes for evaluating answers against a version. */
public final class EvaluationDtos {

    private EvaluationDtos() {
    }

    /**
     * Answers keyed by the document's stable question keys:
     *
     * <pre>{ "answers": { "q1": { "value": "o2" }, "q3": { "value": ["o7", "o8"] }, "q4": { "value": 140 } } }</pre>
     *
     * Missing or empty {@code answers} means nothing answered yet, which is a
     * valid thing to evaluate — it is where every inspection starts.
     */
    public record EvaluateRequest(Map<String, AnswerInput> answers) {

        /** Question key to bare value, the shape the evaluator reads. */
        public Map<String, Object> values() {
            Map<String, Object> values = new HashMap<>();
            if (answers != null) {
                answers.forEach((key, answer) -> values.put(key, answer == null ? null : answer.value()));
            }
            return values;
        }
    }

    /**
     * One answer. Only {@code value} is read; anything beside it — a comment,
     * a timestamp, the result an older client worked out — is ignored, so the
     * inspection service can send what it stores without reshaping it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnswerInput(Object value) {
    }

    /** Which version was evaluated — a draft is evaluated too, for the author's preview. */
    public record VersionRef(UUID templateId, int versionNo, VersionState state) {
    }

    /** The {@link Verdict}, with the version it was reached against. */
    public record EvaluationResponse(
            VersionRef version,
            Overall overall,
            List<SectionVerdict> sections,
            List<QuestionVerdict> questions,
            List<String> critical,
            List<WorkOrder> workOrders,
            List<String> unanswered,
            List<String> ignored) {

        public static EvaluationResponse of(VersionRef version, Verdict verdict) {
            return new EvaluationResponse(version, verdict.overall(), verdict.sections(), verdict.questions(),
                    verdict.critical(), verdict.workOrders(), verdict.unanswered(), verdict.ignored());
        }
    }
}
