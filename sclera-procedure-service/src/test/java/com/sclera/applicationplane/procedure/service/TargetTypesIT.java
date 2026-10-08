package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.client.vocabulary.CachedVocabulary;
import com.sclera.applicationplane.procedure.client.vocabulary.Vocabulary;
import com.sclera.applicationplane.procedure.client.vocabulary.VocabularyEntry;
import com.sclera.applicationplane.procedure.client.vocabulary.VocabularyKind;
import com.sclera.applicationplane.procedure.client.vocabulary.VocabularyUnavailableException;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetKind;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetType;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.NewDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.PublishResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.SaveDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What a version applies to, through the service against real Postgres, with the
 * vocabulary replaced by a mock so a test can make it complete, retire a key, or
 * take it away. The vocabulary client itself is tested on its own; these hold the
 * rules about when it is asked and what a refusal says.
 *
 * Each test runs in a brand-new organization.
 */
class TargetTypesIT extends PostgresIntegrationTest {

    @Autowired
    private ProcedureTemplateService procedures;

    @MockBean
    private CachedVocabulary vocabulary;

    private static TargetType asset(String key) {
        return new TargetType(TargetKind.ASSET_CLASS, key);
    }

    private void vocabularyHas(String... assetClasses) {
        // doReturn, not when(...).thenReturn: re-stubbing a mock that is already set to
        // throw would call it, and throw, while stubbing.
        doReturn(new Vocabulary(Map.of(VocabularyKind.ASSET_CLASS,
                List.of(assetClasses).stream().map(k -> new VocabularyEntry(k, k, 1, true)).toList())))
                .when(vocabulary).forOrg(any());
    }

