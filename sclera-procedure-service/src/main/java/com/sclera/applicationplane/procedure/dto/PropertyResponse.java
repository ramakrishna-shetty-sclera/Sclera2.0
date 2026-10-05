package com.sclera.applicationplane.procedure.dto;

import java.util.UUID;

/**
 * A property (VDMS) the caller may open. {@code code} is a label such as
 * VDMS001; codes run per organization, so only the id identifies a property,
 * and the id is what goes back in X-Sclera-Property.
 */
public record PropertyResponse(UUID id, String code) {
}
