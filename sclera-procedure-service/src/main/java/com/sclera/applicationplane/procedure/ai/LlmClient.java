package com.sclera.applicationplane.procedure.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.sclera.applicationplane.procedure.domain.ChatRole;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place this service talks to the LLM provider. Every generation —
 * document or chat — goes through {@link #complete}, which is where retries,
 * the timeout, structured output and per-call token/latency logging live.
 *
 * The provider is any OpenAI-compatible chat-completions endpoint
 * (sclera.ai.base-url; OpenRouter by default), so swapping providers or models
 * is a config decision, not a code change. The model is resolved per call:
 * callers pass an override (bounded by sclera.ai.allowed-models) or null for
 * the configured default.
 *
 * Structured output: the JSON schema is derived from the requested class
 * (Jackson annotations included) and sent two ways — embedded in the system
 * prompt and as response_format json_object — then the reply is parsed into
 * the class. Unlike Anthropic structured outputs this is not hard-constrained,
 * so a malformed reply fails the call; semantic validation of the content is
 * CandidateMapper's job, never skipped.
 */
@Component
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final AiProperties properties;
    private final ObjectMapper mapper;
    private final SchemaGenerator schemaGenerator;
    private final Map<Class<?>, String> schemaCache = new ConcurrentHashMap<>();
    private volatile HttpClient http;

    public LlmClient(AiProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.schemaGenerator = new SchemaGenerator(
                new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        .with(new JacksonModule())
                        .build());
    }

    /** One conversation turn. Document generation sends a single USER turn. */
    public record Turn(ChatRole role, String text) {
    }

    /** The parsed value plus what the call cost — callers persist/log usage per job. */
    public record Result<T>(T value, String model, long inputTokens, long outputTokens, long latencyMs) {
    }

    public <T> Result<T> complete(String modelOverride, String system, List<Turn> turns, Class<T> schema) {
        String model = resolveModel(modelOverride);
        String requestBody = buildRequest(model, system, turns, schema);

        long started = System.nanoTime();
        int attempts = Math.max(1, properties.getMaxRetries() + 1);
        for (int attempt = 1; ; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create(properties.getBaseUrl() + "/chat/completions"))
                        .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                        .header("Authorization", "Bearer " + apiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                HttpResponse<String> response = ready().send(request, HttpResponse.BodyHandlers.ofString());

                if ((response.statusCode() == 429 || response.statusCode() >= 500) && attempt < attempts) {
                    log.warn("LLM call got HTTP {} (attempt {}/{}), retrying", response.statusCode(), attempt, attempts);
                    backoff(attempt);
                    continue;
                }
                if (response.statusCode() != 200) {
                    throw new BusinessRuleException("AI generation failed: " + errorMessage(response));
                }
                return parse(response.body(), model, schema, started, turns.size());
            } catch (IOException e) {
                if (attempt < attempts) {
                    log.warn("LLM call IO failure (attempt {}/{}): {}", attempt, attempts, e.getMessage());
                    backoff(attempt);
                    continue;
                }
                long latencyMs = (System.nanoTime() - started) / 1_000_000;
                log.error("LLM call failed: model={} schema={} latencyMs={}: {}",
                        model, schema.getSimpleName(), latencyMs, e.getMessage());
                throw new BusinessRuleException("AI generation failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessRuleException("AI generation was interrupted");
            }
        }
    }

    /** Default or override, bounded by the allow-list. Public so callers can validate eagerly. */
    public String resolveModel(String modelOverride) {
        if (modelOverride == null || modelOverride.isBlank()) {
            return properties.getModel();
        }
        String model = modelOverride.strip();
        List<String> allowed = properties.getAllowedModels();
        if (!allowed.isEmpty() && !allowed.contains(model)) {
            throw new ValidationException("Model '" + model + "' is not in sclera.ai.allowed-models");
        }
        return model;
    }

    private <T> String buildRequest(String model, String system, List<Turn> turns, Class<T> schema) {
        String schemaJson = schemaCache.computeIfAbsent(schema,
                c -> schemaGenerator.generateSchema(c).toString());
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", properties.getMaxOutputTokens());
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", system
                        + "\n\nRespond with a single JSON object only — no markdown fences, no commentary —"
                        + " conforming to this JSON Schema:\n" + schemaJson);
        for (Turn turn : turns) {
            messages.addObject()
                    .put("role", turn.role() == ChatRole.ASSISTANT ? "assistant" : "user")
                    .put("content", turn.text());
        }
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new BusinessRuleException("AI request could not be serialized: " + e.getMessage());
        }
    }

    private <T> Result<T> parse(String responseBody, String model, Class<T> schema, long started, int turnCount) {
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        try {
            JsonNode root = mapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new BusinessRuleException("The AI returned no usable content");
            }
            T value = mapper.readValue(stripFences(content), schema);
            long inputTokens = root.path("usage").path("prompt_tokens").asLong();
            long outputTokens = root.path("usage").path("completion_tokens").asLong();
            log.info("LLM call ok: model={} schema={} turns={} latencyMs={} inputTokens={} outputTokens={}",
                    model, schema.getSimpleName(), turnCount, latencyMs, inputTokens, outputTokens);
            return new Result<>(value, model, inputTokens, outputTokens, latencyMs);
        } catch (JsonProcessingException e) {
            log.error("LLM reply was not valid JSON for schema {}: {}", schema.getSimpleName(), e.getMessage());
            throw new BusinessRuleException("AI generation failed: the model returned malformed JSON");
        }
    }

    /** Models occasionally wrap JSON in ``` fences despite instructions; tolerate it. */
    private static String stripFences(String content) {
        String trimmed = content.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }

    private static String errorMessage(HttpResponse<String> response) {
        String body = response.body();
        try {
            JsonNode error = new ObjectMapper().readTree(body).path("error");
            String message = error.path("message").asText(null);
            if (message != null) {
                return "HTTP " + response.statusCode() + ": " + message;
            }
        } catch (JsonProcessingException ignored) {
            // fall through to the raw body
        }
        return "HTTP " + response.statusCode() + ": " + (body == null ? "" : body);
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(1000L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessRuleException("AI generation was interrupted");
        }
    }

    private String apiKey() {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessRuleException(
                    "AI question generation is enabled but no API key is set (set OPENROUTER_API_KEY)");
        }
        return apiKey;
    }

    /** Built lazily so the service starts (flag on, key absent) and fails only when used. */
    private HttpClient ready() {
        HttpClient current = http;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (http == null) {
                http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
            }
            return http;
        }
    }
}
