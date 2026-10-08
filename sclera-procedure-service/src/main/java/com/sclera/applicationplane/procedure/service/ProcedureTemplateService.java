package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.authz.FgaAuthorizationService;
import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer;
import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer.Canonical;
import com.sclera.applicationplane.procedure.definition.DefinitionDiff;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionValidator;
import com.sclera.applicationplane.procedure.definition.KeyMinter;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.domain.VersionResultTypeRef;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CloneRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.DiffResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.NewDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.PublishRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.PublishResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.SaveDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.TemplateResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.UpdateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionSummary;
import com.sclera.applicationplane.procedure.dto.EvaluationDtos.EvaluateRequest;
import com.sclera.applicationplane.procedure.dto.EvaluationDtos.EvaluationResponse;
import com.sclera.applicationplane.procedure.dto.EvaluationDtos.VersionRef;
import com.sclera.applicationplane.procedure.evaluation.Answers;
import com.sclera.applicationplane.procedure.evaluation.Evaluator;
import com.sclera.applicationplane.procedure.evaluation.ResultRanks;
import com.sclera.applicationplane.procedure.evaluation.Verdict;
import com.sclera.applicationplane.procedure.event.ProcedureTemplateEvent;
import com.sclera.applicationplane.procedure.event.TemplateEventPublisher;
import com.sclera.applicationplane.procedure.mapper.ProcedureTemplateMapper;
import com.sclera.applicationplane.procedure.repository.ProcedureDocumentRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateVersionRepository;
import com.sclera.applicationplane.procedure.repository.ResultTypeRepository;
import com.sclera.applicationplane.procedure.repository.VersionResultTypeRefRepository;
import com.sclera.applicationplane.procedure.tenancy.PropertyContext;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ConflictException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Procedure templates and their versions.
 *
 * A template has at most one DRAFT, the only version that can change. Publish
 * freezes the draft and repoints the template's current version at it. Any
 * published version can seed a new draft. Question keys are minted from the
 * template's key_seq and survive every edit, so they identify the same
 * question across all versions.
 *
 * Every operation that mints keys or version numbers locks the template row
 * first, so concurrent requests serialise on it.
 */
@Service
@Transactional
public class ProcedureTemplateService {

    private static final String FGA_TYPE = "procedure_template";

    private final ProcedureTemplateRepository templates;
    private final ProcedureTemplateVersionRepository versions;
    private final DefinitionCanonicalizer canonicalizer;
    private final ProcedureTemplateMapper mapper;
    private final TemplateEventPublisher events;
    private final FgaAuthorizationService fga;
    private final ResultTypeRepository resultTypes;
    private final VersionResultTypeRefRepository resultTypeRefs;
    private final ProcedureDocumentRepository documents;

    public ProcedureTemplateService(ProcedureTemplateRepository templates,
                                    ProcedureTemplateVersionRepository versions,
                                    DefinitionCanonicalizer canonicalizer,
                                    ProcedureTemplateMapper mapper,
                                    TemplateEventPublisher events,
                                    FgaAuthorizationService fga,
                                    ResultTypeRepository resultTypes,
                                    VersionResultTypeRefRepository resultTypeRefs,
                                    ProcedureDocumentRepository documents) {
        this.templates = templates;
        this.versions = versions;
        this.canonicalizer = canonicalizer;
        this.mapper = mapper;
        this.events = events;
        this.fga = fga;
        this.resultTypes = resultTypes;
        this.resultTypeRefs = resultTypeRefs;
        this.documents = documents;
    }

    // --- template identity --------------------------------------------------

    /** Creates the template and its first draft, v1. */
    public TemplateResponse create(CreateTemplateRequest request) {
        UUID orgId = OrgContext.getOrgId();
        String name = request.name().strip();
        requireNameFree(orgId, name, null);

        ProcedureTemplate template = new ProcedureTemplate();
        template.setOrgId(orgId);
        // Authored inside a property, it belongs to that property; authored at
        // organization level, it belongs to the whole organization. Which one
        // the author meant is already answered by where they were standing, so
        // there is no flag to set and no way to get it wrong.
        //
        // Exactly one property is in scope when a request carries the header,
        // so a list of more than one means organization level and shared is the
        // right answer.
        List<UUID> scope = PropertyContext.current();
        template.setPropertyId(scope.size() == 1 ? scope.get(0) : null);
        template.setName(name);
        template.setDescription(blankToNull(request.description()));
        if (!isBlank(request.consumerKey())) {
            template.setConsumerKey(request.consumerKey().strip().toUpperCase(Locale.ROOT));
        }
        template.setCreatedBy(OrgContext.getUserId());
        templates.saveAndFlush(template);

        DefinitionDocument document = request.definition() == null ? DefinitionDocument.empty() : request.definition();
        Canonical canonical = assignKeysAndCanonicalize(template, document);
        versions.saveAndFlush(newVersion(template, 1, canonical.json(), canonical.hash()));

        // FGA tuples (org link + creator). A failed write throws, rolling back
        // the insert so no template exists that nobody can access.
        fga.grantCreated(FGA_TYPE, template.getId(), template.getCreatedBy(), null);
        return mapper.toResponse(template, null, 1);
    }

