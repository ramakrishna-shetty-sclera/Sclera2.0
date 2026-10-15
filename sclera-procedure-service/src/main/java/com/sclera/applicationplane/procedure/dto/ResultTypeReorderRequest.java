package com.sclera.applicationplane.procedure.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * Severity ordering, most severe first. The list must name every result type
 * in the organization: a partial list is rejected rather than half-applied,
 * because ranks are dense and the ones left out would have no defined place.
 */
public record ResultTypeReorderRequest(
        @NotEmpty List<UUID> orderedIds
) {
}
