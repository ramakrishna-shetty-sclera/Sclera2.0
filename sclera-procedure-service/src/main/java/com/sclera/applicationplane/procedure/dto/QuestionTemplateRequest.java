package com.sclera.applicationplane.procedure.dto;

import com.sclera.applicationplane.procedure.domain.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Create / full-update payload for a draft template. */
public record QuestionTemplateRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @Size(max = 100) String category,
        @NotEmpty @Valid List<SectionRequest> sections
) {
    public record SectionRequest(
            @NotBlank @Size(max = 200) String title,
            int displayOrder,
            @NotEmpty @Valid List<QuestionRequest> questions
    ) {}

    public record QuestionRequest(
            @NotBlank @Size(max = 1000) String text,
            @Size(max = 1000) String helpText,
            @NotNull QuestionType type,
            boolean required,
            int displayOrder,
            List<String> options,
            Integer scoreWeight
    ) {}
}
