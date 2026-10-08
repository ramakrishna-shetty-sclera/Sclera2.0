package com.sclera.applicationplane.procedure.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Who is using which version of a procedure, in and out. One container for the
 * feature, the way {@link EvaluationDtos} is for evaluation.
 */
public final class UsageDtos {

    private UsageDtos() {
    }

    /**
     * What a consumer tells this service: "this configuration of mine is on this
     * version of this procedure".
     *
     * <p>{@code consumerRefId} is the consumer's own id for whatever uses the
     * procedure, such as an inspection configuration's id. It is opaque here and
     * only ever compared for equality, which is why it is text and not a UUID:
     * another service's ids are not this one's to constrain.
     *
     * <p>{@code targetTypeKey} says which target type the consumer bound the
     * procedure to, when it bound one. A consumer that applies a procedure
     * without binding it to a target type leaves it out.
     */
    public record ReportUsageRequest(
            @NotNull UUID templateId,
            @NotNull UUID versionId,
            @NotBlank @Size(max = 50) String consumerKey,
            @NotBlank @Size(max = 100) String consumerRefId,
            @Size(max = 50) String targetTypeKey) {
    }

    /**
     * What was stored. {@code created} is true the first time a consumer reports
     * about a template and false when the report replaced an earlier one, so a
     * caller can tell a new fact from a repeat.
     */
    public record ReportUsageResponse(
            UUID id,
            UUID templateId,
            UUID versionId,
            String consumerKey,
            String consumerRefId,
            String targetTypeKey,
            OffsetDateTime recordedAt,
            boolean created) {
    }
}
