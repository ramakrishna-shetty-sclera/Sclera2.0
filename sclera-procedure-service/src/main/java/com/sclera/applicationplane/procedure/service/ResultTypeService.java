package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.domain.ResultType;
import com.sclera.applicationplane.procedure.dto.ResultTypeRequest;
import com.sclera.applicationplane.procedure.dto.ResultTypeResponse;
import com.sclera.applicationplane.procedure.dto.ResultTypeUpdateRequest;
import com.sclera.applicationplane.procedure.mapper.ResultTypeMapper;
import com.sclera.applicationplane.procedure.repository.ResultTypeRepository;
import com.sclera.applicationplane.procedure.repository.VersionResultTypeRefRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ConflictException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages the organization's vocabulary of outcomes. Procedure versions map
 * answers to these by key, and a published version is permanent, so a type a
 * published version names can be deactivated but no longer deleted — see
 * {@link #delete}.
 */
@Service
@Transactional
public class ResultTypeService {

    private final ResultTypeRepository repository;
    private final ResultTypeMapper mapper;
    private final VersionResultTypeRefRepository refs;

    public ResultTypeService(ResultTypeRepository repository, ResultTypeMapper mapper,
                             VersionResultTypeRefRepository refs) {
        this.repository = repository;
        this.mapper = mapper;
        this.refs = refs;
    }

    public ResultTypeResponse create(ResultTypeRequest request) {
        UUID orgId = OrgContext.getOrgId();
        if (repository.existsByOrgIdAndKeyIgnoreCase(orgId, request.key())) {
            throw new ConflictException("A result type with key '" + request.key() + "' already exists");
        }

        List<ResultType> existing = repository.findAllByOrgIdOrderBySeverityOrderAsc(orgId);
        int append = existing.size() + 1;
        int target = request.severityOrder() == null ? append : request.severityOrder();
        if (target > append) {
            throw new BusinessRuleException(
                    "severityOrder must be between 1 and " + append + "; ranks are contiguous");
        }

        // Inserting in the middle pushes everything at or below the target down
        // one, so ranks stay contiguous. Safe to do before the insert: nothing
        // outside this table stores the number, and it is not unique-constrained.
        existing.stream()
                .filter(rt -> rt.getSeverityOrder() >= target)
                .forEach(rt -> rt.setSeverityOrder(rt.getSeverityOrder() + 1));

        ResultType resultType = new ResultType();
        resultType.setOrgId(orgId);
        mapper.applyCreate(resultType, request);
        resultType.setSeverityOrder(target);
        resultType.setSystem(false);

        return mapper.toResponse(repository.save(resultType));
    }

    public ResultTypeResponse update(UUID id, ResultTypeUpdateRequest request) {
        // Allowed on system types too: renaming Pass to "Compliant" is a
        // labelling choice, and the key it is stored under does not move.
        ResultType resultType = getOwned(id);
        mapper.applyUpdate(resultType, request);
        return mapper.toResponse(resultType);
    }

    /**
     * Rewrites every rank to 1..n in the order given, most severe first.
     * Wholesale rather than incremental because ranks are dense and nothing
     * outside this table stores the number.
     */
    public List<ResultTypeResponse> reorder(List<UUID> orderedIds) {
        UUID orgId = OrgContext.getOrgId();
        Map<UUID, ResultType> byId = repository.findAllByOrgIdOrderBySeverityOrderAsc(orgId).stream()
                .collect(Collectors.toMap(ResultType::getId, rt -> rt));

        if (new HashSet<>(orderedIds).size() != orderedIds.size()) {
            throw new BusinessRuleException("Reorder list contains duplicate ids");
        }
        if (!new HashSet<>(orderedIds).equals(byId.keySet())) {
            throw new BusinessRuleException(
                    "Reorder list must contain every result type in the organization, exactly once");
        }

        List<ResultTypeResponse> reordered = new ArrayList<>(orderedIds.size());
        for (int i = 0; i < orderedIds.size(); i++) {
            ResultType resultType = byId.get(orderedIds.get(i));
            resultType.setSeverityOrder(i + 1);
            reordered.add(mapper.toResponse(resultType));
        }
        return reordered;
    }

    public ResultTypeResponse deactivate(UUID id) {
        ResultType resultType = getOwned(id);
        if (resultType.isSystem()) {
            throw new BusinessRuleException("Pass and Fail cannot be deactivated");
        }
        resultType.setActive(false);
        return mapper.toResponse(resultType);
    }

    public ResultTypeResponse activate(UUID id) {
        ResultType resultType = getOwned(id);
        resultType.setActive(true);
        return mapper.toResponse(resultType);
    }

    /**
     * Hard delete, refused in two cases. Pass and Fail belong to the system and
     * never go. A type that any published version names is refused as well:
     * those versions are the record of what past inspections decided, and
     * deleting the type they point at would leave that record unreadable.
     *
     * Deactivating is the way out, and the refusal says so. It stops new
     * versions using the type while every version that already names it keeps
     * rendering — which is why only delete is guarded and deactivate is not.
     */
    public void delete(UUID id) {
        ResultType resultType = getOwned(id);
        if (resultType.isSystem()) {
            throw new BusinessRuleException("Pass and Fail cannot be deleted");
        }
        // Counts every published version in the organization, whatever
        // property it belongs to: the index carries no row-level security.
        long usedBy = refs.countByResultTypeKey(resultType.getKey());
        if (usedBy > 0) {
            throw new BusinessRuleException(resultType.getName() + " is used by " + usedBy
                    + (usedBy == 1 ? " published version" : " published versions")
                    + ", so it cannot be deleted; deactivate it instead");
        }
        UUID orgId = resultType.getOrgId();
        repository.delete(resultType);

        // Close the gap the delete leaves. Ranks have to stay contiguous: both
        // create and the bounds check on an explicit rank derive the end of the
        // list from the row count, so a hole would let a new type be given a
        // rank that is still in use.
        repository.flush();
        List<ResultType> remaining = repository.findAllByOrgIdOrderBySeverityOrderAsc(orgId);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setSeverityOrder(i + 1);
        }
    }

    @Transactional(readOnly = true)
    public ResultTypeResponse get(UUID id) {
        return mapper.toResponse(getOwned(id));
    }

    /** Ordered most severe first. {@code active} null returns both active and inactive. */
    @Transactional(readOnly = true)
    public List<ResultTypeResponse> list(Boolean active) {
        UUID orgId = OrgContext.getOrgId();
        List<ResultType> found = active == null
                ? repository.findAllByOrgIdOrderBySeverityOrderAsc(orgId)
                : repository.findAllByOrgIdAndActiveOrderBySeverityOrderAsc(orgId, active);
        return found.stream().map(mapper::toResponse).toList();
    }

    private ResultType getOwned(UUID id) {
        return repository.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Result type not found: " + id));
    }
}
