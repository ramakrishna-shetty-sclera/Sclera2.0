package com.sclera.applicationplane.procedure.dto;

import com.sclera.applicationplane.procedure.ai.CandidateQuestion;
import com.sclera.applicationplane.procedure.ai.DedupService.SimilarQuestion;
import com.sclera.applicationplane.procedure.ai.LlmSchemas.TopicBatch;
import com.sclera.applicationplane.procedure.ai.LlmSchemas.Topic;
import com.sclera.applicationplane.procedure.domain.AiDocumentStatus;
import com.sclera.applicationplane.procedure.domain.ChatSessionStatus;
import com.sclera.applicationplane.procedure.domain.GeneratedQuestionSource;
import com.sclera.applicationplane.procedure.domain.GeneratedQuestionStatus;
import com.sclera.applicationplane.procedure.domain.GenerationJobStatus;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for AI-assisted question generation. */
public final class AiGenerationDtos {

    private AiGenerationDtos() {
    }

    // --- requests -----------------------------------------------------------

    /**
     * {@code model} optionally overrides sclera.ai.model, within the allow-list.
     * {@code topics} narrows generation to the topics the author selected from
     * the document's extracted topic list; empty or null means the whole document.
     */
    public record GenerateFromDocumentRequest(
            @NotNull UUID documentId,
            @Size(max = 2000) String instructions,
            @Size(max = 100) String model,
            @Size(max = 50) List<Topic> topics) {

        public GenerateFromDocumentRequest(UUID documentId, String instructions, String model) {
            this(documentId, instructions, model, null);
        }
    }

    public record ChatMessageRequest(
            @NotBlank @Size(max = 4000) String message,
            @Size(max = 100) String model) {}

    public record ImportQuestionsRequest(@NotEmpty List<UUID> candidateIds) {}

    // --- responses ----------------------------------------------------------

    /** {@code topics} is present only when {@code topicsStatus} is READY. */
    public record AiDocumentResponse(
            UUID id,
            String filename,
            String mediaType,
            long sizeBytes,
            AiDocumentStatus status,
            String error,
            AiDocumentStatus topicsStatus,
            TopicBatch topics,
            String topicsError,
            OffsetDateTime createdAt) {}

    /**
     * One candidate question awaiting review — the per-question slice of a
     * GeneratedQuestionSet. {@code question} is the key-less candidate shape;
     * {@code similarExisting} is set when the organization already has a
     * question like it.
     */
    public record QuestionDraftResponse(
            UUID id,
            GeneratedQuestionSource source,
            UUID sourceRef,
            String sourceSpan,
            Integer turnNo,
            CandidateQuestion question,
            Double confidence,
            SimilarQuestion similarExisting,
            GeneratedQuestionStatus status,
            String importedKey,
            OffsetDateTime importedAt,
            OffsetDateTime createdAt) {}

    /** Candidates are present once the job is COMPLETED; empty before. */
    public record GenerationJobResponse(
            UUID id,
            UUID templateId,
            UUID documentId,
            GenerationJobStatus status,
            String error,
            String model,
            long inputTokens,
            long outputTokens,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt,
            List<QuestionDraftResponse> candidates) {}

    public record ChatSessionResponse(
            UUID id,
            UUID templateId,
            ChatSessionStatus status,
            OffsetDateTime createdAt) {}

    public record ChatTurnResponse(
            UUID sessionId,
            String reply,
            List<QuestionDraftResponse> candidates) {}

    /**
     * {@code imported} carries the question key each candidate was given in
     * the draft. {@code alreadyImported} lists candidates skipped because an
     * earlier import already placed them — re-importing is a no-op, which is
     * what makes retrying a failed request safe.
     */
    public record ImportQuestionsResponse(
            List<ImportedQuestion> imported,
            List<UUID> alreadyImported,
            VersionResponse draft) {}

    public record ImportedQuestion(UUID candidateId, String key) {}
}
