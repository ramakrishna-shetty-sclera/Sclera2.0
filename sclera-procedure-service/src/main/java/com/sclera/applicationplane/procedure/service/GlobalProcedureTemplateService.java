package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer;
import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer.Canonical;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionValidator;
import com.sclera.applicationplane.procedure.definition.KeyMinter;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.LinkState;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.CreateGlobalTemplateRequest;
import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.GlobalPublishResponse;
import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.GlobalTemplateResponse;
import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.GlobalVersionResponse;
import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.SaveGlobalDraftRequest;
import com.sclera.applicationplane.procedure.event.GlobalTemplateEvent;
import com.sclera.applicationplane.procedure.event.GlobalTemplateEventPublisher;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateVersionRepository;
import com.sclera.applicationplane.procedure.repository.GlobalTemplateOrgCopyRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ConflictException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Authoring Sclera's own shared library. Platform-admin only — there is no
 * organization to scope a check against, so the controller guards with
 * {@code @fga.isPlatformAdmin()} rather than an object or org relation.
 *
 * <p>Deliberately minimal (decision 3): create, save a draft, publish. No
 * update, archive, clone or version history — this is a small, infrequent
 * action for Sclera staff, not a second full authoring surface; feature 12
 * is what makes this content genuinely browsable. No optimistic lock on the
 * draft either — see {@link SaveGlobalDraftRequest}'s javadoc for why that
 * is a deliberate omission, not an oversight.
 */
@Service
@Transactional
public class GlobalProcedureTemplateService {

    private final GlobalProcedureTemplateRepository templates;
    private final GlobalProcedureTemplateVersionRepository versions;
    private final DefinitionCanonicalizer canonicalizer;
    private final GlobalTemplateEventPublisher events;
    private final GlobalTemplateOrgCopyRepository orgCopies;

    public GlobalProcedureTemplateService(GlobalProcedureTemplateRepository templates,
                                          GlobalProcedureTemplateVersionRepository versions,
                                          DefinitionCanonicalizer canonicalizer,
                                          GlobalTemplateEventPublisher events,
                                          GlobalTemplateOrgCopyRepository orgCopies) {
        this.templates = templates;
        this.versions = versions;
        this.canonicalizer = canonicalizer;
        this.events = events;
        this.orgCopies = orgCopies;
    }

    /** Creates the global template and its first draft, v1. */
    public GlobalTemplateResponse create(CreateGlobalTemplateRequest request) {
        GlobalProcedureTemplate template = new GlobalProcedureTemplate();
        template.setName(request.name().strip());
        template.setDescription(blankToNull(request.description()));
        template.setConsumerKey(blankToNull(request.consumerKey()));
        template.setCreatedBy(OrgContext.getUserId());
        templates.saveAndFlush(template);

        DefinitionDocument document = request.definition() == null ? DefinitionDocument.empty() : request.definition();
        Canonical canonical = assignKeysAndCanonicalize(template, document);
        versions.saveAndFlush(newVersion(template, 1, canonical.json(), canonical.hash()));

        return toResponse(template);
    }

    @Transactional(readOnly = true)
    public GlobalVersionResponse getDraft(UUID id) {
        return toResponse(requireDraft(getOwned(id)));
    }

    /**
     * Starts a new draft from the current published version — the minimal
     * surface's only way to produce a second version. There is no
     * version-history browsing to pick an earlier one from (decision 3), so
     * unlike {@code ProcedureTemplateService.createDraft}, this takes no
     * {@code fromVersionNo}: the current published version is the only
     * starting point there is.
     */
    public GlobalVersionResponse createDraft(UUID id) {
        GlobalProcedureTemplate template = lockOwned(id);
        versions.findByGlobalTemplateIdAndState(template.getId(), VersionState.DRAFT).ifPresent(existing -> {
            throw new ConflictException("This global template already has a draft (v" + existing.getVersionNo()
                    + "); publish it first");
        });
        // There is no discard in this minimal surface, so the only way to
        // reach here with no draft is to have already published once —
        // currentPublishedVersionId is therefore always set by this point.
        GlobalProcedureTemplateVersion current = versions.findById(template.getCurrentPublishedVersionId())
                .orElseThrow();

        int versionNo = versions.maxVersionNo(template.getId()) + 1;
        GlobalProcedureTemplateVersion draft = newVersion(template, versionNo,
                current.getDefinitionJson(), current.getDefinitionHash());
        versions.saveAndFlush(draft);
        return toResponse(draft);
    }

    /** Replaces the draft's whole document. Keys the client sends back are kept; new items get one minted. */
    public GlobalVersionResponse saveDraft(UUID id, SaveGlobalDraftRequest request) {
        GlobalProcedureTemplate template = lockOwned(id);
        GlobalProcedureTemplateVersion draft = requireDraft(template);
        Canonical canonical = assignKeysAndCanonicalize(template, request.definition());
        draft.setDefinitionJson(canonical.json());
        draft.setDefinitionHash(canonical.hash());
        draft.setChangeNote(blankToNull(request.changeNote()));
        versions.saveAndFlush(draft);
        return toResponse(draft);
    }

