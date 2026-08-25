package com.sclera.applicationplane.inspection.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclera.applicationplane.inspection.client.ProcedureTemplateClient;
import com.sclera.applicationplane.inspection.client.TemplateSnapshot;
import com.sclera.applicationplane.inspection.domain.Inspection;
import com.sclera.applicationplane.inspection.domain.InspectionAnswer;
import com.sclera.applicationplane.inspection.domain.InspectionStatus;
import com.sclera.applicationplane.inspection.dto.CreateInspectionRequest;
import com.sclera.applicationplane.inspection.dto.InspectionResponse;
import com.sclera.applicationplane.inspection.dto.SubmitAnswersRequest;
import com.sclera.applicationplane.inspection.event.InspectionEventPublisher;
import com.sclera.applicationplane.inspection.repository.InspectionRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.exception.ValidationException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class InspectionService {

    private final InspectionRepository repository;
    private final ProcedureTemplateClient templateClient;
    private final InspectionEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public InspectionService(InspectionRepository repository,
                             ProcedureTemplateClient templateClient,
                             InspectionEventPublisher eventPublisher,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.templateClient = templateClient;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    public InspectionResponse create(CreateInspectionRequest request) {
        UUID orgId = OrgContext.getOrgId();

        // Fetch the template from procedure-service (Dapr, HMAC-signed) and snapshot it
        TemplateSnapshot snapshot = templateClient.fetchTemplate(request.templateId(), orgId);
        if (!"PUBLISHED".equals(snapshot.status())) {
            throw new BusinessRuleException(
                    "Inspections can only be created from PUBLISHED templates (current: " + snapshot.status() + ")");
        }

        Inspection inspection = new Inspection();
        inspection.setOrgId(orgId);
        inspection.setCreatedBy(OrgContext.getUserId());
        inspection.setTemplateId(snapshot.id());
        inspection.setTemplateVersion(snapshot.version());
        inspection.setTemplateName(snapshot.name());
        inspection.setTemplateSnapshot(writeJson(snapshot));
        inspection.setAssigneeId(request.assigneeId());
        inspection.setScheduledFor(request.scheduledFor());
        inspection.setNotes(request.notes());
        return toResponse(repository.save(inspection));
    }

    public InspectionResponse start(UUID id) {
        Inspection inspection = getOwned(id);
        if (inspection.getStatus() != InspectionStatus.DRAFT) {
            throw new BusinessRuleException("Only DRAFT inspections can be started");
        }
        inspection.setStatus(InspectionStatus.IN_PROGRESS);
        inspection.setStartedAt(OffsetDateTime.now());
        return toResponse(inspection);
    }

    public InspectionResponse submitAnswers(UUID id, SubmitAnswersRequest request) {
        Inspection inspection = getOwned(id);
        if (inspection.getStatus() != InspectionStatus.IN_PROGRESS) {
            throw new BusinessRuleException("Answers can only be submitted while IN_PROGRESS");
        }

        Set<UUID> knownQuestionIds = questionIdsOf(inspection);
        inspection.replaceAnswers(request.answers().stream().map(item -> {
            if (!knownQuestionIds.contains(item.questionId())) {
                throw new ValidationException("Unknown question id: " + item.questionId());
            }
            InspectionAnswer answer = new InspectionAnswer();
            answer.setQuestionId(item.questionId());
            answer.setAnswerValue(item.value().toString());
            answer.setScore(item.score());
            answer.setComment(item.comment());
            return answer;
        }).toList());
        return toResponse(inspection);
    }

    public InspectionResponse complete(UUID id) {
        Inspection inspection = getOwned(id);
        if (inspection.getStatus() != InspectionStatus.IN_PROGRESS) {
            throw new BusinessRuleException("Only IN_PROGRESS inspections can be completed");
        }
        requireAllRequiredAnswered(inspection);
        inspection.setStatus(InspectionStatus.COMPLETED);
        inspection.setCompletedAt(OffsetDateTime.now());
        eventPublisher.publishCompleted(inspection);
        return toResponse(inspection);
    }

    public InspectionResponse cancel(UUID id) {
        Inspection inspection = getOwned(id);
        if (inspection.getStatus() == InspectionStatus.COMPLETED) {
            throw new BusinessRuleException("Completed inspections cannot be cancelled");
        }
        inspection.setStatus(InspectionStatus.CANCELLED);
        return toResponse(inspection);
    }

    @Transactional(readOnly = true)
    public InspectionResponse get(UUID id) {
        return toResponse(getOwned(id));
    }

    @Transactional(readOnly = true)
    public Page<InspectionResponse> list(InspectionStatus status, UUID templateId, Pageable pageable) {
        UUID orgId = OrgContext.getOrgId();
        Page<Inspection> page;
        if (templateId != null) {
            page = repository.findAllByOrgIdAndTemplateId(orgId, templateId, pageable);
        } else if (status != null) {
            page = repository.findAllByOrgIdAndStatus(orgId, status, pageable);
        } else {
            page = repository.findAllByOrgId(orgId, pageable);
        }
        return page.map(this::toResponse);
    }

    private Inspection getOwned(UUID id) {
        return repository.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found: " + id));
    }

    private void requireAllRequiredAnswered(Inspection inspection) {
        Set<UUID> answered = new HashSet<>();
        inspection.getAnswers().forEach(a -> answered.add(a.getQuestionId()));

        TemplateSnapshot snapshot = readSnapshot(inspection);
        for (TemplateSnapshot.SectionSnapshot section : snapshot.sections()) {
            for (TemplateSnapshot.QuestionSnapshot question : section.questions()) {
                if (question.required() && !answered.contains(question.id())) {
                    throw new BusinessRuleException(
                            "Required question not answered: '" + question.text() + "'");
                }
            }
        }
    }

    private Set<UUID> questionIdsOf(Inspection inspection) {
        Set<UUID> ids = new HashSet<>();
        TemplateSnapshot snapshot = readSnapshot(inspection);
        snapshot.sections().forEach(s -> s.questions().forEach(q -> ids.add(q.id())));
        return ids;
    }

    private InspectionResponse toResponse(Inspection inspection) {
        return new InspectionResponse(
                inspection.getId(),
                inspection.getOrgId(),
                inspection.getTemplateId(),
                inspection.getTemplateVersion(),
                inspection.getTemplateName(),
                readTree(inspection.getTemplateSnapshot()),
                inspection.getStatus(),
                inspection.getAssigneeId(),
                inspection.getScheduledFor(),
                inspection.getStartedAt(),
                inspection.getCompletedAt(),
                inspection.getNotes(),
                inspection.getCreatedBy(),
                inspection.getCreatedAt(),
                inspection.getUpdatedAt(),
                inspection.getAnswers().stream()
                        .map(a -> new InspectionResponse.AnswerResponse(
                                a.getId(),
                                a.getQuestionId(),
                                readTree(a.getAnswerValue()),
                                a.getScore(),
                                a.getComment()))
                        .toList());
    }

    private TemplateSnapshot readSnapshot(Inspection inspection) {
        try {
            return objectMapper.readValue(inspection.getTemplateSnapshot(), TemplateSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt template snapshot for inspection " + inspection.getId(), e);
        }
    }

    private JsonNode readTree(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt JSON column", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize template snapshot", e);
        }
    }
}
