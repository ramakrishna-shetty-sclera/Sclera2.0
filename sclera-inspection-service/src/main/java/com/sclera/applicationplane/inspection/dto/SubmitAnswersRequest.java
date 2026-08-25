package com.sclera.applicationplane.inspection.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Answers submitted against the questions of the snapshotted template. */
public record SubmitAnswersRequest(
        @NotEmpty @Valid List<AnswerItem> answers
) {
    public record AnswerItem(
            @NotNull UUID questionId,
            /** Typed value: string, number, boolean, array or object — stored as JSON. */
            @NotNull JsonNode value,
            Integer score,
            @Size(max = 2000) String comment
    ) {}
}
