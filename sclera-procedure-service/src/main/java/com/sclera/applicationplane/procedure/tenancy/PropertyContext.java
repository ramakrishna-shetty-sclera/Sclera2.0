package com.sclera.applicationplane.procedure.tenancy;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The properties (VDMS) the current request may see.
 *
 * Sits beside {@link TenantContext}: that one decides which organization's
 * schema a connection points at, this one decides which rows inside it are
 * visible. Both are read when a connection is checked out of the pool.
 *
 * Empty means organization level — only procedures shared across the whole
 * organization are visible, which is what the library screen shows before a
 * property is opened.
 *
 * Nothing here authorises anything. The list is written by the filter that has
 * already checked the caller's grants; this only carries it to the connection.
 */
public final class PropertyContext {

    private static final ThreadLocal<List<UUID>> SELECTED = new ThreadLocal<>();

    private PropertyContext() {}

    public static void set(List<UUID> propertyIds) {
        SELECTED.set(propertyIds == null ? List.of() : List.copyOf(propertyIds));
    }

    public static List<UUID> current() {
        List<UUID> ids = SELECTED.get();
        return ids == null ? List.of() : ids;
    }

    public static void clear() {
        SELECTED.remove();
    }

    /**
     * The value handed to Postgres: comma-separated ids, or empty for
     * organization level.
     *
     * Empty is deliberate rather than leaving the setting unset. An unset
     * setting and an empty one both hide every property row — verified — but a
     * pooled connection that is never told would keep whatever the previous
     * request left on it. Always writing a value is what makes that impossible.
     */
    public static String asSetting() {
        return current().stream().map(UUID::toString).collect(Collectors.joining(","));
    }
}
