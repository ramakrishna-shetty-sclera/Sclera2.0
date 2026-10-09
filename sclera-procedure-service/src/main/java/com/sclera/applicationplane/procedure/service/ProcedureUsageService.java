package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.domain.ProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.ProcedureUsage;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.dto.UsageDtos.ReportUsageRequest;
import com.sclera.applicationplane.procedure.dto.UsageDtos.ReportUsageResponse;
import com.sclera.applicationplane.procedure.dto.UsageDtos.VersionUsageSummary;
import com.sclera.applicationplane.procedure.repository.ProcedureConsumerRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateVersionRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureUsageRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What consumers report about the versions they use, and what an author is told
 * of it.
 *
 * <p>Kept apart from {@link ProcedureTemplateService}, which is already large,
 * and because this is a different kind of caller: a report arrives from another
 * service with no user, no property and no organization context beyond the one
 * the controller pinned.
 */
@Service
@Transactional
public class ProcedureUsageService {

    private final ProcedureUsageRepository usage;
    private final ProcedureTemplateVersionRepository versions;
    private final ProcedureConsumerRepository consumers;

    public ProcedureUsageService(ProcedureUsageRepository usage,
                                 ProcedureTemplateVersionRepository versions,
                                 ProcedureConsumerRepository consumers) {
        this.usage = usage;
        this.versions = versions;
        this.consumers = consumers;
    }

    /**
     * Records that a consumer's configuration is on a version of a procedure.
     *
     * <p>A find-or-create on {@code (consumerKey, consumerRefId, templateId)}: the
     * first report inserts a row, and every later one for the same triple updates
     * it in place, so a configuration that moves from v3 to v4 is one row changing.
     *
     * <p><b>Checked against the version, not the template.</b> A report arrives
     * with no property in scope, so row-level security would hide a property's
     * template from this lookup. The version table has no such policy and the
     * evaluation endpoint reaches it the same way, so a consumer on a property's
     * procedure can report. What is checked is that the version exists, belongs to
     * the template the consumer named, and is published: a consumer is only ever
     * configured against a published version, so a draft is refused.
     *
     * <p>The consumer key is looked up rather than trusted, so a typo is refused
     * naming the key. A <em>retired</em> consumer is still accepted: retiring one
     * stops new procedures naming it, and does not make an existing configuration
     * stop being in use.
     */
    public ReportUsageResponse reportUsage(ReportUsageRequest request) {
        ProcedureTemplateVersion version = versions.findById(request.versionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Procedure version not found: " + request.versionId()));
        if (!version.getTemplateId().equals(request.templateId())) {
            throw new ValidationException("Version " + request.versionId()
                    + " does not belong to procedure " + request.templateId());
        }
        if (version.getState() != VersionState.PUBLISHED) {
            throw new BusinessRuleException("Version " + request.versionId() + " is a draft; a consumer "
                    + "can only be configured against a published version");
        }

        String consumerKey = request.consumerKey().strip().toUpperCase(Locale.ROOT);
        if (!consumers.existsById(consumerKey)) {
            throw new ValidationException("'" + consumerKey + "' is not a procedure consumer");
        }
        String consumerRefId = request.consumerRefId().strip();
        String targetTypeKey = request.targetTypeKey() == null || request.targetTypeKey().isBlank()
                ? null : request.targetTypeKey().strip();

        var existing = usage.findByConsumerKeyAndConsumerRefIdAndTemplateId(
                consumerKey, consumerRefId, request.templateId());
        boolean created = existing.isEmpty();
        ProcedureUsage row = existing.orElseGet(() -> new ProcedureUsage(
                request.templateId(), request.versionId(), consumerKey, consumerRefId, targetTypeKey));
        if (!created) {
            row.reportAgain(request.versionId(), targetTypeKey);
        }
        row = usage.saveAndFlush(row);

        return new ReportUsageResponse(row.getId(), row.getTemplateId(), row.getVersionId(),
                row.getConsumerKey(), row.getConsumerRefId(), row.getTargetTypeKey(), row.getRecordedAt(), created);
    }

    /**
     * Which versions of a template still have consumers, oldest first, so the ones
     * most worth deprecating come first. Empty when nothing has been reported,
     * which is the usual case for a procedure nobody downstream has wired up yet.
     *
     * <p>The template is not looked up: the usage table has no row-level security,
     * so a property's procedure is counted from organization level even though the
     * procedure itself is hidden there, and an id with no reports is simply empty.
     */
    @Transactional(readOnly = true)
    public List<VersionUsageSummary> usageOf(UUID templateId) {
        return usage.countByVersion(templateId).stream()
                .map(c -> new VersionUsageSummary(c.getVersionNo(), c.getConsumerCount()))
                .toList();
    }
}