    /** Name and description are identity, not content, so they are not versioned. */
    public TemplateResponse update(UUID id, UpdateTemplateRequest request) {
        ProcedureTemplate template = getOwned(id);
        requireActive(template);
        String name = request.name().strip();
        if (!name.equalsIgnoreCase(template.getName())) {
            requireNameFree(template.getOrgId(), name, template.getId());
        }
        template.setName(name);
        template.setDescription(blankToNull(request.description()));
        templates.flush();
        return toResponse(template);
    }

    /** Archiving keeps every version readable; it only stops further edits and publishing. */
    public TemplateResponse archive(UUID id) {
        ProcedureTemplate template = getOwned(id);
        if (template.getStatus() == TemplateStatus.ARCHIVED) {
            return toResponse(template);
        }
        template.setStatus(TemplateStatus.ARCHIVED);
        templates.flush();
        events.publish(template, currentVersion(template).orElse(null), ProcedureTemplateEvent.EventType.ARCHIVED);
        return toResponse(template);
    }

    @Transactional(readOnly = true)
    public TemplateResponse get(UUID id) {
        return toResponse(getOwned(id));
    }

    @Transactional(readOnly = true)
    public Page<TemplateResponse> list(TemplateStatus status, Pageable pageable) {
        UUID orgId = OrgContext.getOrgId();
        Page<ProcedureTemplate> page = status == null
                ? templates.findAllByOrgId(orgId, pageable)
                : templates.findAllByOrgIdAndStatus(orgId, status, pageable);
        if (page.isEmpty()) {
            return page.map(t -> mapper.toResponse(t, null, null));
        }

        // Two queries for the whole page rather than two per template.
        List<UUID> ids = page.getContent().stream().map(ProcedureTemplate::getId).toList();
        Map<UUID, Integer> draftNos = versions.findAllByTemplateIdInAndState(ids, VersionState.DRAFT).stream()
                .collect(Collectors.toMap(ProcedureTemplateVersion::getTemplateId, ProcedureTemplateVersion::getVersionNo));
        List<UUID> currentIds = page.getContent().stream()
                .map(ProcedureTemplate::getCurrentPublishedVersionId).filter(Objects::nonNull).toList();
        Map<UUID, Integer> currentNos = versions.findAllById(currentIds).stream()
                .collect(Collectors.toMap(ProcedureTemplateVersion::getId, ProcedureTemplateVersion::getVersionNo));

        return page.map(t -> mapper.toResponse(t,
                t.getCurrentPublishedVersionId() == null ? null : currentNos.get(t.getCurrentPublishedVersionId()),
                draftNos.get(t.getId())));
    }

    /**
     * Copies one version of a template into a new, independent template as its
     * v1 draft. Keys carry over unchanged, and so does the key counter, so the
     * copy keeps minting numbers the source never used.
     */
    public TemplateResponse cloneTemplate(UUID id, CloneRequest request) {
        ProcedureTemplate source = getOwned(id);
        ProcedureTemplateVersion from = request.fromVersionNo() != null
                ? requireVersion(source, request.fromVersionNo())
                : currentVersion(source)
                        .or(() -> versions.findByTemplateIdAndState(source.getId(), VersionState.DRAFT))
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Procedure template " + id + " has no version to clone"));

        String name = request.name().strip();
        requireNameFree(source.getOrgId(), name, null);

        ProcedureTemplate copy = new ProcedureTemplate();
        copy.setOrgId(source.getOrgId());
        copy.setName(name);
        copy.setDescription(source.getDescription());
        copy.setConsumerKey(source.getConsumerKey());
        copy.setKeySeq(source.getKeySeq());
        copy.setCreatedBy(OrgContext.getUserId());
        templates.saveAndFlush(copy);

        ProcedureTemplateVersion draft = newVersion(copy, 1, from.getDefinitionJson(), from.getDefinitionHash());
        draft.setChangeNote("Cloned from '" + source.getName() + "' v" + from.getVersionNo());
        versions.saveAndFlush(draft);

        fga.grantCreated(FGA_TYPE, copy.getId(), copy.getCreatedBy(), null);
        return mapper.toResponse(copy, null, 1);
    }

