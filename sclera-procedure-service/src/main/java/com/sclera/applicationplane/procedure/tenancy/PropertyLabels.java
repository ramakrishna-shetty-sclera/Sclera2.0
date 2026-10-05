package com.sclera.applicationplane.procedure.tenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Human-readable codes for properties, such as VDMS001 — a stand-in until a
 * property service owns them ({@code sclera.properties.labels}).
 *
 * The procedure service only ever holds a property's id. Nothing here grants
 * or refuses anything: a property with no label still works everywhere, it
 * just shows the first block of its id.
 */
@Component
@ConfigurationProperties(prefix = "sclera.properties")
public class PropertyLabels {

    /** Property id (as a UUID string) to its code. */
    private Map<String, String> labels = new HashMap<>();

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    /** The configured code, or the first block of the id when there is none. */
    public String codeFor(UUID propertyId) {
        String code = labels.get(propertyId.toString());
        return code != null ? code : propertyId.toString().substring(0, 8);
    }

    /** Every property that has a label. */
    public List<UUID> labelledIds() {
        return labels.keySet().stream().map(UUID::fromString).toList();
    }
}
