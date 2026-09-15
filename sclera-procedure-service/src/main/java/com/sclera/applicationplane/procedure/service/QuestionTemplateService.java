package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.authz.FgaAuthorizationService;
import com.sclera.applicationplane.procedure.domain.QuestionTemplate;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.dto.QuestionTemplateRequest;
import com.sclera.applicationplane.procedure.dto.QuestionTemplateResponse;
import com.sclera.applicationplane.procedure.event.QuestionTemplateEvent;
import com.sclera.applicationplane.procedure.event.TemplateEventPublisher;
import com.sclera.applicationplane.procedure.mapper.QuestionTemplateMapper;
import com.sclera.applicationplane.procedure.repository.QuestionTemplateRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ConflictException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class QuestionTemplateService {

    private final QuestionTemplateRepository repository;
    private final QuestionTemplateMapper mapper;
    private final TemplateEventPublisher eventPublisher;
    private final FgaAuthorizationService fga;

    public QuestionTemplateService(QuestionTemplateRepository repository,
                                   QuestionTemplateMapper mapper,
                                   TemplateEventPublisher eventPublisher,
                                   FgaAuthorizationService fga) {
        this.repository = repository;
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
        this.fga = fga;
    }

    public QuestionTemplateResponse create(QuestionTemplateRequest request) {
        UUID orgId = OrgContext.getOrgId();
        if (repository.existsByOrgIdAndNameIgnoreCaseAndStatusNot(orgId, request.name(), TemplateStatus.ARCHIVED)) {
            throw new ConflictException("A template named '" + request.name() + "' already exists");
        }
        QuestionTemplate template = new QuestionTemplate();
        template.setOrgId(orgId);
        template.setCreatedBy(OrgContext.getUserId());
        mapper.applyRequest(template, request);
        QuestionTemplate saved = repository.save(template);

        // FGA tuples (org link + creator, one round trip). A failed write throws,
        // rolling back the insert so no template exists that nobody can access.
        fga.grantCreated("question_template", saved.getId(), saved.getCreatedBy(), null);
        return mapper.toResponse(saved);
    }

    public QuestionTemplateResponse update(UUID id, QuestionTemplateRequest request) {
        QuestionTemplate template = getOwned(id);
        if (template.getStatus() != TemplateStatus.DRAFT) {
            throw new BusinessRuleException("Only DRAFT templates can be edited; use publish to create a new version");
        }
        mapper.applyRequest(template, request);
        return mapper.toResponse(template);
    }

    public QuestionTemplateResponse publish(UUID id) {
        QuestionTemplate template = getOwned(id);
        if (template.getStatus() == TemplateStatus.ARCHIVED) {
            throw new BusinessRuleException("Archived templates cannot be published");
        }
        template.setStatus(TemplateStatus.PUBLISHED);
        template.setVersion(template.getVersion() + 1);
        eventPublisher.publish(template, QuestionTemplateEvent.EventType.PUBLISHED);
        return mapper.toResponse(template);
    }

    public QuestionTemplateResponse archive(UUID id) {
        QuestionTemplate template = getOwned(id);
        template.setStatus(TemplateStatus.ARCHIVED);
        eventPublisher.publish(template, QuestionTemplateEvent.EventType.ARCHIVED);
        return mapper.toResponse(template);
    }

    @Transactional(readOnly = true)
    public QuestionTemplateResponse get(UUID id) {
        return mapper.toResponse(getOwned(id));
    }

    @Transactional(readOnly = true)
    public Page<QuestionTemplateResponse> list(TemplateStatus status, Pageable pageable) {
        UUID orgId = OrgContext.getOrgId();
        Page<QuestionTemplate> page = status == null
                ? repository.findAllByOrgId(orgId, pageable)
                : repository.findAllByOrgIdAndStatus(orgId, status, pageable);
        return page.map(mapper::toResponse);
    }

    /**
     * Internal (service-to-service) lookup. The caller passes the org explicitly
     * because internal calls carry no user JWT — HMAC guards the transport, and
     * the org filter here preserves tenant isolation.
     */
    @Transactional(readOnly = true)
    public QuestionTemplateResponse getForOrg(UUID id, UUID orgId) {
        return repository.findByIdAndOrgId(id, orgId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Question template not found: " + id));
    }

    private QuestionTemplate getOwned(UUID id) {
        return repository.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Question template not found: " + id));
    }
}
