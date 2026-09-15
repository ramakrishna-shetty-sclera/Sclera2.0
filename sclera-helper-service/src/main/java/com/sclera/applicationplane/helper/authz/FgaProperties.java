package com.sclera.applicationplane.helper.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenFGA connection settings (sclera.fga.* in application.yml).
 * The store is resolved by name at first use so local setups don't have to
 * copy the store id out of setup-openfga.ps1 into every service.
 */
@ConfigurationProperties(prefix = "sclera.fga")
public class FgaProperties {

    /** Master switch â€” when false every check allows and writes are no-ops. */
    private boolean enabled = true;

    /** OpenFGA HTTP API base URL. */
    private String apiUrl = "http://localhost:8085";

    /** Store looked up by name (created by setup-openfga.ps1). */
    private String storeName = "sclera";

    /** Explicit store id; skips the lookup by name when set. */
    private String storeId;

    /** Pin a model version; empty means "latest model in the store". */
    private String authorizationModelId;

    /**
     * How long check decisions are cached per user/relation/object (0 disables).
     * Role/tuple changes can take up to this long to be reflected.
     */
    private long checkCacheTtlSeconds = 30;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public String getAuthorizationModelId() { return authorizationModelId; }
    public void setAuthorizationModelId(String authorizationModelId) { this.authorizationModelId = authorizationModelId; }
    public long getCheckCacheTtlSeconds() { return checkCacheTtlSeconds; }
    public void setCheckCacheTtlSeconds(long checkCacheTtlSeconds) { this.checkCacheTtlSeconds = checkCacheTtlSeconds; }
}

