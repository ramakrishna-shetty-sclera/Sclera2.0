package com.sclera.applicationplane.helper.service;

import com.sclera.applicationplane.helper.domain.Location;
import com.sclera.applicationplane.helper.domain.LocationType;
import com.sclera.applicationplane.helper.dto.LocationRequest;
import com.sclera.applicationplane.helper.dto.LocationResponse;
import com.sclera.applicationplane.helper.repository.AssetRepository;
import com.sclera.applicationplane.helper.repository.LocationRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class LocationService {

    private final LocationRepository repository;
    private final AssetRepository assetRepository;

    public LocationService(LocationRepository repository, AssetRepository assetRepository) {
        this.repository = repository;
        this.assetRepository = assetRepository;
    }

    public LocationResponse create(LocationRequest request) {
        UUID orgId = OrgContext.getOrgId();
        validateHierarchy(request.type(), request.parentId(), orgId);

        Location location = new Location();
        location.setId(UUID.randomUUID());
        location.setOrgId(orgId);
        location.setCreatedBy(OrgContext.getUserId());
        location.setName(request.name());
        location.setType(request.type());
        location.setParentId(request.parentId());
        OffsetDateTime now = OffsetDateTime.now();
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        return toResponse(repository.save(location));
    }

    public List<LocationResponse> list(LocationType type, UUID parentId) {
        UUID orgId = OrgContext.getOrgId();
        return repository.findAllByOrgId(orgId).stream()
                .filter(l -> type == null || l.getType() == type)
                .filter(l -> parentId == null || parentId.equals(l.getParentId()))
                .sorted(Comparator.comparing(Location::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toResponse)
                .toList();
    }

    public LocationResponse get(UUID id) {
        return toResponse(getOwned(id));
    }

    public LocationResponse update(UUID id, LocationRequest request) {
        Location location = getOwned(id);
        validateHierarchy(request.type(), request.parentId(), location.getOrgId());
        if (request.parentId() != null && request.parentId().equals(id)) {
            throw new BusinessRuleException("A location cannot be its own parent");
        }
        location.setName(request.name());
        location.setType(request.type());
        location.setParentId(request.parentId());
        location.setUpdatedAt(OffsetDateTime.now());
        return toResponse(repository.save(location));
    }

    public void delete(UUID id) {
        Location location = getOwned(id);
        UUID orgId = location.getOrgId();
        if (repository.existsByParentIdAndOrgId(id, orgId)) {
            throw new BusinessRuleException("Cannot delete a location that still has child locations");
        }
        if (assetRepository.existsByLocationIdAndOrgId(id, orgId)) {
            throw new BusinessRuleException("Cannot delete a location that still has assets tagged to it");
        }
        repository.delete(location);
    }

    private Location getOwned(UUID id) {
        return repository.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Location not found: " + id));
    }

    private void validateHierarchy(LocationType type, UUID parentId, UUID orgId) {
        if (type == LocationType.BUILDING) {
            if (parentId != null) {
                throw new BusinessRuleException("A BUILDING must not have a parent");
            }
            return;
        }
        if (parentId == null) {
            throw new BusinessRuleException(type + " requires a parentId");
        }
        Location parent = repository.findByIdAndOrgId(parentId, orgId)
                .orElseThrow(() -> new BusinessRuleException("Parent location not found: " + parentId));
        LocationType required = (type == LocationType.FLOOR) ? LocationType.BUILDING : LocationType.FLOOR;
        if (parent.getType() != required) {
            throw new BusinessRuleException(type + " must have a " + required + " parent (got " + parent.getType() + ")");
        }
    }

    private LocationResponse toResponse(Location l) {
        return new LocationResponse(
                l.getId(), l.getOrgId(), l.getName(), l.getType(), l.getParentId(),
                l.getCreatedBy(), l.getCreatedAt(), l.getUpdatedAt());
    }
}