    // --- the draft ----------------------------------------------------------

    @Transactional(readOnly = true)
    public VersionResponse getDraft(UUID id) {
        return mapper.toResponse(requireDraft(getOwned(id)));
    }

    /**
     * Replaces the draft's whole document. Keys the client sends back are
     * kept; items without a key are new and get one minted.
     */
    public VersionResponse saveDraft(UUID id, SaveDraftRequest request) {
        ProcedureTemplate template = lockOwned(id);
        requireActive(template);
        ProcedureTemplateVersion draft = requireDraft(template);
        requireRowVersion(draft, request.rowVersion());

        Canonical canonical = assignKeysAndCanonicalize(template, request.definition());
        draft.setDefinition(canonical.json(), canonical.hash());
        draft.setChangeNote(blankToNull(request.changeNote()));
        versions.saveAndFlush(draft);
        return mapper.toResponse(draft);
    }

    /** Starts a new draft from any earlier version — the way to edit after publishing. */
    public VersionResponse createDraft(UUID id, NewDraftRequest request) {
        ProcedureTemplate template = lockOwned(id);
        requireActive(template);
        versions.findByTemplateIdAndState(template.getId(), VersionState.DRAFT).ifPresent(existing -> {
            throw new ConflictException("This procedure already has a draft (v" + existing.getVersionNo()
                    + "); publish or discard it first");
        });
        ProcedureTemplateVersion source = requireVersion(template, request.fromVersionNo());

        int versionNo = versions.maxVersionNo(template.getId()) + 1;
        ProcedureTemplateVersion draft = newVersion(template, versionNo,
                source.getDefinitionJson(), source.getDefinitionHash());
        versions.saveAndFlush(draft);
        return mapper.toResponse(draft);
    }

    public void discardDraft(UUID id) {
        ProcedureTemplate template = lockOwned(id);
        ProcedureTemplateVersion draft = requireDraft(template);
        if (template.getCurrentPublishedVersionId() == null) {
            throw new BusinessRuleException("This procedure has never been published, so its draft is all "
                    + "it contains. Archive the procedure instead.");
        }
        versions.delete(draft);
    }

    /**
     * Freezes the draft as the current version. If its content matches a
     * version already published, nothing new is created: the draft is
     * discarded and that version becomes current (a rollback, or a no-op if it
     * already was current).
     */
    public PublishResponse publish(UUID id, PublishRequest request) {
        ProcedureTemplate template = lockOwned(id);
        requireActive(template);
        ProcedureTemplateVersion draft = requireDraft(template);
        if (request != null && request.rowVersion() != null) {
            requireRowVersion(draft, request.rowVersion());
        }
        if (!DefinitionCanonicalizer.sha256Hex(draft.getDefinitionJson()).equals(draft.getDefinitionHash())) {
            throw new IllegalStateException("Draft " + draft.getId() + " has a hash that does not match its content");
        }
        // Every reason at once. An author fixing a checklist wants the whole
        // list, not one refusal per attempt — and the screens that show it are
        // built for a list.
        DefinitionDocument document = canonicalizer.parse(draft.getDefinitionJson());
        List<String> blockers = DefinitionValidator.publishBlockers(
                document, activeResultKeys(template.getOrgId()), citableDocumentIds(template));
        if (!blockers.isEmpty()) {
            throw new BusinessRuleException("This procedure cannot be published yet: "
                    + String.join("; ", blockers));
        }

        Optional<ProcedureTemplateVersion> sameContent = versions.findByTemplateIdAndStateAndDefinitionHash(
                template.getId(), VersionState.PUBLISHED, draft.getDefinitionHash());
        if (sameContent.isPresent()) {
            ProcedureTemplateVersion existing = sameContent.get();
            versions.delete(draft);
            if (!existing.getId().equals(template.getCurrentPublishedVersionId())) {
                template.setCurrentPublishedVersionId(existing.getId());
                templates.flush();
                events.publish(template, existing, ProcedureTemplateEvent.EventType.PUBLISHED);
            }
            return new PublishResponse(false, mapper.toResponse(existing));
        }

        draft.markPublished(OrgContext.getUserId(), OffsetDateTime.now());
        versions.saveAndFlush(draft);
        // Only here, on a version frozen for the first time. The path above
        // republishes a version that already has its rows, and a draft never
        // gets any: until now nothing was committed to, so every result type
        // it names stays deletable.
        resultTypeRefs.saveAll(document.resultTypeKeys().stream()
                .map(key -> new VersionResultTypeRef(draft.getId(), key))
                .toList());
        template.setCurrentPublishedVersionId(draft.getId());
        templates.flush();
        events.publish(template, draft, ProcedureTemplateEvent.EventType.PUBLISHED);
        return new PublishResponse(true, mapper.toResponse(draft));
    }

