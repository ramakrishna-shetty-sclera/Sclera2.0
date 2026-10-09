package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.authz.FgaAuthorizationService;
import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer;
import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer.Canonical;
import com.sclera.applicationplane.procedure.definition.DefinitionDiff;
import com.sclera.applicationplane.procedure.client.vocabulary.CachedVocabulary;
import com.sclera.applicationplane.procedure.client.vocabulary.Vocabulary;
import com.sclera.applicationplane.procedure.client.vocabulary.VocabularyKind;
import com.sclera.applicationplane.procedure.client.vocabulary.VocabularyUnavailableException;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetKind;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetType;
import com.sclera.applicationplane.procedure.definition.DefinitionValidator;
import com.sclera.applicationplane.procedure.definition.KeyMinter;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.GlobalTemplateOrgCopy;
import com.sclera.applicationplane.procedure.domain.LinkState;
import com.sclera.applicationplane.procedure.domain.ProcedureConsumer;
import com.sclera.applicationplane.procedure.domain.ProcedureFavourite;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.ResultType;
import com.sclera.applicationplane.procedure.domain.TemplateScope;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.domain.VersionDocumentRef;
import com.sclera.applicationplane.procedure.domain.VersionResultTypeRef;
import com.sclera.applicationplane.procedure.domain.VersionTargetType;
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
import com.sclera.applicationplane.procedure.dto.GlobalLibraryDtos.ExportedProcedure;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryDtos.ExportedVersion;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryDtos.FavouriteResponse;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryDtos.ImportRequest;
import com.sclera.applicationplane.procedure.evaluation.Answers;
import com.sclera.applicationplane.procedure.evaluation.Evaluator;
import com.sclera.applicationplane.procedure.evaluation.ResultRanks;
import com.sclera.applicationplane.procedure.evaluation.Verdict;
import com.sclera.applicationplane.procedure.event.ProcedureTemplateEvent;
import com.sclera.applicationplane.procedure.event.TemplateEventPublisher;
import com.sclera.applicationplane.procedure.mapper.ProcedureTemplateMapper;
import com.sclera.applicationplane.procedure.repository.ProcedureConsumerRepository;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateVersionRepository;
import com.sclera.applicationplane.procedure.repository.GlobalTemplateOrgCopyRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureDocumentRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureFavouriteRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateVersionRepository;
import com.sclera.applicationplane.procedure.repository.ResultTypeRepository;
import com.sclera.applicationplane.procedure.repository.VersionDocumentRefRepository;
import com.sclera.applicationplane.procedure.repository.VersionResultTypeRefRepository;
import com.sclera.applicationplane.procedure.repository.VersionTargetTypeRepository;
import com.sclera.applicationplane.procedure.tenancy.PropertyContext;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ConflictException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.exception.ValidationException;
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
    private final VersionTargetTypeRepository targetTypeRefs;
    private final ProcedureConsumerRepository consumers;
    private final CachedVocabulary vocabulary;
    private final ProcedureDocumentRepository documents;
    private final VersionDocumentRefRepository documentRefs;
    private final GlobalProcedureTemplateRepository globalTemplates;
    private final GlobalProcedureTemplateVersionRepository globalVersions;
    private final GlobalTemplateOrgCopyRepository orgCopies;
    private final ProcedureFavouriteRepository favourites;

    public ProcedureTemplateService(ProcedureTemplateRepository templates,
                                    ProcedureTemplateVersionRepository versions,
                                    DefinitionCanonicalizer canonicalizer,
                                    ProcedureTemplateMapper mapper,
                                    TemplateEventPublisher events,
                                    FgaAuthorizationService fga,
                                    ResultTypeRepository resultTypes,
                                    VersionResultTypeRefRepository resultTypeRefs,
                                    VersionTargetTypeRepository targetTypeRefs,
                                    ProcedureConsumerRepository consumers,
                                    CachedVocabulary vocabulary,
                                    ProcedureDocumentRepository documents,
                                    VersionDocumentRefRepository documentRefs,
                                    GlobalProcedureTemplateRepository globalTemplates,
                                    GlobalProcedureTemplateVersionRepository globalVersions,
                                    GlobalTemplateOrgCopyRepository orgCopies,
                                    ProcedureFavouriteRepository favourites) {
        this.templates = templates;
        this.versions = versions;
        this.canonicalizer = canonicalizer;
        this.mapper = mapper;
        this.events = events;
        this.fga = fga;
        this.resultTypes = resultTypes;
        this.resultTypeRefs = resultTypeRefs;
        this.targetTypeRefs = targetTypeRefs;
        this.consumers = consumers;
        this.vocabulary = vocabulary;
        this.documents = documents;
        this.documentRefs = documentRefs;
        this.globalTemplates = globalTemplates;
        this.globalVersions = globalVersions;
        this.orgCopies = orgCopies;
        this.favourites = favourites;
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
            template.setConsumerKey(requireActiveConsumer(request.consumerKey()));
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
    public Page<TemplateResponse> list(TemplateStatus status, TemplateScope scope, boolean favouritesOnly,
                                       Pageable pageable) {
        Boolean propertyScoped = scope == null ? null : scope == TemplateScope.PROPERTY;
        Page<ProcedureTemplate> page = templates.findAllByOrgId(OrgContext.getOrgId(), status, propertyScoped,
                favouritesOnly, OrgContext.getUserId(), pageable);
        return withVersionNumbers(page);
    }

    /** Idempotent: starring an already-starred procedure changes nothing. */
    public FavouriteResponse favourite(UUID id) {
        ProcedureTemplate template = getOwned(id);
        UUID userId = OrgContext.getUserId();
        if (favourites.findByUserIdAndTemplateId(userId, template.getId()).isEmpty()) {
            favourites.saveAndFlush(new ProcedureFavourite(userId, template.getId()));
        }
        return new FavouriteResponse(true);
    }

    /** Idempotent: un-starring one that was never starred changes nothing. */
    public FavouriteResponse unfavourite(UUID id) {
        ProcedureTemplate template = getOwned(id);
        favourites.findByUserIdAndTemplateId(OrgContext.getUserId(), template.getId()).ifPresent(favourites::delete);
        return new FavouriteResponse(false);
    }

    /**
     * The active procedures that apply to one target — "which checklists does an
     * extinguisher get?". One that names no target types applies to anything and
     * is always included. Row-level security still hides a procedure that belongs
     * to a property the caller is not standing in.
     */
    @Transactional(readOnly = true)
    public Page<TemplateResponse> discover(String consumerKey, TargetKind kind, String key, Pageable pageable) {
        Page<ProcedureTemplate> page = templates.findApplicableTo(OrgContext.getOrgId(),
                consumerKey.trim().toUpperCase(Locale.ROOT), kind, key.strip(), pageable);
        return withVersionNumbers(page);
    }

    private Page<TemplateResponse> withVersionNumbers(Page<ProcedureTemplate> page) {
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
        // Same rule as create(): where the caller is standing decides the
        // scope, not the source's own scope. Cloning an organization-wide
        // procedure from inside a property must produce a property-scoped
        // copy, not silently widen it back to organization level.
        List<UUID> scope = PropertyContext.current();
        copy.setPropertyId(scope.size() == 1 ? scope.get(0) : null);
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
     *
     * <p>Fork-on-edit: an actual content change to a template still linked
     * to a global source sets that link's state to {@code STANDALONE} — the
     * organization no longer tracks the global template's future updates,
     * because its own copy has diverged. An unchanged re-save is not an
     * edit, matching the no-op-republish convention everywhere else in this
     * service, so it does not fork.
     */
    public VersionResponse saveDraft(UUID id, SaveDraftRequest request) {
        ProcedureTemplate template = lockOwned(id);
        requireActive(template);
        ProcedureTemplateVersion draft = requireDraft(template);
        requireRowVersion(draft, request.rowVersion());

        String previousHash = draft.getDefinitionHash();
        Canonical canonical = assignKeysAndCanonicalize(template, request.definition());
        if (template.getGlobalTemplateId() != null && !canonical.hash().equals(previousHash)) {
            forkFromGlobal(template);
        }
        draft.setDefinition(canonical.json(), canonical.hash());
        draft.setChangeNote(blankToNull(request.changeNote()));
        versions.saveAndFlush(draft);
        return mapper.toResponse(draft);
    }

    /** Sets the link to STANDALONE only if it still points at this template. */
    private void forkFromGlobal(ProcedureTemplate template) {
        orgCopies.findByGlobalTemplateIdAndOrgId(template.getGlobalTemplateId(), template.getOrgId())
                .filter(copy -> copy.getTemplateId().equals(template.getId()))
                .filter(copy -> copy.getLinkState() != LinkState.STANDALONE)
                .ifPresent(copy -> {
                    copy.setLinkState(LinkState.STANDALONE);
                    orgCopies.saveAndFlush(copy);
                });
    }

    /**
     * Stops tracking a global template's future updates without requiring an
     * edit first — the explicit counterpart to fork-on-edit.
     */
    public TemplateResponse unlink(UUID id) {
        ProcedureTemplate template = getOwned(id);
        if (template.getGlobalTemplateId() == null) {
            throw new BusinessRuleException("'" + template.getName()
                    + "' was not imported from the shared library, so there is nothing to unlink");
        }
        forkFromGlobal(template);
        return toResponse(template);
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
        List<String> blockers = DefinitionValidator.publishBlockers(document, activeResultKeys(template.getOrgId()),
                unknownTargetTypes(document, template.getOrgId()), citableDocumentIds(template));
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
        // The same rule for what the version applies to: first freeze only, never
        // a draft. A version that names no target types writes nothing, because
        // that means it applies to anything, not to nothing.
        targetTypeRefs.saveAll(document.targetTypeKeys().stream()
                .map(target -> new VersionTargetType(draft.getId(), target.kind(), target.key()))
                .toList());
        documentRefs.saveAll(document.citedDocumentIds().stream()
                .map(docId -> new VersionDocumentRef(draft.getId(), UUID.fromString(docId)))
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

    // --- sharing: export, import, linking ------------------------------------

    /**
     * Packages one or more of this template's versions for sharing. Never
     * carries an org id, a property id or anything FGA-related — none of
     * that travels with a procedure once it leaves this organization.
     *
     * @param versionNumbers which versions to include; null or empty means
     *                       just the current published one
     * @param includeDocuments whether a version's cited library documents
     *                          ride along as bare references. False strips
     *                          them entirely rather than just omitting
     *                          bytes (which this service never held in the
     *                          first place) — a document id from this
     *                          organization means nothing wherever the
     *                          export ends up, so the safer default is no
     *                          dangling reference at all.
     */
    @Transactional(readOnly = true)
    public ExportedProcedure export(UUID id, List<Integer> versionNumbers, boolean includeDocuments) {
        ProcedureTemplate template = getOwned(id);
        List<ProcedureTemplateVersion> toExport = (versionNumbers == null || versionNumbers.isEmpty())
                ? List.of(currentVersion(template).orElseThrow(() -> new BusinessRuleException(
                        "Procedure '" + template.getName() + "' has never been published, so there is nothing to export")))
                : versionNumbers.stream().map(no -> requireVersion(template, no)).toList();

        List<ExportedVersion> exported = toExport.stream().map(v -> exportVersion(v, includeDocuments)).toList();
        return new ExportedProcedure(template.getName(), template.getDescription(), template.getConsumerKey(), exported);
    }

    private ExportedVersion exportVersion(ProcedureTemplateVersion version, boolean includeDocuments) {
        if (includeDocuments) {
            return new ExportedVersion(version.getVersionNo(), version.getDefinitionJson(),
                    version.getDefinitionHash(), version.getChangeNote());
        }
        DefinitionDocument document = canonicalizer.parse(version.getDefinitionJson());
        if (document.documents().isEmpty()) {
            return new ExportedVersion(version.getVersionNo(), version.getDefinitionJson(),
                    version.getDefinitionHash(), version.getChangeNote());
        }
        DefinitionDocument stripped = new DefinitionDocument(document.schema(), document.items(),
                document.thresholds(), document.targetTypes(), List.of());
        Canonical canonical = canonicalizer.canonicalize(stripped);
        return new ExportedVersion(version.getVersionNo(), canonical.json(), canonical.hash(), version.getChangeNote());
    }

    /**
     * Pulls one published version of a global template into this
     * organization — a new template ({@code templateId} absent) or a new
     * draft of an existing one ({@code templateId} set). Either way, a
     * {@link GlobalTemplateOrgCopy} row is written or updated with
     * {@code link_state = LINKED}, so the organization starts out tracking
     * the global template's future updates.
     *
     * <p>Lands as a draft, never auto-published: the organization reviews
     * and publishes it like any other draft, the same as every other
     * version-creating action in this service.
     */
    public TemplateResponse importFromGlobal(ImportRequest request) {
        GlobalProcedureTemplate globalTemplate = globalTemplates.findById(request.globalTemplateId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Global template not found: " + request.globalTemplateId()));
        GlobalProcedureTemplateVersion source = resolveGlobalVersion(globalTemplate, request.versionNo());

        createMissingResultTypes(canonicalizer.parse(source.getDefinitionJson()).resultTypeKeys());

        ProcedureTemplate template;
        int versionNo;
        if (request.templateId() == null) {
            if (isBlank(request.name())) {
                throw new ValidationException("A name is required when importing as a new procedure");
            }
            template = newTemplateFromGlobal(globalTemplate, request.name().strip());
            versionNo = 1;
            versions.saveAndFlush(newVersion(template, versionNo, source.getDefinitionJson(), source.getDefinitionHash()));
            fga.grantCreated(FGA_TYPE, template.getId(), template.getCreatedBy(), null);
        } else {
            template = lockOwned(request.templateId());
            requireActive(template);
            versions.findByTemplateIdAndState(template.getId(), VersionState.DRAFT).ifPresent(existing -> {
                throw new ConflictException("This procedure already has a draft (v" + existing.getVersionNo()
                        + "); publish or discard it first");
            });
            // Carries the global template's own key counter forward, exactly as
            // cloneTemplate does - the imported content's keys must never land
            // outside the range this template has issued, or a later edit
            // would have KeyMinter refuse them as forged.
            template.setKeySeq(Math.max(template.getKeySeq(), globalTemplate.getKeySeq()));
            versionNo = versions.maxVersionNo(template.getId()) + 1;
            versions.saveAndFlush(newVersion(template, versionNo, source.getDefinitionJson(), source.getDefinitionHash()));
        }

        linkToGlobal(globalTemplate, template, source.getVersionNo());
        return mapper.toResponse(template, currentVersion(template).map(ProcedureTemplateVersion::getVersionNo)
                .orElse(null), versionNo);
    }

    private GlobalProcedureTemplateVersion resolveGlobalVersion(GlobalProcedureTemplate globalTemplate, Integer versionNo) {
        GlobalProcedureTemplateVersion version = versionNo != null
                ? globalVersions.findByGlobalTemplateIdAndVersionNo(globalTemplate.getId(), versionNo)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Global template " + globalTemplate.getId() + " has no version " + versionNo))
                : Optional.ofNullable(globalTemplate.getCurrentPublishedVersionId())
                        .flatMap(globalVersions::findById)
                        .orElseThrow(() -> new BusinessRuleException("'" + globalTemplate.getName()
                                + "' has never been published, so there is nothing to import"));
        if (version.getState() != VersionState.PUBLISHED) {
            throw new BusinessRuleException("Version " + version.getVersionNo() + " of '" + globalTemplate.getName()
                    + "' is not published");
        }
        return version;
    }

    private ProcedureTemplate newTemplateFromGlobal(GlobalProcedureTemplate globalTemplate, String name) {
        UUID orgId = OrgContext.getOrgId();
        requireNameFree(orgId, name, null);

        ProcedureTemplate template = new ProcedureTemplate();
        template.setOrgId(orgId);
        List<UUID> scope = PropertyContext.current();
        template.setPropertyId(scope.size() == 1 ? scope.get(0) : null);
        template.setName(name);
        template.setDescription(globalTemplate.getDescription());
        if (!isBlank(globalTemplate.getConsumerKey())) {
            template.setConsumerKey(requireActiveConsumer(globalTemplate.getConsumerKey()));
        }
        template.setGlobalTemplateId(globalTemplate.getId());
        template.setKeySeq(globalTemplate.getKeySeq());
        template.setCreatedBy(OrgContext.getUserId());
        templates.saveAndFlush(template);
        return template;
    }

    /**
     * Maps the imported document's result keys against this organization's
     * vocabulary; a key it does not have is created rather than dropped or
     * refused. Appended as the least severe rank, since an auto-created
     * type's actual severity relative to what the organization already
     * defined is unknown, and appending can never silently outrank
     * anything existing. An admin can rename, recolour and reorder it
     * afterwards like any other result type.
     */
    private void createMissingResultTypes(Set<String> keys) {
        UUID orgId = OrgContext.getOrgId();
        List<ResultType> existing = resultTypes.findAllByOrgIdOrderBySeverityOrderAsc(orgId);
        Set<String> known = existing.stream().map(ResultType::getKey).collect(Collectors.toSet());
        int nextSeverity = existing.size();
        for (String key : keys) {
            if (known.contains(key)) {
                continue;
            }
            nextSeverity++;
            ResultType created = new ResultType();
            created.setOrgId(orgId);
            created.setKey(key);
            created.setName(key);
            created.setColor("#9E9E9E");
            created.setSeverityOrder(nextSeverity);
            created.setSystem(false);
            resultTypes.save(created);
            known.add(key);
        }
    }

    private void linkToGlobal(GlobalProcedureTemplate globalTemplate, ProcedureTemplate template, int appliedVersionNo) {
        UUID orgId = OrgContext.getOrgId();
        GlobalTemplateOrgCopy copy = orgCopies.findByGlobalTemplateIdAndOrgId(globalTemplate.getId(), orgId)
                .orElseGet(() -> new GlobalTemplateOrgCopy(globalTemplate.getId(), orgId, template.getId(),
                        appliedVersionNo, OffsetDateTime.now()));
        // One link slot per (global template, org): importing again, as a new
        // template, repoints it rather than failing or leaving it stale.
        copy.setTemplateId(template.getId());
        copy.setAppliedVersionNo(appliedVersionNo);
        copy.setLinkState(LinkState.LINKED);
        copy.setDeferredVersionNo(null);
        copy.setDeferredAt(null);
        orgCopies.saveAndFlush(copy);
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
     * The consumer a new procedure names, checked against the organization's
     * active consumers. Normalised the way it always was, then looked up: until
     * now any string up to fifty characters was accepted.
     *
     * <p>Only a new procedure is checked. The consumer is identity — set here and
     * never changed — so a consumer retired later leaves every procedure that
     * already names it exactly as it was, and a clone keeps the one it copies. A
     * request that names none gets INSPECTION, the column default, which is
     * seeded for every organization and is what every existing procedure carries.
     *
     * @throws ValidationException naming the valid ones, since "not a consumer"
     *         with nowhere to go is a dead end
     */
    private String requireActiveConsumer(String requested) {
        String key = requested.strip().toUpperCase(Locale.ROOT);
        List<String> active = consumers.findAllByActiveOrderByKeyAsc(true).stream()
                .map(ProcedureConsumer::getKey).toList();
        if (!active.contains(key)) {
            throw new ValidationException("'" + key + "' is not one of this organization's procedure consumers"
                    + (active.isEmpty() ? "" : ": " + String.join(", ", active)));
        }
        return key;
    }

    /**
     * The document's target types that the organization's vocabulary does not
     * have, for the publish check.
     *
     * <p><b>Only asked when the version declares any.</b> A procedure that
     * applies to anything never touches the vocabulary service, so every
     * existing procedure publishes exactly as before and does not start
     * depending on the helper being up.
     *
     * <p><b>Fails closed, and says which problem it is.</b> If the vocabulary
     * cannot be read, nothing can be said about whether a key exists, so the
     * publish is refused — but with a message about the vocabulary, not about the
     * key. "EXTINGUISHER is not an asset class" would send an author to fix a
     * procedure that is fine. It is a business-rule refusal rather than the
     * shared external-service error because that one answers with a fixed
     * generic sentence and would throw this wording away.
     *
     * <p>A key that exists but has been retired counts as present: refusing it
     * would block an unrelated edit to a procedure that already names it.
     */
    private List<TargetType> unknownTargetTypes(DefinitionDocument document, UUID orgId) {
        if (document.targetTypes().isEmpty()) {
            return List.of();
        }
        Vocabulary known;
        try {
            known = vocabulary.forOrg(orgId);
        } catch (VocabularyUnavailableException e) {
            throw new BusinessRuleException("This procedure cannot be published right now: it names target "
                    + "types, and the property vocabulary they are checked against could not be read. "
                    + "Nothing is wrong with the procedure; try again shortly");
        }
        return document.targetTypes().stream()
                .filter(target -> !known.contains(VocabularyKind.valueOf(target.kind().name()), target.key().strip()))
                .toList();
    }

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
