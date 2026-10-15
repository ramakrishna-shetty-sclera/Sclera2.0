package com.sclera.applicationplane.procedure.ai;

import com.sclera.applicationplane.procedure.domain.GeneratedQuestionStatus;
import com.sclera.applicationplane.procedure.domain.ItemSource;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ChatMessageRequest;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ChatSessionResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ChatTurnResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ImportQuestionsResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.TemplateResponse;
import com.sclera.applicationplane.procedure.service.ProcedureTemplateService;
import com.sclera.applicationplane.procedure.service.QuestionGenerationService;
import com.sclera.applicationplane.procedure.service.QuestionImportService;
import com.sclera.applicationplane.procedure.support.AiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Feature 2 end to end against a scripted LLM: a session over a draft, a turn
 * that adds candidates, a turn that refines one and removes another, and the
 * same shared import path at the end.
 */
class ChatGenerationIT extends AiIntegrationTest {

    @Autowired
    private ProcedureTemplateService templateService;
    @Autowired
    private QuestionGenerationService generation;
    @Autowired
    private QuestionImportService imports;

    @Test
    void chatRefinesCandidatesAcrossTurnsAndImports() {
        actAsNewOrg();
        TemplateResponse template = templateService.create(
                new CreateTemplateRequest("Pool maintenance", null, null, null));
        ChatSessionResponse session = generation.createChatSession(template.id());

        when(llm.complete(any(), any(), any(), eq(LlmSchemas.ChatTurn.class)))
                // Turn 1: two new candidates.
                .thenAnswer(inv -> new LlmClient.Result<>(new LlmSchemas.ChatTurn(
                        "Added two checks for the pump room.",
                        List.of(question("Pump pressure gauge reads normally"),
                                question("Chemical feeder is stocked")),
                        List.of(), List.of()),
                        "mock-model", 80, 40, 10))
                // Turn 2: refine #1, remove #2.
                .thenAnswer(inv -> new LlmClient.Result<>(new LlmSchemas.ChatTurn(
                        "Tightened the first one and dropped the second.",
                        List.of(),
                        List.of(new LlmSchemas.ChatRevision(1,
                                question("Pump pressure gauge reads between 10 and 20 psi"))),
                        List.of(2)),
                        "mock-model", 60, 30, 8));

        ChatTurnResponse first = generation.generateFromChat(session.id(),
                new ChatMessageRequest("Build a pump-room checklist", null));
        assertThat(first.reply()).contains("pump room");
        assertThat(first.candidates()).hasSize(2);
        assertThat(first.candidates()).allMatch(c -> c.turnNo() == 1);

        ChatTurnResponse second = generation.generateFromChat(session.id(),
                new ChatMessageRequest("Make the pressure check specific, drop the feeder one", null));
        assertThat(second.candidates()).hasSize(1);
        assertThat(second.candidates().get(0).question().text())
                .isEqualTo("Pump pressure gauge reads between 10 and 20 psi");
        assertThat(second.candidates().get(0).turnNo()).isEqualTo(2);

        // The removed candidate is DISCARDED, not deleted.
        assertThat(imports.listDrafts(template.id(), GeneratedQuestionStatus.DISCARDED)).hasSize(1);

        ImportQuestionsResponse imported = imports.importQuestions(template.id(),
                List.of(second.candidates().get(0).id()));
        assertThat(imported.imported()).hasSize(1);
        var item = imported.draft().definition().items().get(0);
        assertThat(item.type()).isEqualTo(QuestionType.YES_NO);
        assertThat(item.source()).isEqualTo(ItemSource.SUGGESTED);
        assertThat(item.key()).startsWith("q");
    }

    private static LlmSchemas.LlmQuestion question(String text) {
        return new LlmSchemas.LlmQuestion(text, null, QuestionType.YES_NO, true,
                null, null, null, null, null, null, null, 0.85, "user asked for a pump-room checklist");
    }
}
