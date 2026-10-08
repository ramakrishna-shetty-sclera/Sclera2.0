package com.sclera.applicationplane.procedure.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Create payload. The bytes have already been uploaded through the helper
 * service before this call — {@code location} is the opaque key it handed
 * back. Scope (organization-wide or one property) is not a field: it is
 * decided by where the caller is standing, exactly as a procedure's is.
 */
public record ProcedureDocumentRequest(
        @NotBlank @Size(max = 255) String name,

        @Size(max = 100) String mimeType,

        @Positive Long sizeBytes,

        @NotBlank String location
) {
}
