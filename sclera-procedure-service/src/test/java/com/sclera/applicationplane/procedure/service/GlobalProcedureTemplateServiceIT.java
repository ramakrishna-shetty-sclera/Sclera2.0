package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.CreateGlobalTemplateRequest;
import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.GlobalPublishResponse;
import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.GlobalTemplateResponse;
import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.GlobalVersionResponse;
import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.SaveGlobalDraftRequest;
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
 * Authoring Sclera's own shared library — create, save a draft, publish, and
 * start a second draft from the current published version. Platform-admin
 * gating is the controller's job ({@code @fga.isPlatformAdmin()}), not
 * exercised here, same convention as every other *ServiceIT in this service.
 */
class GlobalProcedureTemplateServiceIT extends PostgresIntegrationTest {

    @Autowired
    private GlobalProcedureTemplateService service;

    private static Item question() {
        return Item.builder().text("Exit clear?").type(QuestionType.YES_NO).required(true)
                .options(List.of(new Option(null, "Yes", "PASS", null, false), new Option(null, "No", "FAIL", null, false)))
                .build();
    }

    private static DefinitionDocument withOneQuestion() {
        return new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(question()), List.of(), List.of(), List.of());
    }

    @Test
    void createsTheTemplateAndItsFirstDraft() {
        actAsNewOrg();
        GlobalTemplateResponse response = service.create(
                new CreateGlobalTemplateRequest("NFPA 10", "Fire extinguisher check", "INSPECTION", withOneQuestion()));

        assertThat(response.name()).isEqualTo("NFPA 10");
        assertThat(response.status()).isEqualTo(TemplateStatus.ACTIVE);
        assertThat(response.currentPublishedVersionNo()).isNull();
        assertThat(response.draftVersionNo()).isEqualTo(1);
    }

    @Test
    void savingTheDraftMintsKeysForNewItemsAndKeepsTheOnesSentBack() {
        actAsNewOrg();
        GlobalTemplateResponse created = service.create(new CreateGlobalTemplateRequest("Boiler check", null, null, null));

        GlobalVersionResponse saved = service.saveDraft(created.id(),
                new SaveGlobalDraftRequest(withOneQuestion(), null));

        Item minted = saved.definition().items().get(0);
        assertThat(minted.key()).isEqualTo("q1");
        assertThat(minted.options()).extracting(Option::key).containsExactly("o2", "o3");

        // Sending the minted keys back changes nothing about them.
        GlobalVersionResponse resaved = service.saveDraft(created.id(),
                new SaveGlobalDraftRequest(new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA,
                        List.of(minted), List.of(), List.of(), List.of()), "reworded"));
        assertThat(resaved.definition().items().get(0).key()).isEqualTo("q1");
        assertThat(resaved.changeNote()).isEqualTo("reworded");
    }

    @Test
    void publishingFreezesTheDraftAndRepointsCurrent() {
        actAsNewOrg();
        GlobalTemplateResponse created = service.create(
                new CreateGlobalTemplateRequest("NFPA 72", null, null, withOneQuestion()));

        GlobalPublishResponse published = service.publish(created.id());

        assertThat(published.newVersion()).isTrue();
        assertThat(published.version().versionNo()).isEqualTo(1);
        assertThat(published.version().state()).isEqualTo(VersionState.PUBLISHED);
        assertThatThrownBy(() -> service.getDraft(created.id())).isInstanceOf(Exception.class);
    }

    @Test
    void publishingWithNoQuestionsIsRefused() {
        actAsNewOrg();
        GlobalTemplateResponse created = service.create(new CreateGlobalTemplateRequest("Empty one", null, null, null));

        assertThatThrownBy(() -> service.publish(created.id())).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void republishingUnchangedContentIsANoOp() {
        actAsNewOrg();
        GlobalTemplateResponse created = service.create(
                new CreateGlobalTemplateRequest("Gauge check", null, null, withOneQuestion()));
        GlobalPublishResponse first = service.publish(created.id());

        service.createDraft(created.id());
        GlobalPublishResponse second = service.publish(created.id());

        assertThat(second.newVersion()).isFalse();
        assertThat(second.version().id()).isEqualTo(first.version().id());
    }

    @Test
    void startingASecondDraftWhileOneAlreadyExistsIsRefused() {
        actAsNewOrg();
        GlobalTemplateResponse created = service.create(
                new CreateGlobalTemplateRequest("Already drafting", null, null, withOneQuestion()));
        service.publish(created.id());
        service.createDraft(created.id());

        assertThatThrownBy(() -> service.createDraft(created.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void aSecondVersionBuildsOnTheFirstsKeyCounterRatherThanRestarting() {
        actAsNewOrg();
        GlobalTemplateResponse created = service.create(
                new CreateGlobalTemplateRequest("Walk", null, null, withOneQuestion()));
        service.publish(created.id());
        service.createDraft(created.id());

        Item second = Item.builder().text("A second question?").type(QuestionType.YES_NO).required(true)
                .options(List.of(new Option(null, "Yes", "PASS", null, false), new Option(null, "No", "FAIL", null, false)))
                .build();
        GlobalVersionResponse draft = service.getDraft(created.id());
        GlobalVersionResponse saved = service.saveDraft(created.id(), new SaveGlobalDraftRequest(
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA,
                        List.of(draft.definition().items().get(0), second), List.of(), List.of(), List.of()), null));

        // q1/o2/o3 already minted for the first question; the second must not reuse any of them.
        assertThat(saved.definition().items().get(1).key()).isEqualTo("q4");
    }
}
