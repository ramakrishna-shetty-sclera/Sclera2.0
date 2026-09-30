package com.sclera.applicationplane.procedure.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create payload. The result editor lets an author set the severity rank while
 * creating, so {@code severityOrder} is accepted here; leaving it out appends
 * the type as least severe. Rank is changed afterwards by reorder, never by
 * update. isSystem is never accepted — system types are seeded, not created.
 */
public record ResultTypeRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,49}$",
                message = "must be upper-case letters, digits and underscores, starting with a letter")
        String key,

        @NotBlank @Size(max = 100) String name,

        @NotBlank
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "must be a six-digit hex colour, e.g. #2ecc71")
        String color,

        @Size(max = 500) String description,

        /** 1 is most severe. Null appends. Anything beyond one past the end is rejected. */
        @Min(1) Integer severityOrder
) {
}
