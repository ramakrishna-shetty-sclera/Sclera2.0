package com.sclera.applicationplane.procedure.client.vocabulary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclera.controlplane.common.dapr.DaprInvocationHelper;
import com.sclera.controlplane.common.exception.ExternalServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DaprVocabularyClientTest {

    private final DaprInvocationHelper dapr = mock(DaprInvocationHelper.class);
    private final VocabularyProperties properties = new VocabularyProperties();
    private final DaprVocabularyClient client = new DaprVocabularyClient(dapr, properties);
    private final UUID org = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @SuppressWarnings("unchecked")
    private void answers(Map<VocabularyKind, List<VocabularyEntry>> lists) {
        when(dapr.invokeGet(any(String.class), any(String.class), any(TypeReference.class))).thenReturn(lists);
    }

    @SuppressWarnings("unchecked")
    private void fails(RuntimeException failure) {
        when(dapr.invokeGet(any(String.class), any(String.class), any(TypeReference.class))).thenThrow(failure);
    }

    @Test
    void theCallHasNoLeadingSlashNoQueryAndTheOrganizationInThePath() {
        answers(Map.of());

        client.fetch(org);

        ArgumentCaptor<String> method = ArgumentCaptor.forClass(String.class);
        verify(dapr).invokeGet(eq("sclera-helper-service"), method.capture(), any(TypeReference.class));
        // Dapr rejects '?' in a method name and the helper has no query overload.
        assertThat(method.getValue()).isEqualTo("internal/api/v1/vocabulary/orgs/" + org)
                .doesNotStartWith("/").doesNotContain("?");
    }

    @Test
    void theAppIdIsConfigurationNotAConstant() {
        properties.setAppId("sclera-property-service");
        answers(Map.of());

        client.fetch(org);

        verify(dapr).invokeGet(eq("sclera-property-service"), any(String.class), any(TypeReference.class));
    }

    @Test
    void theAnswerBecomesAVocabulary() {
        answers(Map.of(VocabularyKind.ASSET_CLASS, List.of(new VocabularyEntry("EXTINGUISHER", "Fire extinguisher", 1, true))));

        Vocabulary vocabulary = client.fetch(org);

        assertThat(vocabulary.contains(VocabularyKind.ASSET_CLASS, "EXTINGUISHER")).isTrue();
        assertThat(vocabulary.entries(VocabularyKind.HIERARCHY_LEVEL)).isEmpty();
    }

    @Test
    void theHelpersJsonParsesIntoTheSameShape() throws Exception {
        // The exact shape InternalVocabularyController returns: kinds as keys,
        // entries with key, name, displayOrder and active.
        String json = "{\"HIERARCHY_LEVEL\":[{\"key\":\"BUILDING\",\"name\":\"Building\",\"displayOrder\":1,\"active\":true}],"
                + "\"LOCATION_TYPE\":[],\"ASSET_CLASS\":[{\"key\":\"HOSE_REEL\",\"name\":\"Hose reel\",\"displayOrder\":2,\"active\":false}],"
                + "\"ASSET_TAG\":[]}";

        Map<VocabularyKind, List<VocabularyEntry>> parsed = new ObjectMapper().readValue(json, DaprVocabularyClient.LISTS);

        Vocabulary vocabulary = new Vocabulary(parsed);
        assertThat(vocabulary.contains(VocabularyKind.HIERARCHY_LEVEL, "BUILDING")).isTrue();
        assertThat(vocabulary.contains(VocabularyKind.ASSET_CLASS, "HOSE_REEL")).isTrue();
        assertThat(vocabulary.isActive(VocabularyKind.ASSET_CLASS, "HOSE_REEL")).isFalse();
    }

    @Test
    void aFailedCallIsUnavailableNeverAnEmptyVocabulary() {
        // An empty vocabulary would read as "no such key", and tell an author to
        // fix a procedure that is fine.
        fails(new ExternalServiceException("sclera-helper-service", "Dapr invocation failed"));

        assertThatThrownBy(() -> client.fetch(org))
                .isInstanceOf(VocabularyUnavailableException.class)
                .hasMessage("The vocabulary service 'sclera-helper-service' could not be reached")
                .hasCauseInstanceOf(ExternalServiceException.class);
    }

    @Test
    void anyOtherFailureIsUnavailableToo() {
        fails(new IllegalStateException("unreadable body"));

        assertThatThrownBy(() -> client.fetch(org)).isInstanceOf(VocabularyUnavailableException.class);
    }

    @Test
    void aNullAnswerIsUnavailableNotEmpty() {
        answers(null);

        assertThatThrownBy(() -> client.fetch(org))
                .isInstanceOf(VocabularyUnavailableException.class)
                .hasMessageContaining("returned nothing");
    }
}