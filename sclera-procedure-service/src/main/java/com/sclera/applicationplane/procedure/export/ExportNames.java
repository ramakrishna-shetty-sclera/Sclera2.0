package com.sclera.applicationplane.procedure.export;

import java.util.Locale;

/** One place for the download filename, so every format names files the same way. */
final class ExportNames {

    private ExportNames() {
    }

    static String filename(TemplateExport template, ExportFormat format) {
        String slug = template.name() == null ? "procedure"
                : template.name().strip().toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("(^-+)|(-+$)", "");
        if (slug.isEmpty()) {
            slug = "procedure";
        }
        return slug + "-v" + template.versionNo() + "." + format.extension();
    }
}
