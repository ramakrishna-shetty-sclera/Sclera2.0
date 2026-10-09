package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.LinkState;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.DiffResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.NewDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.PublishResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.SaveDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.TemplateResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.dto.UpdateDtos.UpdateStatus;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Forking an organization-wide procedure into a property's own copy — the
 * org → property half of feature 11, built after the Sclera → org half
 * because it reuses the same {@link LinkState} and the same mental model.
 */
class PropertyForkIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService service;

    @Autowired
    private ProcedureTemplateRepository templates;

    private static Item question() {
        return Item.builder().text("Exit clear?").type(QuestionType.YES_NO).required(true)
                .options(List.of(new Option(null, "Yes", "PASS", null, false), new Option(null, "No", "FAIL", null, false)))
                .build();
    }

    private static CreateTemplateRequest request(String name) {
        return new CreateTemplateRequest(name, "A description", null,
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(question()), List.of(), List.of(), List.of()));
    }

    @Test
    void forkingAPublishedOrgWideProcedureCreatesALinkedPropertyCopy() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);
        UUID property = UUID.randomUUID();

        TemplateResponse fork = asProperty(org, property, () -> service.fork(source.id()));

        assertThat(fork.propertyId()).isEqualTo(property);
        assertThat(fork.draftVersionNo()).isEqualTo(1);
        VersionResponse draft = asProperty(org, property, () -> service.getDraft(fork.id()));
        assertThat(draft.definition().items()).extracting(Item::text).containsExactly("Exit clear?");

        var row = asProperty(org, property, () -> templates.findByIdAndOrgId(fork.id(), org)).orElseThrow();
        assertThat(row.getForkedFromTemplateId()).isEqualTo(source.id());
        assertThat(row.getLinkState()).isEqualTo(LinkState.LINKED);
        assertThat(row.getAppliedVersionNo()).isEqualTo(1);
    }

    @Test
    void forkingAnUnpublishedProcedureForksItsCurrentDraft() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Draft only"));
        UUID property = UUID.randomUUID();

        TemplateResponse fork = asProperty(org, property, () -> service.fork(source.id()));

        var row = asProperty(org, property, () -> templates.findByIdAndOrgId(fork.id(), org)).orElseThrow();
        assertThat(row.getAppliedVersionNo()).isEqualTo(1);
    }

    @Test
    void forkingRequiresStandingInsideAProperty() {
        actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);

        assertThatThrownBy(() -> service.fork(source.id())).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void forkingAnAlreadyPropertyScopedTemplateIsRefused() {
        UUID org = actAsNewOrg();
        UUID property = UUID.randomUUID();
        TemplateResponse propertyScoped = asProperty(org, property,
                () -> service.create(request("Already a property's own")));

        assertThatThrownBy(() -> asProperty(org, property, () -> service.fork(propertyScoped.id())))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void twoPropertiesForkingTheSameSourceEachGetTheirOwnIndependentCopy() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);
        UUID propertyA = UUID.randomUUID();
        UUID propertyB = UUID.randomUUID();

        TemplateResponse forkA = asProperty(org, propertyA, () -> service.fork(source.id()));
        TemplateResponse forkB = asProperty(org, propertyB, () -> service.fork(source.id()));

        assertThat(forkA.id()).isNotEqualTo(forkB.id());

        VersionResponse draftA = asProperty(org, propertyA, () -> service.getDraft(forkA.id()));
        asProperty(org, propertyA, () -> service.saveDraft(forkA.id(), new SaveDraftRequest(
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA,
                        List.of(Item.builder().key(draftA.definition().items().get(0).key())
                                .text("Exit clear? (property A's wording)").type(QuestionType.YES_NO).required(true)
                                .options(draftA.definition().items().get(0).options()).build()),
                        List.of(), List.of(), List.of()),
                draftA.rowVersion(), null)));

        VersionResponse draftB = asProperty(org, propertyB, () -> service.getDraft(forkB.id()));
        assertThat(draftB.definition().items().get(0).text()).isEqualTo("Exit clear?");
        var rowB = asProperty(org, propertyB, () -> templates.findByIdAndOrgId(forkB.id(), org)).orElseThrow();
        assertThat(rowB.getLinkState()).isEqualTo(LinkState.LINKED);
    }

    @Test
    void forkingReusesTheSourcesNameWithoutBeingRefusedAsADuplicate() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);
        UUID property = UUID.randomUUID();

        TemplateResponse fork = asProperty(org, property, () -> service.fork(source.id()));

        assertThat(fork.name()).isEqualTo(source.name());
    }

    /**
     * Publishes v2 of the parent with genuinely different content. Runs
     * entirely at organization level in its own context: callers invoke this
     * after an {@code asProperty} block has already ended and cleared
     * OrgContext, so the org must be re-entered here rather than assumed
     * still active.
     */
    private PublishResponse publishASecondVersionOfTheParent(UUID org, UUID parentId) {
        return asOrg(org, () -> {
            service.createDraft(parentId, new NewDraftRequest(1));
            VersionResponse draft = service.getDraft(parentId);
            Item original = draft.definition().items().get(0);
            Item changed = Item.builder().key(original.key()).text(original.text() + " (updated)")
                    .type(QuestionType.YES_NO).required(true).options(original.options()).build();
            service.saveDraft(parentId, new SaveDraftRequest(
                    new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA,
                            List.of(changed), List.of(), List.of(), List.of()),
                    draft.rowVersion(), "reworded"));
            return service.publish(parentId, null);
        });
    }

    @Test
    void updateStatusOnAFreshForkReportsNoUpdateAvailable() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);
        UUID property = UUID.randomUUID();
        TemplateResponse fork = asProperty(org, property, () -> service.fork(source.id()));

        UpdateStatus status = asProperty(org, property, () -> service.updateStatus(fork.id()));

        assertThat(status.linkState()).isEqualTo(LinkState.LINKED);
        assertThat(status.appliedVersionNo()).isEqualTo(1);
        assertThat(status.currentVersionNo()).isEqualTo(1);
        assertThat(status.updateAvailable()).isFalse();
    }

    @Test
    void updateStatusReportsAvailableAfterTheParentPublishesAgain() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);
        UUID property = UUID.randomUUID();
        TemplateResponse fork = asProperty(org, property, () -> service.fork(source.id()));

        publishASecondVersionOfTheParent(org, source.id());

        UpdateStatus status = asProperty(org, property, () -> service.updateStatus(fork.id()));
        assertThat(status.appliedVersionNo()).isEqualTo(1);
        assertThat(status.currentVersionNo()).isEqualTo(2);
        assertThat(status.updateAvailable()).isTrue();
    }

    @Test
    void updateDiffShowsWhatTheParentChangedSinceTheForkWasMade() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);
        UUID property = UUID.randomUUID();
        TemplateResponse fork = asProperty(org, property, () -> service.fork(source.id()));
        publishASecondVersionOfTheParent(org, source.id());

        DiffResponse diff = asProperty(org, property, () -> service.updateDiff(fork.id()));

        assertThat(diff.fromVersionNo()).isEqualTo(1);
        assertThat(diff.toVersionNo()).isEqualTo(2);
        assertThat(diff.diff()).isNotNull();
    }

    @Test
    void applyingAnUpdatePullsTheParentsCurrentVersionIntoANewForkDraft() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);
        UUID property = UUID.randomUUID();
        TemplateResponse fork = asProperty(org, property, () -> service.fork(source.id()));
        // The fork's own v1 draft must be published first, or apply-update's
        // "a draft is already in progress" refusal fires on that draft
        // instead -- the same step the global-import test above needs too.
        asProperty(org, property, () -> service.publish(fork.id(), null));
        publishASecondVersionOfTheParent(org, source.id());

        TemplateResponse applied = asProperty(org, property, () -> service.applyUpdate(fork.id()));

        assertThat(applied.id()).isEqualTo(fork.id());
        assertThat(applied.draftVersionNo()).isEqualTo(2);
        UpdateStatus status = asProperty(org, property, () -> service.updateStatus(fork.id()));
        assertThat(status.appliedVersionNo()).isEqualTo(2);
        assertThat(status.linkState()).isEqualTo(LinkState.LINKED);
        assertThat(status.updateAvailable()).isFalse();
    }

    @Test
    void deferringOnAForkRecordsTheChoiceButStillReportsAnUpdateAvailable() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);
        UUID property = UUID.randomUUID();
        TemplateResponse fork = asProperty(org, property, () -> service.fork(source.id()));
        publishASecondVersionOfTheParent(org, source.id());

        UpdateStatus deferred = asProperty(org, property, () -> service.deferUpdate(fork.id()));

        assertThat(deferred.linkState()).isEqualTo(LinkState.DEFERRED);
        assertThat(deferred.appliedVersionNo()).isEqualTo(1);
        assertThat(deferred.updateAvailable()).isTrue();
    }

    @Test
    void deferringOnAForkWithNothingToDeferIsRefused() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);
        UUID property = UUID.randomUUID();
        TemplateResponse fork = asProperty(org, property, () -> service.fork(source.id()));

        assertThatThrownBy(() -> asProperty(org, property, () -> service.deferUpdate(fork.id())))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void applyingAnUpdateOnAForkResetsADeferredLinkBackToLinked() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);
        UUID property = UUID.randomUUID();
        TemplateResponse fork = asProperty(org, property, () -> service.fork(source.id()));
        asProperty(org, property, () -> service.publish(fork.id(), null));
        publishASecondVersionOfTheParent(org, source.id());
        asProperty(org, property, () -> service.deferUpdate(fork.id()));

        asProperty(org, property, () -> service.applyUpdate(fork.id()));

        UpdateStatus status = asProperty(org, property, () -> service.updateStatus(fork.id()));
        assertThat(status.linkState()).isEqualTo(LinkState.LINKED);
        assertThat(status.appliedVersionNo()).isEqualTo(2);
    }

    @Test
    void publishingTheParentReportsHowManyForksAreStillLinked() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);
        UUID propertyA = UUID.randomUUID();
        UUID propertyB = UUID.randomUUID();
        asProperty(org, propertyA, () -> service.fork(source.id()));
        TemplateResponse forkB = asProperty(org, propertyB, () -> service.fork(source.id()));
        // forkB stops tracking; only forkA's property should count afterwards.
        // Set directly via the repository rather than service.unlink(), which
        // does not yet understand forkedFromTemplateId -- that generalisation
        // is a later commit's scope, not this one's.
        asProperty(org, propertyB, () -> {
            var row = templates.findByIdAndOrgId(forkB.id(), org).orElseThrow();
            row.setLinkState(LinkState.STANDALONE);
            return templates.saveAndFlush(row);
        });

        var response = publishASecondVersionOfTheParent(org, source.id());

        assertThat(response.linkedForkCount()).isEqualTo(1);
    }

    // --- name uniqueness, now scoped by property too (the fix this feature needed) ---

    @Test
    void twoDifferentPropertiesMayEachHaveATemplateWithTheSameName() {
        UUID org = actAsNewOrg();
        UUID propertyA = UUID.randomUUID();
        UUID propertyB = UUID.randomUUID();

        TemplateResponse a = asProperty(org, propertyA, () -> service.create(request("Fire walk")));
        TemplateResponse b = asProperty(org, propertyB, () -> service.create(request("Fire walk")));

        assertThat(a.id()).isNotEqualTo(b.id());
    }

    @Test
    void anOrgWideTemplateAndAPropertysTemplateMayShareAName() {
        UUID org = actAsNewOrg();
        service.create(request("Fire walk"));
        UUID property = UUID.randomUUID();

        assertThat(asProperty(org, property, () -> service.create(request("Fire walk"))).name())
                .isEqualTo("Fire walk");
    }

    @Test
    void theSameNameWithinOneSinglePropertyIsStillRefused() {
        UUID org = actAsNewOrg();
        UUID property = UUID.randomUUID();
        asProperty(org, property, () -> service.create(request("Fire walk")));

        assertThatThrownBy(() -> asProperty(org, property, () -> service.create(request("Fire walk"))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void twoOrgWideTemplatesWithTheSameNameAreStillRefused() {
        actAsNewOrg();
        service.create(request("Fire walk"));

        assertThatThrownBy(() -> service.create(request("Fire walk"))).isInstanceOf(ConflictException.class);
    }

    // --- fork-on-edit and unlink, extended to a property's own fork ---

    @Test
    void editingAForksDraftWithARealChangeForksIt() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);
        UUID property = UUID.randomUUID();
        TemplateResponse fork = asProperty(org, property, () -> service.fork(source.id()));
        VersionResponse draft = asProperty(org, property, () -> service.getDraft(fork.id()));

        asProperty(org, property, () -> service.saveDraft(fork.id(), new SaveDraftRequest(
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA,
                        List.of(Item.builder().key(draft.definition().items().get(0).key())
                                .text("Exit clear? (reworded)").type(QuestionType.YES_NO).required(true)
                                .options(draft.definition().items().get(0).options()).build()),
                        List.of(), List.of(), List.of()),
                draft.rowVersion(), null)));

        var row = asProperty(org, property, () -> templates.findByIdAndOrgId(fork.id(), org)).orElseThrow();
        assertThat(row.getLinkState()).isEqualTo(LinkState.STANDALONE);
    }

    @Test
    void reSavingUnchangedContentOnAForkDoesNotFork() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);
        UUID property = UUID.randomUUID();
        TemplateResponse fork = asProperty(org, property, () -> service.fork(source.id()));
        VersionResponse draft = asProperty(org, property, () -> service.getDraft(fork.id()));

        asProperty(org, property, () -> service.saveDraft(fork.id(),
                new SaveDraftRequest(draft.definition(), draft.rowVersion(), null)));

        var row = asProperty(org, property, () -> templates.findByIdAndOrgId(fork.id(), org)).orElseThrow();
        assertThat(row.getLinkState()).isEqualTo(LinkState.LINKED);
    }

    @Test
    void explicitUnlinkOnAForkStopsTrackingWithoutRequiringAnEdit() {
        UUID org = actAsNewOrg();
        TemplateResponse source = service.create(request("Fire walk"));
        service.publish(source.id(), null);
        UUID property = UUID.randomUUID();
        TemplateResponse fork = asProperty(org, property, () -> service.fork(source.id()));

        asProperty(org, property, () -> service.unlink(fork.id()));

        var row = asProperty(org, property, () -> templates.findByIdAndOrgId(fork.id(), org)).orElseThrow();
        assertThat(row.getLinkState()).isEqualTo(LinkState.STANDALONE);
    }

    @Test
    void unlinkingAHandAuthoredTemplateThatWasNeverForkedOrImportedIsRefused() {
        UUID org = actAsNewOrg();
        UUID property = UUID.randomUUID();
        TemplateResponse own = asProperty(org, property, () -> service.create(request("Hand-authored")));

        assertThatThrownBy(() -> asProperty(org, property, () -> service.unlink(own.id())))
                .isInstanceOf(BusinessRuleException.class);
    }
}
