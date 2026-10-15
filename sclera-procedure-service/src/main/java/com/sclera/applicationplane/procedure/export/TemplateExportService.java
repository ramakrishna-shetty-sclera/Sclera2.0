package com.sclera.applicationplane.procedure.export;

import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.TemplateResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.service.ProcedureTemplateService;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves what to export (the published version by default, a named version
 * on request, the draft only when nothing was ever published) and which
 * {@link TemplateExporter} renders it. Exporters register themselves by being
 * Spring beans — this service never names an implementation.
 */
@Service
public class TemplateExportService {

    private final ProcedureTemplateService templates;
    private final Map<ExportFormat, TemplateExporter> exporters = new EnumMap<>(ExportFormat.class);

    public TemplateExportService(ProcedureTemplateService templates, List<TemplateExporter> available) {
        this.templates = templates;
        for (TemplateExporter exporter : available) {
            exporters.put(exporter.format(), exporter);
        }
    }

    public ExportFile export(java.util.UUID templateId, String rawFormat, Integer versionNo) {
        ExportFormat format = ExportFormat.parse(rawFormat);
        TemplateExporter exporter = exporters.get(format);
        if (exporter == null) {
            throw new BusinessRuleException("No exporter is available for " + format);
        }

        TemplateResponse template = templates.get(templateId);
        VersionResponse version;
        if (versionNo != null) {
            version = templates.getVersion(templateId, versionNo);
        } else if (template.currentPublishedVersionNo() != null) {
            version = templates.getVersion(templateId, template.currentPublishedVersionNo());
        } else if (template.draftVersionNo() != null) {
            version = templates.getDraft(templateId);
        } else {
            throw new BusinessRuleException("This procedure has no version to export");
        }

        return exporter.export(new TemplateExport(
                template.name(),
                template.description(),
                version.versionNo(),
                version.state(),
                version.publishedAt(),
                version.definition()));
    }
}