    private static CreateTemplateRequest create(TargetType... targets) {
        Item question = new Item(null, "Extinguisher present?", null, QuestionType.YES_NO, true, false,
                List.of(new Option(null, "Yes", "PASS", null, false), new Option(null, "No", "FAIL", null, false)),
                null, null, null, false, null, false, null, null, null, null, null, List.of(), List.of());
        return new CreateTemplateRequest("Fire walk " + UUID.randomUUID(), null, null,
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(question), List.of(), List.of(targets), List.of()));
    }

    // --- the vocabulary is only asked when there is something to check -----------------

    @Test
    void aProcedureThatAppliesToAnythingNeverAsksTheVocabularyEvenWhenItIsDown() {
        // Every procedure written before target types existed is this one. It
        // must not start depending on the helper being up.
        UUID org = actAsNewOrg();
        when(vocabulary.forOrg(any())).thenThrow(new VocabularyUnavailableException("down", null));
        UUID id = procedures.create(create()).id();

        PublishResponse published = procedures.publish(id, null);

        assertThat(published.newVersion()).isTrue();
        verifyNoInteractions(vocabulary);
    }

    @Test
    void aProcedureNamingKeysTheOrganizationHasPublishes() {
        UUID org = actAsNewOrg();
        vocabularyHas("EXTINGUISHER", "HOSE_REEL");
        UUID id = procedures.create(create(asset("EXTINGUISHER"), asset("HOSE_REEL"))).id();

        PublishResponse published = procedures.publish(id, null);

        assertThat(published.version().definition().targetTypes())
                .containsExactly(asset("EXTINGUISHER"), asset("HOSE_REEL"));
        verify(vocabulary).forOrg(org);          // asked about this organization, no other
    }

    // --- a key that is not there ---------------------------------------------------------

    @Test
    void aDraftNamingAnUnknownKeySavesAndTheSameDocumentRefusesToPublishNamingTheKey() {
        actAsNewOrg();
        vocabularyHas("EXTINGUISHER");
        UUID id = procedures.create(create(asset("SPRINKLER"))).id();      // saves: a draft may be unfinished

        assertThatThrownBy(() -> procedures.publish(id, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("This procedure cannot be published yet: "
                        + "The target type 'SPRINKLER' (asset class) is not one of this organization's asset classes");
    }

    @Test
    void aKeyIsCheckedAgainstTheListItWasNamedForNotAnyList() {
        // FLOOR exists, but as an asset class. The procedure says it is a
        // hierarchy level, which this organization's hierarchy levels do not have here.
        actAsNewOrg();
        vocabularyHas("FLOOR");
        UUID id = procedures.create(create(new TargetType(TargetKind.HIERARCHY_LEVEL, "FLOOR"))).id();

        assertThatThrownBy(() -> procedures.publish(id, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("'FLOOR' (hierarchy level) is not one of this organization's hierarchy levels");
    }
    @Test
    void aRetiredKeyIsStillAcceptedSoAnUnrelatedEditIsNotBlocked() {
        actAsNewOrg();
        when(vocabulary.forOrg(any())).thenReturn(new Vocabulary(Map.of(VocabularyKind.ASSET_CLASS,
                List.of(new VocabularyEntry("HOSE_REEL", "Hose reel", 1, false)))));
        UUID id = procedures.create(create(asset("HOSE_REEL"))).id();

        assertThat(procedures.publish(id, null).newVersion()).isTrue();
    }

    // --- the vocabulary cannot be read ----------------------------------------------------

    @Test
    void anUnreadableVocabularyRefusesThePublishAndSaysItIsTheVocabularyNotTheKey() {
        actAsNewOrg();
        when(vocabulary.forOrg(any())).thenThrow(new VocabularyUnavailableException("down", null));
        UUID id = procedures.create(create(asset("EXTINGUISHER"))).id();

        assertThatThrownBy(() -> procedures.publish(id, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("property vocabulary")
                .hasMessageContaining("could not be read")
                .hasMessageContaining("Nothing is wrong with the procedure")
                // The other problem sounds different, and must not be confused with it.
                .hasMessageNotContaining("is not one of this organization's");
    }

    @Test
    void afterAnOutageTheNextPublishWorks() {
        actAsNewOrg();
        UUID id = procedures.create(create(asset("EXTINGUISHER"))).id();
        when(vocabulary.forOrg(any())).thenThrow(new VocabularyUnavailableException("down", null));
        assertThatThrownBy(() -> procedures.publish(id, null)).isInstanceOf(BusinessRuleException.class);

        vocabularyHas("EXTINGUISHER");

        assertThat(procedures.publish(id, null).newVersion()).isTrue();
    }

    // --- saving ------------------------------------------------------------------------------

    @Test
    void aTargetTypeListedTwiceIsRefusedOnCreate() {
        actAsNewOrg();

        assertThatThrownBy(() -> procedures.create(create(asset("EXTINGUISHER"), asset("EXTINGUISHER"))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("is listed more than once");
    }

    @Test
    void whatAProcedureAppliesToSurvivesAToAndFroThroughTheDraft() {
        actAsNewOrg();
        UUID id = procedures.create(create(asset("EXTINGUISHER"), new TargetType(TargetKind.HIERARCHY_LEVEL, "FLOOR"))).id();

        assertThat(procedures.getDraft(id).definition().targetTypes())
                .containsExactly(asset("EXTINGUISHER"), new TargetType(TargetKind.HIERARCHY_LEVEL, "FLOOR"));
    }

    // --- a new version ---------------------------------------------------------------------

    @Test
    void widenningWhatAProcedureAppliesToMakesANewVersion() {
        actAsNewOrg();
        vocabularyHas("EXTINGUISHER", "HOSE_REEL");
        UUID id = procedures.create(create(asset("EXTINGUISHER"))).id();
        PublishResponse v1 = procedures.publish(id, null);

        procedures.createDraft(id, new NewDraftRequest(1));
        VersionResponse draft = procedures.getDraft(id);
        DefinitionDocument widened = new DefinitionDocument(draft.definition().schema(), draft.definition().items(),
                draft.definition().thresholds(), List.of(asset("EXTINGUISHER"), asset("HOSE_REEL")), List.of());
        procedures.saveDraft(id, new SaveDraftRequest(widened, draft.rowVersion(), "Added hose reels"));
        PublishResponse v2 = procedures.publish(id, null);

        // Not one question changed, so only the hash can tell these apart.
        assertThat(v2.newVersion()).isTrue();
        assertThat(v2.version().versionNo()).isEqualTo(2);
        assertThat(v2.version().definitionHash()).isNotEqualTo(v1.version().definitionHash());
    }
}