package com.sclera.applicationplane.helper.service;

import com.sclera.applicationplane.helper.domain.Asset;
import com.sclera.applicationplane.helper.domain.AssetType;
import com.sclera.applicationplane.helper.dto.AssetRequest;
import com.sclera.applicationplane.helper.dto.AssetResponse;
import com.sclera.applicationplane.helper.repository.AssetRepository;
import com.sclera.applicationplane.helper.repository.LocationRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class AssetService {

    private final AssetRepository repository;
    private final LocationRepository locationRepository;

    public AssetService(AssetRepository repository, LocationRepository locationRepository) {
        this.repository = repository;
        this.locationRepository = locationRepository;
    }

    public AssetResponse create(AssetRequest request) {
        UUID orgId = OrgContext.getOrgId();
        String ip = normalizeAndValidate(request, orgId);

        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setOrgId(orgId);
        asset.setCreatedBy(OrgContext.getUserId());
        asset.setName(request.name());
        asset.setAssetType(request.assetType());
        asset.setIpAddress(ip);
        asset.setLocationId(request.locationId());
        OffsetDateTime now = OffsetDateTime.now();
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        return toResponse(repository.save(asset));
    }

    public List<AssetResponse> list(AssetType assetType, UUID locationId, Boolean untagged) {
        UUID orgId = OrgContext.getOrgId();
        return repository.findAllByOrgId(orgId).stream()
                .filter(a -> assetType == null || a.getAssetType() == assetType)
                .filter(a -> locationId == null || locationId.equals(a.getLocationId()))
                .filter(a -> untagged == null || (untagged ? a.getLocationId() == null : a.getLocationId() != null))
                .sorted(Comparator.comparing(Asset::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toResponse)
                .toList();
    }

    public AssetResponse get(UUID id) {
        return toResponse(getOwned(id));
    }

    public AssetResponse update(UUID id, AssetRequest request) {
        Asset asset = getOwned(id);
        String ip = normalizeAndValidate(request, asset.getOrgId());
        asset.setName(request.name());
        asset.setAssetType(request.assetType());
        asset.setIpAddress(ip);
        asset.setLocationId(request.locationId());
        asset.setUpdatedAt(OffsetDateTime.now());
        return toResponse(repository.save(asset));
    }

    public void delete(UUID id) {
        repository.delete(getOwned(id));
    }

    private Asset getOwned(UUID id) {
        return repository.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Asset not found: " + id));
    }

    /** Enforces the IP/NON_IP address rule and that any locationId exists. Returns the cleaned ip. */
    private String normalizeAndValidate(AssetRequest request, UUID orgId) {
        String ip = request.ipAddress() != null && !request.ipAddress().isBlank()
                ? request.ipAddress().trim() : null;
        if (request.assetType() == AssetType.IP && ip == null) {
            throw new BusinessRuleException("An IP asset requires an ipAddress");
        }
        if (request.assetType() == AssetType.NON_IP && ip != null) {
            throw new BusinessRuleException("A NON_IP asset must not have an ipAddress");
        }
        if (request.locationId() != null
                && locationRepository.findByIdAndOrgId(request.locationId(), orgId).isEmpty()) {
            throw new BusinessRuleException("Location not found: " + request.locationId());
        }
        return ip;
    }

    private AssetResponse toResponse(Asset a) {
        return new AssetResponse(
                a.getId(), a.getOrgId(), a.getName(), a.getAssetType(), a.getIpAddress(),
                a.getLocationId(), a.getCreatedBy(), a.getCreatedAt(), a.getUpdatedAt());
    }
}
