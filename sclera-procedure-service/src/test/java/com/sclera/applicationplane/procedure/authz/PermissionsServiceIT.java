package com.sclera.applicationplane.procedure.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclera.applicationplane.procedure.dto.PermissionsResponse;
import com.sclera.applicationplane.procedure.service.PermissionsService;
import com.sclera.applicationplane.procedure.support.AuthorizationModelFiles;
import com.sclera.controlplane.common.security.OrgContext;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each row of the four-role matrix is told it may do, against a real
 * OpenFGA.
 *
 * This is the only thing that can catch a typo in one of the four relation
 * names {@link PermissionsService} checks: they are plain strings passed to
 * {@code fga.checkOrg(...)}, not {@code @PreAuthorize} annotation values, so
 * {@code ControllerAuthorizationTest}'s reflection-based check of annotation
 * expressions against the model never sees them. A wrong name here fails
 * closed and silently — every permission false — which is the failure mode
 * this test exists to catch instead.
 */
@Testcontainers
class PermissionsServiceIT {

    private static final UUID ORG = UUID.randomUUID();

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID AUTHOR = UUID.randomUUID();
    private static final UUID PUBLISHER = UUID.randomUUID();
    private static final UUID RESULT_TYPE_MANAGER = UUID.randomUUID();
    private static final UUID SUPERVISOR = UUID.randomUUID();
    private static final UUID INSPECTOR = UUID.randomUUID();
    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID NO_GRANTS = UUID.randomUUID();

    /** The same image docker-compose runs. */
    @Container
    private static final GenericContainer<?> OPENFGA =
            new GenericContainer<>(DockerImageName.parse("openfga/openfga:v1.8.4"))
                    .withCommand("run")
                    .withExposedPorts(8080)
                    .waitingFor(Wait.forHttp("/healthz").forPort(8080));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static PermissionsService service;

    @BeforeAll
    static void loadModelAndGrants() throws Exception {
        String root = "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080);
        String storeId = post(root + "/stores", Map.of("name", "permissions-service-it")).path("id").asText();
        String store = root + "/stores/" + storeId;
        String modelId = post(store + "/authorization-models", AuthorizationModelFiles.jsonBytes())
                .path("authorization_model_id").asText();
        post(store + "/write", Map.of("authorization_model_id", modelId, "writes", Map.of("tuple_keys", List.of(
                tuple("user:" + ADMIN, "admin", "organization:" + ORG),
                tuple("user:" + AUTHOR, "template_author", "organization:" + ORG),
                tuple("user:" + PUBLISHER, "template_publisher", "organization:" + ORG),
                tuple("user:" + RESULT_TYPE_MANAGER, "result_type_manager", "organization:" + ORG),
                tuple("user:" + SUPERVISOR, "supervisor", "organization:" + ORG),
                tuple("user:" + INSPECTOR, "inspector", "organization:" + ORG),
                tuple("user:" + VIEWER, "viewer", "organization:" + ORG)))));

        FgaProperties properties = new FgaProperties();
        properties.setApiUrl(root);
        properties.setStoreId(storeId);
        properties.setCheckCacheTtlSeconds(0);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("openFgaClient", new OpenFgaClient(new ClientConfiguration().apiUrl(root)));
        FgaAuthorizationService fga = new FgaAuthorizationService(beans.getBeanProvider(OpenFgaClient.class), properties);
        service = new PermissionsService(fga);
    }

    @AfterEach
    void clearCaller() {
        OrgContext.clear();
    }

    @Test
    void anAdminMayDoEverything() {
        actAs(ADMIN);
        assertThat(service.forCurrentUser())
                .isEqualTo(new PermissionsResponse(true, true, true, true));
    }

    @Test
    void anAuthorMayAuthorButNotPublishOrManageResultTypes() {
        actAs(AUTHOR);
        assertThat(service.forCurrentUser())
                .isEqualTo(new PermissionsResponse(true, true, false, false));
    }

    @Test
    void aPublisherMayPublishButNotAuthor() {
        // The split the feature exists for: publishing and authoring are
        // separate rights, and this is the role that only holds one of them.
        actAs(PUBLISHER);
        assertThat(service.forCurrentUser())
                .isEqualTo(new PermissionsResponse(true, false, true, false));
    }

    @Test
    void aResultTypeManagerMayManageResultTypesButNotTemplates() {
        actAs(RESULT_TYPE_MANAGER);
        assertThat(service.forCurrentUser())
                .isEqualTo(new PermissionsResponse(true, false, false, true));
    }

    @Test
    void aSupervisorMayOnlyView() {
        actAs(SUPERVISOR);
        assertThat(service.forCurrentUser())
                .isEqualTo(new PermissionsResponse(true, false, false, false));
    }

    @Test
    void anInspectorMayOnlyView() {
        actAs(INSPECTOR);
        assertThat(service.forCurrentUser())
                .isEqualTo(new PermissionsResponse(true, false, false, false));
    }

    @Test
    void aViewerMayOnlyView() {
        actAs(VIEWER);
        assertThat(service.forCurrentUser())
                .isEqualTo(new PermissionsResponse(true, false, false, false));
    }

    @Test
    void someoneWithNoGrantsMayDoNothing() {
        actAs(NO_GRANTS);
        assertThat(service.forCurrentUser())
                .isEqualTo(new PermissionsResponse(false, false, false, false));
    }

    @Test
    void aPlatformAdminMayDoEverythingWithNoTuplesAtAll() {
        actAs(UUID.randomUUID());
        OrgContext.setPlatformAdmin(true);
        assertThat(service.forCurrentUser())
                .isEqualTo(new PermissionsResponse(true, true, true, true));
    }

    private static void actAs(UUID userId) {
        OrgContext.clear();
        OrgContext.setOrgId(ORG);
        OrgContext.setUserId(userId);
    }

    private static Map<String, String> tuple(String user, String relation, String object) {
        return Map.of("user", user, "relation", relation, "object", object);
    }

    private static JsonNode post(String url, Object body) throws Exception {
        byte[] bytes = body instanceof byte[] raw ? raw : JSON.writeValueAsBytes(body);
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s answered %s", url, response.body()).isBetween(200, 201);
        return JSON.readTree(response.body());
    }
}
