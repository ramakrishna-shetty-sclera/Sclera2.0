package com.sclera.applicationplane.procedure.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclera.applicationplane.procedure.ai.CandidateQuestion;
import com.sclera.applicationplane.procedure.ai.DedupService.SimilarQuestion;
import com.sclera.applicationplane.procedure.domain.AiDocument;
import com.sclera.applicationplane.procedure.domain.ChatSession;
import com.sclera.applicationplane.procedure.domain.GeneratedQuestion;
import com.sclera.applicationplane.procedure.domain.GenerationJob;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.AiDocumentResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ChatSessionResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.GenerationJobResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.QuestionDraftResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Entity → response mapping for the AI feature, including the JSON columns:
 * question_json ↔ {@link CandidateQuestion} and similar_existing ↔
 * {@link SimilarQuestion} are (de)serialised here and nowhere else.
 */
@Component
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
public class AiMapper {

    private final ObjectMapper objectMapper;

    public AiMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AiDocumentResponse toResponse(AiDocument document) {
        return new AiDocumentResponse(document.getId(), document.getFilename(), document.getMediaType(),
                document.getSizeBytes(), document.getStatus(), document.getError(), document.getCreatedAt());
    }

    public ChatSessionResponse toResponse(ChatSession session) {
        return new ChatSessionResponse(session.getId(), session.getTemplateId(),
                session.getStatus(), session.getCreatedAt());
    }

    public GenerationJobResponse toResponse(GenerationJob job, List<GeneratedQuestion> candidates) {
        return new GenerationJobResponse(job.getId(), job.getTemplateId(), job.getDocumentId(),
                job.getStatus(), job.getError(), job.getModel(),
                job.getInputTokens(), job.getOutputTokens(),
                job.getCreatedAt(), job.getCompletedAt(),
                candidates.stream().map(this::toResponse).toList());
    }

    public QuestionDraftResponse toResponse(GeneratedQuestion candidate) {
        return new QuestionDraftResponse(candidate.getId(), candidate.getSource(), candidate.getSourceRef(),
                candidate.getSourceSpan(), candidate.getTurnNo(),
                readQuestion(candidate.getQuestionJson()),
                candidate.getConfidence(),
                readSimilar(candidate.getSimilarExisting()),
                candidate.getStatus(), candidate.getImportedKey(), candidate.getImportedAt(),
                candidate.getCreatedAt());
    }

    public CandidateQuestion readQuestion(String json) {
        try {
            return objectMapper.readValue(json, CandidateQuestion.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored candidate question is not readable", e);
        }
    }

    public String writeQuestion(CandidateQuestion question) {
        try {
            return objectMapper.writeValueAsString(question);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Candidate question is not serialisable", e);
        }
    }

    public SimilarQuestion readSimilar(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SimilarQuestion.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored similar-question reference is not readable", e);
        }
    }

    public String writeSimilar(SimilarQuestion similar) {
        if (similar == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(similar);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Similar-question reference is not serialisable", e);
        }
    }
}