    // --- history ------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<VersionSummary> listVersions(UUID id) {
        ProcedureTemplate template = getOwned(id);
        return versions.findAllByTemplateIdOrderByVersionNoDesc(template.getId()).stream()
                .map(mapper::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public VersionResponse getVersion(UUID id, int versionNo) {
        return mapper.toResponse(requireVersion(getOwned(id), versionNo));
    }

    /** Diff between any two versions of the same template, draft included. */
    @Transactional(readOnly = true)
    public DiffResponse diff(UUID id, int fromVersionNo, int toVersionNo) {
        ProcedureTemplate template = getOwned(id);
        DefinitionDocument from = canonicalizer.parse(requireVersion(template, fromVersionNo).getDefinitionJson());
        DefinitionDocument to = canonicalizer.parse(requireVersion(template, toVersionNo).getDefinitionJson());
        return new DiffResponse(fromVersionNo, toVersionNo, DefinitionDiff.between(from, to));
    }

    // --- evaluation ---------------------------------------------------------

    /**
     * What a set of answers means against one version — a draft as well as a
     * published one, because the author's running-score preview evaluates the
     * draft being written. A draft has not been through the publish checks, so
     * the evaluator degrades on it rather than failing: a score or a reading in
     * no band gives no result.
     *
     * <p>Reads only. The result-type ranks are loaded on every call and never
     * cached with the verdict: an admin reordering result types changes which
     * of two results is more severe.
     */
    @Transactional(readOnly = true)
    public EvaluationResponse evaluate(UUID id, int versionNo, EvaluateRequest request) {
        ProcedureTemplate template = getOwned(id);
        return evaluate(requireVersion(template, versionNo), template.getOrgId(), request);
    }

    /**
     * The inspection service's evaluation, over Dapr: a published version, by
     * its id, for an organization named in the call. The caller must already
     * be inside that organization's schema ({@code TenantContext.runAs}).
     *
     * <p><b>It reaches the version without reading {@code procedure_template}.</b>
     * There is no JWT, so no property header, so a call lands at organization
     * level, where row-level security on {@code procedure_template} hides every
     * property's procedures — a checklist on a property's procedure would be
     * refused. {@code procedure_template_version} has no row-level security, and
     * the schema already is the organization boundary, so the version is looked
     * up directly. Routing this through {@code getOwned} would bring the
     * property filter back.
     *
     * <p>A draft is refused: an inspection is only ever evaluated against the
     * version it pinned, and only a published version can be pinned. The public
     * endpoint allows drafts for the author's preview; that difference is the
     * point of having two.
     */
    @Transactional(readOnly = true)
    public EvaluationResponse evaluatePublished(UUID versionId, UUID orgId, EvaluateRequest request) {
        ProcedureTemplateVersion version = versions.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("Procedure version not found: " + versionId));
        if (version.getState() != VersionState.PUBLISHED) {
            throw new BusinessRuleException("Procedure version " + versionId + " is a draft; "
                    + "only a published version can be evaluated for an inspection");
        }
        return evaluate(version, orgId, request);
    }

    private EvaluationResponse evaluate(ProcedureTemplateVersion version, UUID orgId, EvaluateRequest request) {
        Verdict verdict = Evaluator.evaluate(
                canonicalizer.parse(version.getDefinitionJson()),
                Answers.of(request == null ? Map.of() : request.values()),
                resultRanks(orgId));
        return EvaluationResponse.of(
                new VersionRef(version.getTemplateId(), version.getVersionNo(), version.getState()), verdict);
    }

    /**
     * Every result type the organization has, <em>inactive ones included</em>
     * — the sibling of {@link #activeResultKeys}, and deliberately not the
     * same query. A published version may name a type deactivated since; it
     * must keep evaluating the way it did, which is the reason the delete guard
     * points admins at deactivate in the first place.
     */
    private ResultRanks resultRanks(UUID orgId) {
        return ResultRanks.of(resultTypes.findAllByOrgIdOrderBySeverityOrderAsc(orgId));
    }

