package com.sclera.applicationplane.procedure.export;

import com.sclera.controlplane.common.exception.ValidationException;

/** The formats a procedure can be exported as, with their HTTP metadata. */
public enum ExportFormat {

    PDF("application/pdf", "pdf"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");

    private final String mediaType;
    private final String extension;

    ExportFormat(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }

    public static ExportFormat parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("Export format is required (pdf or xlsx)");
        }
        String normalized = raw.strip().toUpperCase();
        // XLS is what the design's picker says; the file produced is OOXML.
        if (normalized.equals("XLS")) {
            return XLSX;
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown export format '" + raw + "' (pdf or xlsx)");
        }
    }
}
