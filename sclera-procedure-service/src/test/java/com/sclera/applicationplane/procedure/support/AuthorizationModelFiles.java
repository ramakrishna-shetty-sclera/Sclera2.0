package com.sclera.applicationplane.procedure.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The OpenFGA authorization model as checked in under {@code docker/openfga},
 * for the tests that verify it.
 *
 * The model sits at the repository root, outside this module, and Maven runs
 * tests from the module directory, so the folder is found by walking up from
 * the working directory. If it cannot be found the test fails rather than
 * skips: a model test that quietly does not run is how a broken model ships.
 */
public final class AuthorizationModelFiles {

    private static final Pattern TYPE = Pattern.compile("^type (\\w+)\\s*$");
    private static final Pattern DEFINE = Pattern.compile("^\\s+define (\\w+):");

    private AuthorizationModelFiles() {
    }

    /** {@code authorization-model.json} — what setup-openfga.ps1 posts to OpenFGA. */
    public static Path json() {
        return dir().resolve("authorization-model.json");
    }

    /** {@code model.fga} — the human-readable mirror, kept in sync by hand. */
    public static Path dsl() {
        return dir().resolve("model.fga");
    }

    /** The JSON exactly as it is on disk, for posting unchanged. */
    public static byte[] jsonBytes() {
        try {
            return Files.readAllBytes(json());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode parsedJson() {
        try {
            return new ObjectMapper().readTree(json().toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Relation names per type, from each type's {@code relations} block. */
    public static Map<String, Set<String>> jsonRelations() {
        return namesPerType("relations");
    }

    /** Relation names per type, from each type's {@code metadata.relations} block. */
    public static Map<String, Set<String>> jsonMetadataRelations() {
        return namesPerType("metadata/relations");
    }

    /** Relation names per type, read from the {@code define} lines of model.fga. */
    public static Map<String, Set<String>> dslRelations() {
        Map<String, Set<String>> result = new TreeMap<>();
        String type = null;
        for (String line : readLines(dsl())) {
            Matcher t = TYPE.matcher(line);
            if (t.matches()) {
                type = t.group(1);
                result.put(type, new TreeSet<>());
                continue;
            }
            Matcher d = DEFINE.matcher(line);
            if (d.find() && type != null) {
                result.get(type).add(d.group(1));
            }
        }
        return result;
    }

    private static Map<String, Set<String>> namesPerType(String pointer) {
        Map<String, Set<String>> result = new TreeMap<>();
        for (JsonNode definition : parsedJson().path("type_definitions")) {
            Set<String> names = new TreeSet<>();
            definition.at("/" + pointer).fieldNames().forEachRemaining(names::add);
            result.put(definition.path("type").asText(), names);
        }
        return result;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path dir() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            Path candidate = p.resolve("docker").resolve("openfga");
            if (Files.isRegularFile(candidate.resolve("authorization-model.json"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "docker/openfga/authorization-model.json not found in " + start + " or any folder above it");
    }
}
