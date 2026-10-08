package com.sclera.applicationplane.helper.dto;

/**
 * What an upload hands back. {@code location} is opaque and is what the caller
 * stores; {@code downloadUrl} is a fresh link good for one use, never stored.
 */
public record DocumentUploadResponse(String location, String downloadUrl, long sizeBytes, boolean stub) {
}
