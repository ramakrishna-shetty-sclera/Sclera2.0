package com.sclera.applicationplane.procedure.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Turns a {@link DefinitionDocument} into its one canonical byte form and the
 * SHA-256 of those bytes. Equal content always gives equal bytes, so the hash
 * identifies the content: publishing an unchanged draft finds its own hash and
 * becomes a no-op.
 *
 * The canonical form:
 * <ul>
 *   <li>one line, no insignificant whitespace;</li>
 *   <li>object keys sorted alphabetically, at every depth;</li>
 *   <li>array order preserved — sibling order is meaningful;</li>
 *   <li>strings trimmed and Unicode-normalised (NFC);</li>
 *   <li>empty values omitted: null, blank strings, {@code false}, empty
 *       arrays and empty objects. A reader treats a missing field as its
 *       empty value, so every new boolean field must default to false.</li>
 * </ul>
 *
 * It uses its own ObjectMapper so that nothing configured on the application's
 * shared mapper can change the stored bytes.
 */
@Component
public class DefinitionCanonicalizer {

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Canonical bytes, their hash, and the document as re-read from those bytes. */
    public record Canonical(String json, String hash, DefinitionDocument document) {}

    public Canonical canonicalize(DefinitionDocument document) {
        DefinitionDocument withSchema = new DefinitionDocument(
                DefinitionDocument.CURRENT_SCHEMA, document.items(), document.thresholds(),
                document.targetTypes(), document.documents());
        JsonNode tree = normalize(mapper.valueToTree(withSchema));
        String json = write(tree == null ? JsonNodeFactory.instance.objectNode() : tree);
        return new Canonical(json, sha256Hex(json), parse(json));
    }

    /** Reads stored canonical bytes. Unknown fields are rejected, not ignored. */
    public DefinitionDocument parse(String json) {
        try {
            return mapper.readValue(json, DefinitionDocument.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored procedure definition is not readable", e);
        }
    }

    public static String sha256Hex(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Returns the normalised node, or null if the node is empty and must be omitted. */
    private static JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String text = Normalizer.normalize(node.textValue(), Normalizer.Form.NFC).strip();
            return text.isEmpty() ? null : TextNode.valueOf(text);
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? node : null;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : node) {
                JsonNode normalized = normalize(element);
                if (normalized != null) {
                    out.add(normalized);
                }
            }
            return out.isEmpty() ? null : out;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(null);
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                JsonNode normalized = normalize(node.get(name));
                if (normalized != null) {
                    out.set(name, normalized);
                }
            }
            return out.isEmpty() ? null : out;
        }
        return node; // numbers
    }

    private String write(JsonNode tree) {
        try {
            return mapper.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise procedure definition", e);
        }
    }
}
