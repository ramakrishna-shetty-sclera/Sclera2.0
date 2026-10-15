package com.sclera.applicationplane.procedure.export;

/** A finished export, ready to stream as a download. */
public record ExportFile(String filename, String mediaType, byte[] bytes) {
}
