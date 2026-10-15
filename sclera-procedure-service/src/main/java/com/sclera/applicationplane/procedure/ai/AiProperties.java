package com.sclera.applicationplane.procedure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for AI-assisted question generation (sclera.ai.*).
 *
 * The model is a deployment decision, not a code decision: {@code model} is the
 * default, and a request may name any model in {@code allowedModels} — the
 * allow-list is what stops a caller from routing generation to an arbitrary
 * (arbitrarily priced) model id.
 */
@ConfigurationProperties("sclera.ai")
public class AiProperties {

    private boolean enabled;
    private String apiKey;
    private String baseUrl = "https://openrouter.ai/api/v1";
    private String model = "nvidia/nemotron-3-super-120b-a12b:free";
    private List<String> allowedModels = List.of();
    private long maxOutputTokens = 8192;
    private int requestTimeoutSeconds = 120;
    private int maxRetries = 2;
    private int chunkChars = 24_000;
    private int maxChunks = 8;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<String> getAllowedModels() { return allowedModels; }
    public void setAllowedModels(List<String> allowedModels) { this.allowedModels = allowedModels; }

    public long getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(long maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public int getChunkChars() { return chunkChars; }
    public void setChunkChars(int chunkChars) { this.chunkChars = chunkChars; }

    public int getMaxChunks() { return maxChunks; }
    public void setMaxChunks(int maxChunks) { this.maxChunks = maxChunks; }
}
