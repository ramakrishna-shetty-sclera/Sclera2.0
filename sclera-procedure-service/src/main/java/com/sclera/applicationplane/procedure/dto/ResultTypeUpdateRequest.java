package com.sclera.applicationplane.procedure.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Update payload. Deliberately carries no key: records and definition
 * documents reference the key, so changing it would orphan them. Name, colour
 * and description are presentation and may change freely — including on the
 * seeded Pass and Fail, since an organization that says "Compliant" rather
 * than "Pass" must be able to say so.
 */
public record ResultTypeUpdateRequest(
        @NotBlank @Size(max = 100) String name,

        @NotBlank
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "must be a six-digit hex colour, e.g. #2ecc71")
        String color,

        @Size(max = 500) String description
) {
}
