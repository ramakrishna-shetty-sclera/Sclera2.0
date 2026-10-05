package com.sclera.applicationplane.procedure.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclera.applicationplane.procedure.dto.PropertyResponse;
import com.sclera.applicationplane.procedure.service.PropertyAccessService;
import com.sclera.applicationplane.procedure.support.AuthorizationModelFiles;
import com.sclera.applicationplane.procedure.tenancy.PropertyLabels;
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
 * Which properties each kind of caller is offered, against a real OpenFGA.
 *
 * The list comes from reading the organization's {@code org} tuples and then
 * checking {@code can_view} on each, so this is the test that proves the read
 * is shaped right — that it finds this organization's properties and never
 * another's — and that the checks behind it drop the ones a caller may not
 * open. It drives the real FgaAuthorizationService, built the way the service
 * builds it, against the model file as checked in.
 */
@Testcontainers
class ViewablePropertiesIT {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final UUID VDMS001 = UUID.randomUUID();
    private static final UUID VDMS002 = UUID.randomUUID();
    private static final UUID OTHER_ORGS_PROPERTY = UUID.randomUUID();

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID INSPECTOR = UUID.randomUUID();
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

    private static FgaAuthorizationService fga;
    private static PropertyAccessService service;

    @BeforeAll
    static void loadModelAndGrants() throws Exception {
        String root = "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080);
        String storeId = post(root + "/stores", Map.of("name", "viewable-properties-it")).path("id").asText();
        String store = root + "/stores/" + storeId;
        String modelId = post(store + "/authorization-models", AuthorizationModelFiles.jsonBytes())
                .path("authorization_model_id").asText();
        post(store + "/write", Map.of("authorization_model_id", modelId, "writes", Map.of("tuple_keys", List.of(
                tuple("organization:" + ORG, "org", "property:" + VDMS001),
                tuple("organization:" + ORG, "org", "property:" + VDMS002),
                tuple("organization:" + OTHER_ORG, "org", "property:" + OTHER_ORGS_PROPERTY),
                tuple("user:" + ADMIN, "admin", "organization:" + ORG),
                tuple("user:" + INSPECTOR, "inspector", "organization:" + ORG),
                tuple("user:" + INSPECTOR, "viewer", "property:" + VDMS001)))));

        FgaProperties properties = new FgaProperties();
        properties.setApiUrl(root);
        properties.setStoreId(storeId);
        properties.setCheckCacheTtlSeconds(0);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("openFgaClient", new OpenFgaClient(new ClientConfiguration().apiUrl(root)));
        fga = new FgaAuthorizationService(beans.getBeanProvider(OpenFgaClient.class), properties);

        PropertyLabels labels = new PropertyLabels();
        labels.setLabels(Map.of(VDMS001.toString(), "VDMS001", VDMS002.toString(), "VDMS002"));
        service = new PropertyAccessService(fga, labels);
    }

    @AfterEach
    void clearCaller() {
        OrgContext.clear();
    }

    @Test
    void readsOnlyThisOrganizationsProperties() {
        actAs(ORG, ADMIN);
        assertThat(fga.orgObjects("property")).containsExactlyInAnyOrder(VDMS001, VDMS002);

        actAs(OTHER_ORG, UUID.randomUUID());
        assertThat(fga.orgObjects("property")).containsExactly(OTHER_ORGS_PROPERTY);
    }

    @Test
    void anOrganizationAdminIsOfferedEveryPropertyOfTheirOrganization() {
        actAs(ORG, ADMIN);
        assertThat(service.viewable()).containsExactly(
                new PropertyResponse(VDMS001, "VDMS001"),
                new PropertyResponse(VDMS002, "VDMS002"));
    }

    @Test
    void anInspectorIsOfferedOnlyThePropertyTheyWereGranted() {
        actAs(ORG, INSPECTOR);
        assertThat(service.viewable()).extracting(PropertyResponse::id).containsExactly(VDMS001);
    }

    @Test
    void aPlatformAdminIsOfferedEveryPropertyOfTheOrganizationTheyAreIn() {
        actAs(ORG, UUID.randomUUID());
        OrgContext.setPlatformAdmin(true);
        assertThat(service.viewable()).extracting(PropertyResponse::id).containsExactly(VDMS001, VDMS002);
    }

    @Test
    void someoneWithNoGrantsIsOfferedNothing() {
        actAs(ORG, NO_GRANTS);
        assertThat(service.viewable()).isEmpty();
    }

    private static void actAs(UUID orgId, UUID userId) {
        OrgContext.clear();
        OrgContext.setOrgId(orgId);
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