    /**
     * Freezes the draft as the current version and fires the event a future
     * notification service will someday react to. Runs structure validation
     * only — {@link DefinitionValidator#publishBlockers} needs an
     * organization's own active result-type keys to check an option's result
     * mapping against, and a global template has no organization at all;
     * calling it with an empty set would flag every legitimate result
     * mapping as unmapped. The one readiness check that does not depend on
     * any organization's vocabulary — at least one question — is kept
     * directly instead.
     *
     * <p>If the draft's content matches a version already published, nothing
     * new is created: the draft is discarded and that version becomes
     * current, the same no-op-republish behaviour every other publish path
     * in this service has — and the event still fires if that repoints
     * current, since linked organizations' "update available" comparison
     * depends on which version is current, not on a new row existing.
     *
     * <p>{@code linkedOrgCount} is read fresh on every call, never cached or
     * stored: it is the only visible confirmation that the broadcast reached
     * anyone, since the event itself carries no recipient.
     */
    public GlobalPublishResponse publish(UUID id) {
        GlobalProcedureTemplate template = lockOwned(id);
        GlobalProcedureTemplateVersion draft = requireDraft(template);
        if (!DefinitionCanonicalizer.sha256Hex(draft.getDefinitionJson()).equals(draft.getDefinitionHash())) {
            throw new IllegalStateException("Draft " + draft.getId() + " has a hash that does not match its content");
        }
        DefinitionDocument document = canonicalizer.parse(draft.getDefinitionJson());
        if (document.questionCount() == 0) {
            throw new BusinessRuleException(
                    "This global template cannot be published yet: add at least one question");
        }

        Optional<GlobalProcedureTemplateVersion> sameContent = versions.findByGlobalTemplateIdAndStateAndDefinitionHash(
                template.getId(), VersionState.PUBLISHED, draft.getDefinitionHash());
        if (sameContent.isPresent()) {
            GlobalProcedureTemplateVersion existing = sameContent.get();
            versions.delete(draft);
            if (!existing.getId().equals(template.getCurrentPublishedVersionId())) {
                template.setCurrentPublishedVersionId(existing.getId());
                templates.flush();
                events.publish(existing, GlobalTemplateEvent.EventType.PUBLISHED);
            }
            return new GlobalPublishResponse(false, toResponse(existing), linkedOrgCount(template.getId()));
        }

        draft.setState(VersionState.PUBLISHED);
        draft.setPublishedBy(OrgContext.getUserId());
        draft.setPublishedAt(OffsetDateTime.now());
        versions.saveAndFlush(draft);
        template.setCurrentPublishedVersionId(draft.getId());
        templates.flush();
        events.publish(draft, GlobalTemplateEvent.EventType.PUBLISHED);
        return new GlobalPublishResponse(true, toResponse(draft), linkedOrgCount(template.getId()));
    }

    private long linkedOrgCount(UUID globalTemplateId) {
        return orgCopies.countByGlobalTemplateIdAndLinkState(globalTemplateId, LinkState.LINKED);
    }

    // --- helpers --------------------------------------------------------------

    private Canonical assignKeysAndCanonicalize(GlobalProcedureTemplate template, DefinitionDocument document) {
        DefinitionDocument keyed = KeyMinter.assignKeys(document, template.getKeySeq(), template::nextKeyNumber);
        DefinitionValidator.validateStructure(keyed);
        return canonicalizer.canonicalize(keyed);
    }

    private GlobalProcedureTemplateVersion newVersion(GlobalProcedureTemplate template, int versionNo, String json, String hash) {
        GlobalProcedureTemplateVersion version = new GlobalProcedureTemplateVersion();
        version.setGlobalTemplateId(template.getId());
        version.setVersionNo(versionNo);
        version.setDefinitionJson(json);
        version.setDefinitionHash(hash);
        return version;
    }

    private GlobalProcedureTemplate getOwned(UUID id) {
        return templates.findById(id).orElseThrow(() -> notFound(id));
    }

    private GlobalProcedureTemplate lockOwned(UUID id) {
        return templates.lockById(id).orElseThrow(() -> notFound(id));
    }

    private static ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException("Global procedure template not found: " + id);
    }

    private GlobalProcedureTemplateVersion requireDraft(GlobalProcedureTemplate template) {
        return versions.findByGlobalTemplateIdAndState(template.getId(), VersionState.DRAFT)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Global template " + template.getId() + " has no draft"));
    }

    private GlobalTemplateResponse toResponse(GlobalProcedureTemplate template) {
        Optional<GlobalProcedureTemplateVersion> current = Optional.ofNullable(template.getCurrentPublishedVersionId())
                .flatMap(versions::findById);
        Optional<GlobalProcedureTemplateVersion> draft = versions.findByGlobalTemplateIdAndState(
                template.getId(), VersionState.DRAFT);
        return new GlobalTemplateResponse(
                template.getId(),
                template.getName(),
                template.getDescription(),
                template.getConsumerKey(),
                template.getStatus(),
                template.getCurrentPublishedVersionId(),
                current.map(GlobalProcedureTemplateVersion::getVersionNo).orElse(null),
                draft.map(GlobalProcedureTemplateVersion::getVersionNo).orElse(null),
                template.getCreatedBy(),
                template.getCreatedAt(),
                template.getUpdatedAt());
    }

    private GlobalVersionResponse toResponse(GlobalProcedureTemplateVersion version) {
        return new GlobalVersionResponse(
                version.getId(),
                version.getGlobalTemplateId(),
                version.getVersionNo(),
                version.getState(),
                version.getDefinitionHash(),
                version.getChangeNote(),
                version.getPublishedBy(),
                version.getPublishedAt(),
                canonicalizer.parse(version.getDefinitionJson()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
