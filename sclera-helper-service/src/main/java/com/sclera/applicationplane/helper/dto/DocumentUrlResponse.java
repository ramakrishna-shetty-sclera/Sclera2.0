package com.sclera.applicationplane.helper.dto;

/** A fresh download URL for a location already on file — generated on every request, never cached. */
public record DocumentUrlResponse(String downloadUrl, boolean stub) {
}