    // --- helpers ------------------------------------------------------------

    /**
     * The result-type keys an author may map an answer to. Inactive ones are
     * left out: deactivating a result type is how an organization retires it,
     * and a new version should not start using it again.
     */
    /**
     * The documents a draft of this template may cite — the sibling of
     * {@link #activeResultKeys}, and needing the template's own property for
     * the same reason that one does not: an organization-wide procedure may
     * cite only organization-wide documents, whatever property the author
     * happens to be standing in while they edit it.
     */
    private Set<String> citableDocumentIds(ProcedureTemplate template) {
        return documents.findCitable(template.getOrgId(), template.getPropertyId()).stream()
                .map(d -> d.getId().toString())
                .collect(Collectors.toSet());
    }

    private Set<String> activeResultKeys(UUID orgId) {
        return resultTypes.findAllByOrgIdAndActiveOrderBySeverityOrderAsc(orgId, true).stream()
                .map(rt -> rt.getKey())
                .collect(Collectors.toSet());
    }

    /**
     * The one place a document becomes stored bytes, so the one place structure
     * is checked. Keys are minted first: the rules talk about which option a
     * follow-up points at, and a new option has no key until this has run.
     */
    private Canonical assignKeysAndCanonicalize(ProcedureTemplate template, DefinitionDocument document) {
        DefinitionDocument keyed = KeyMinter.assignKeys(document, template.getKeySeq(), template::nextKeyNumber);
        DefinitionValidator.validateStructure(keyed);
        return canonicalizer.canonicalize(keyed);
    }

    private ProcedureTemplateVersion newVersion(ProcedureTemplate template, int versionNo, String json, String hash) {
        ProcedureTemplateVersion version = new ProcedureTemplateVersion();
        version.setTemplateId(template.getId());
        version.setVersionNo(versionNo);
        version.setDefinition(json, hash);
        version.setCreatedBy(OrgContext.getUserId());
        return version;
    }

    private TemplateResponse toResponse(ProcedureTemplate template) {
        Integer currentNo = currentVersion(template).map(ProcedureTemplateVersion::getVersionNo).orElse(null);
        Integer draftNo = versions.findByTemplateIdAndState(template.getId(), VersionState.DRAFT)
                .map(ProcedureTemplateVersion::getVersionNo).orElse(null);
        return mapper.toResponse(template, currentNo, draftNo);
    }

    private Optional<ProcedureTemplateVersion> currentVersion(ProcedureTemplate template) {
        UUID currentId = template.getCurrentPublishedVersionId();
        return currentId == null ? Optional.empty() : versions.findById(currentId);
    }

    private ProcedureTemplate getOwned(UUID id) {
        return templates.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> notFound(id));
    }

    private ProcedureTemplate lockOwned(UUID id) {
        return templates.lockByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> notFound(id));
    }

    private static ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException("Procedure template not found: " + id);
    }

    private ProcedureTemplateVersion requireDraft(ProcedureTemplate template) {
        return versions.findByTemplateIdAndState(template.getId(), VersionState.DRAFT)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Procedure template " + template.getId() + " has no draft; create one from a version"));
    }

    private ProcedureTemplateVersion requireVersion(ProcedureTemplate template, int versionNo) {
        return versions.findByTemplateIdAndVersionNo(template.getId(), versionNo)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Procedure template " + template.getId() + " has no version " + versionNo));
    }

    private static void requireActive(ProcedureTemplate template) {
        if (template.getStatus() == TemplateStatus.ARCHIVED) {
            throw new BusinessRuleException("Procedure '" + template.getName() + "' is archived and cannot be changed");
        }
    }

    private static void requireRowVersion(ProcedureTemplateVersion draft, long expected) {
        if (draft.getRowVersion() != expected) {
            throw new ConflictException("The draft was saved by someone else since you loaded it (rowVersion is now "
                    + draft.getRowVersion() + ", you sent " + expected + "). Reload it and reapply your changes.");
        }
    }

    private void requireNameFree(UUID orgId, String name, UUID excludeId) {
        boolean taken = excludeId == null
                ? templates.existsByOrgIdAndNameIgnoreCaseAndStatus(orgId, name, TemplateStatus.ACTIVE)
                : templates.existsByOrgIdAndNameIgnoreCaseAndStatusAndIdNot(orgId, name, TemplateStatus.ACTIVE, excludeId);
        if (taken) {
            throw new ConflictException("A procedure named '" + name + "' already exists");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.strip();
    }
}
