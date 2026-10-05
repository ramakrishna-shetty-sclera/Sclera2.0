package com.sclera.applicationplane.procedure.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclera.applicationplane.procedure.support.AuthorizationModelFiles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authorization model as OpenFGA actually evaluates it.
 *
 * AuthorizationModelTest checks that the files agree with each other; this
 * checks that they mean what the role matrix says. It starts a throwaway
 * OpenFGA — {@code run} keeps its store in memory, so no database is needed —
 * posts authorization-model.json byte for byte, the way setup-openfga.ps1 does,
 * gives each test user exactly one role, and asks the questions the services ask.
 *
 * Posting the raw file matters: nothing else proves OpenFGA accepts the model
 * at all, and re-serialising it first could hide the very defect being looked for.
 */
@Testcontainers
class AuthorizationModelIT {

    private static final String ORG = "organization:org1";
    private static final String TEMPLATE = "procedure_template:t1";
    private static final String PROPERTY_A = "property:p1";
    private static final String PROPERTY_B = "property:p2";

    /** Every role a user can hold in an organization; test user "user:<role>" holds that one only. */
    private static final List<String> ROLES = List.of(
            "admin", "viewer", "inspector", "supervisor", "template_author",
            "template_publisher", "template_importer", "result_type_manager", "asset_manager");

    /** The same image docker-compose runs. */
    @Container
    private static final GenericContainer<?> OPENFGA =
            new GenericContainer<>(DockerImageName.parse("openfga/openfga:v1.8.4"))
                    .withCommand("run")
                    .withExposedPorts(8080)
                    .waitingFor(Wait.forHttp("/healthz").forPort(8080));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String store;
    private static String modelId;

    @BeforeAll
    static void loadModelAndRoles() throws Exception {
        String root = "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080);
        String storeId = post(root + "/stores", Map.of("name", "authorization-model-it"), 201).path("id").asText();
        store = root + "/stores/" + storeId;
        modelId = post(store + "/authorization-models", AuthorizationModelFiles.jsonBytes(), 201)
                .path("authorization_model_id").asText();

        List<Map<String, String>> tuples = new ArrayList<>();
        for (String role : ROLES) {
            tuples.add(tuple("user:" + role, role, ORG));
        }
        tuples.add(tuple(ORG, "org", TEMPLATE));
        tuples.add(tuple(ORG, "org", PROPERTY_A));
        tuples.add(tuple(ORG, "org", PROPERTY_B));
        // Granted one property and no organization role at all.
        tuples.add(tuple("user:property_viewer", "viewer", PROPERTY_A));
        post(store + "/write", Map.of("authorization_model_id", modelId,
                "writes", Map.of("tuple_keys", tuples)), 200);
    }

    @Test
    void openFgaAcceptsTheModelFileAsItIs() {
        assertThat(modelId).isNotBlank();
    }

    @ParameterizedTest(name = "{0} {1} on {2} -> {3}")
    @MethodSource("roleMatrix")
    void answersAsTheRoleMatrixSays(String user, String relation, String object, boolean expected) throws Exception {
        JsonNode answer = post(store + "/check", Map.of("authorization_model_id", modelId,
                "tuple_key", tuple("user:" + user, relation, object)), 200);
        assertThat(answer.path("allowed").asBoolean()).isEqualTo(expected);
    }

    static Stream<Arguments> roleMatrix() {
        return Stream.of(
                // The split this branch exists for: drafting and publishing are different rights.
                allow("template_author", "can_manage_templates", ORG),
                deny("template_author", "can_publish_templates", ORG),
                allow("template_author", "can_edit", TEMPLATE),
                deny("template_author", "can_publish", TEMPLATE),
                allow("template_publisher", "can_publish_templates", ORG),
                allow("template_publisher", "can_publish", TEMPLATE),
                deny("template_publisher", "can_manage_templates", ORG),
                deny("template_publisher", "can_edit", TEMPLATE),

                allow("template_importer", "can_import_templates", ORG),
                deny("template_importer", "can_manage_templates", ORG),
                allow("result_type_manager", "can_manage_result_types", ORG),
                deny("result_type_manager", "can_manage_templates", ORG),

                // Supervisor reviews; running checklists comes from inspector, not from this role.
                allow("supervisor", "can_review", ORG),
                deny("supervisor", "can_manage_inspections", ORG),
                deny("supervisor", "can_manage_templates", ORG),
                deny("supervisor", "can_publish_templates", ORG),

                // Every role can read — the member trap. Each of these is a 403 on a GET if member is incomplete.
                allow("template_author", "can_view", ORG),
                allow("template_publisher", "can_view", ORG),
                allow("template_publisher", "can_view", TEMPLATE),
                allow("template_importer", "can_view", ORG),
                allow("result_type_manager", "can_view", ORG),
                allow("supervisor", "can_view", ORG),
                allow("supervisor", "can_view", TEMPLATE),
                allow("asset_manager", "can_view", ORG),
                allow("viewer", "can_view", ORG),

                // The roles that existed before get none of the four new rights.
                allow("inspector", "can_manage_inspections", ORG),
                deny("inspector", "can_review", ORG),
                deny("inspector", "can_publish_templates", ORG),
                deny("inspector", "can_import_templates", ORG),
                deny("inspector", "can_manage_result_types", ORG),
                deny("viewer", "can_review", ORG),
                deny("viewer", "can_publish_templates", ORG),
                deny("viewer", "can_import_templates", ORG),
                deny("viewer", "can_manage_result_types", ORG),
                deny("asset_manager", "can_manage_result_types", ORG),

                // Admin holds all of them.
                allow("admin", "can_review", ORG),
                allow("admin", "can_publish_templates", ORG),
                allow("admin", "can_import_templates", ORG),
                allow("admin", "can_manage_result_types", ORG),
                allow("admin", "can_publish", TEMPLATE),

                // Properties are untouched by this branch and must stay that way.
                allow("admin", "can_view", PROPERTY_B),
                allow("property_viewer", "can_view", PROPERTY_A),
                deny("property_viewer", "can_view", PROPERTY_B),
                deny("inspector", "can_view", PROPERTY_A),

                // No tuples, no access.
                deny("outsider", "can_view", ORG));
    }

    private static Arguments allow(String user, String relation, String object) {
        return Arguments.of(user, relation, object, true);
    }

    private static Arguments deny(String user, String relation, String object) {
        return Arguments.of(user, relation, object, false);
    }

    private static Map<String, String> tuple(String user, String relation, String object) {
        return Map.of("user", user, "relation", relation, "object", object);
    }

    private static JsonNode post(String url, Object body, int expectedStatus) throws Exception {
        byte[] bytes = body instanceof byte[] raw ? raw : JSON.writeValueAsBytes(body);
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("POST %s answered %s", url, response.body())
                .isEqualTo(expectedStatus);
        return JSON.readTree(response.body());
    }
}
