package com.sclera.applicationplane.procedure.ai;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.domain.AiDocumentStatus;
import com.sclera.applicationplane.procedure.domain.GeneratedQuestionSource;
import com.sclera.applicationplane.procedure.domain.GeneratedQuestionStatus;
import com.sclera.applicationplane.procedure.domain.GenerationJobStatus;
import com.sclera.applicationplane.procedure.domain.ItemSource;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.AiDocumentResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.GenerateFromDocumentRequest;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.GenerationJobResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ImportQuestionsResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.QuestionDraftResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.TemplateResponse;
import com.sclera.applicationplane.procedure.service.AiDocumentService;
import com.sclera.applicationplane.procedure.service.ProcedureTemplateService;
import com.sclera.applicationplane.procedure.service.QuestionGenerationService;
import com.sclera.applicationplane.procedure.service.QuestionImportService;
import com.sclera.applicationplane.procedure.support.AiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Feature 1 end to end against a scripted LLM: upload → extraction → async
 * generation job → candidates (validated, provenance, dedup flag) → the shared
 * import path → questions in the draft with minted keys — and a second import
 * that proves idempotency.
 */
class DocumentGenerationIT extends AiIntegrationTest {

    @Autowired
    private ProcedureTemplateService templateService;
    @Autowired
    private QuestionGenerationService generation;
    @Autowired
    private AiDocumentService documents;
    @Autowired
    private QuestionImportService imports;

    @Test
    void documentToImportedQuestions() {
        actAsNewOrg();

        // An existing question, so dedup has something to flag against.
        TemplateResponse template = templateService.create(new CreateTemplateRequest(
                "Fire safety walk", null, null,
                new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(
                        new Item(null, "Fire extinguisher is present and charged", null,
                                QuestionType.YES_NO, true,
                                List.of(new DefinitionDocument.Option(null, "Yes", "PASS"),
                                        new DefinitionDocument.Option(null, "No", "FAIL")),
                                null, null, null, false, null, null, null, null, null, null)))));

        when(llm.resolveModel(any())).thenReturn("mock-model");
        when(llm.complete(any(), any(), any(), eq(LlmSchemas.GeneratedBatch.class)))
                .thenAnswer(inv -> new LlmClient.Result<>(new LlmSchemas.GeneratedBatch(List.of(
                        new LlmSchemas.LlmQuestion("Exit door closes and latches", null,
                                QuestionType.YES_NO, true, null, null, null, null, null, "NFPA 101",
                                List.of(new LlmSchemas.LlmFollowUp(1, "Describe the fault", null,
                                        QuestionType.TEXT, false, null, null, null, null, null, null)),
                                0.9, "Exit doors must close and latch."),
                        // Near-duplicate of the existing question — flagged, not dropped.
                        new LlmSchemas.LlmQuestion("Fire extinguisher is present and charged", null,
                                QuestionType.YES_NO, true, null, null, null, null, null, null,
                                null, 0.8, "A charged extinguisher shall be provided."),
                        // Unsalvageable: no text. Rejected by the mapper.
                        new LlmSchemas.LlmQuestion("  ", null, QuestionType.TEXT, false,
                                null, null, null, null, null, null, null, 0.1, null))),
                        "mock-model", 100, 50, 10));

        AiDocumentResponse document = documents.upload(new MockMultipartFile(
                "file", "sop.txt", "text/plain",
                "Exit doors must close and latch. A charged extinguisher shall be provided."
                        .getBytes(StandardCharsets.UTF_8)));
        AiDocumentResponse ready = await(() -> documents.getDocument(document.id()),
                d -> d.status() != AiDocumentStatus.PENDING && d.status() != AiDocumentStatus.EXTRACTING);
        assertThat(ready.status()).isEqualTo(AiDocumentStatus.READY);

        GenerationJobResponse job = generation.generateFromDocument(template.id(),
                new GenerateFromDocumentRequest(document.id(), "focus on safety", null));
        assertThat(job.status()).isEqualTo(GenerationJobStatus.QUEUED);

        GenerationJobResponse done = await(() -> generation.getJob(job.id()),
                j -> j.status() == GenerationJobStatus.COMPLETED || j.status() == GenerationJobStatus.FAILED);
        assertThat(done.status()).isEqualTo(GenerationJobStatus.COMPLETED);
        assertThat(done.model()).isEqualTo("mock-model");
        assertThat(done.inputTokens()).isEqualTo(100);
        assertThat(done.outputTokens()).isEqualTo(50);

        // The blank-text item was rejected; two candidates survived.
        assertThat(done.candidates()).hasSize(2);
        QuestionDraftResponse followUpCandidate = done.candidates().get(0);
        assertThat(followUpCandidate.source()).isEqualTo(GeneratedQuestionSource.DOCUMENT);
        assertThat(followUpCandidate.sourceRef()).isEqualTo(job.id());
        assertThat(followUpCandidate.sourceSpan()).contains("close and latch");
        assertThat(followUpCandidate.confidence()).isEqualTo(0.9);
        assertThat(followUpCandidate.question().followUps()).hasSize(1);
        assertThat(followUpCandidate.similarExisting()).isNull();

        QuestionDraftResponse duplicate = done.candidates().get(1);
        assertThat(duplicate.similarExisting()).isNotNull();
        assertThat(duplicate.similarExisting().templateName()).isEqualTo("Fire safety walk");

        // Import both through the one shared path.
        List<UUID> ids = List.of(followUpCandidate.id(), duplicate.id());
        ImportQuestionsResponse imported = imports.importQuestions(template.id(), ids);
        assertThat(imported.imported()).hasSize(2);
        assertThat(imported.alreadyImported()).isEmpty();

        DefinitionDocument draft = imported.draft().definition();
        assertThat(draft.items()).hasSize(3);
        Item importedItem = draft.items().get(1);
        assertThat(importedItem.key()).startsWith("q");
        assertThat(importedItem.source()).isEqualTo(ItemSource.DOCUMENT);
        assertThat(importedItem.options()).allMatch(o -> o.key() != null && o.key().startsWith("o"));
        // Yes→PASS / No→FAIL were applied, and the follow-up points at "No"'s minted key.
        assertThat(importedItem.options().get(0).result()).isEqualTo("PASS");
        assertThat(importedItem.options().get(1).result()).isEqualTo("FAIL");
        assertThat(importedItem.follow()).hasSize(1);
        assertThat(importedItem.follow().get(0).when()).isEqualTo(importedItem.options().get(1).key());

        // Importing the same candidates again is a no-op, not a duplicate.
        ImportQuestionsResponse replay = imports.importQuestions(template.id(), ids);
        assertThat(replay.imported()).isEmpty();
        assertThat(replay.alreadyImported()).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(replay.draft().definition().items()).hasSize(3);

        assertThat(imports.listDrafts(template.id(), GeneratedQuestionStatus.IMPORTED)).hasSize(2);
        assertThat(imports.listDrafts(template.id(), GeneratedQuestionStatus.DRAFT)).isEmpty();
    }
}
