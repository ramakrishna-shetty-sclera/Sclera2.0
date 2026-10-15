package com.sclera.applicationplane.procedure.export;

/**
 * One export format. Implementations are discovered by Spring and keyed by
 * {@link #format()} in {@link TemplateExportService} — adding a format means
 * adding an implementation, never touching the service or the controller
 * (open for extension, closed for modification).
 */
public interface TemplateExporter {

    ExportFormat format();

    ExportFile export(TemplateExport template);
}
