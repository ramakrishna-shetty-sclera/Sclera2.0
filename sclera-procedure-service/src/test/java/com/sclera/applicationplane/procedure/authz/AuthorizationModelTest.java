package com.sclera.applicationplane.procedure.authz;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.sclera.applicationplane.procedure.support.AuthorizationModelFiles.dslRelations;
import static com.sclera.applicationplane.procedure.support.AuthorizationModelFiles.jsonMetadataRelations;
import static com.sclera.applicationplane.procedure.support.AuthorizationModelFiles.jsonRelations;
import static com.sclera.applicationplane.procedure.support.AuthorizationModelFiles.parsedJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authorization model's structure, without starting OpenFGA.
 *
 * The model is written twice by hand — model.fga to read, authorization-model.json
 * to post — and inside the JSON every relation is written twice again, under
 * {@code relations} and under {@code metadata}. These are the places a change
 * most often goes half-done, so they are checked here first, cheaply.
 * Whether OpenFGA accepts the model and answers as intended is
 * AuthorizationModelIT's job.
 */
class AuthorizationModelTest {

    @Test
    void bothFilesDefineTheSameTypes() {
        assertThat(dslRelations().keySet())
                .containsExactlyInAnyOrderElementsOf(jsonRelations().keySet());
    }

    @Test
    void everyRelationInTheJsonHasMetadataAndNothingElseDoes() {
        // Without its metadata entry a role cannot be assigned: OpenFGA refuses
        // tuples written against it. An entry with no relation is a typo.
        Map<String, Set<String>> metadata = jsonMetadataRelations();
        jsonRelations().forEach((type, relations) ->
                assertThat(metadata.getOrDefault(type, Set.of()))
                        .as("metadata relations of %s", type)
                        .containsExactlyInAnyOrderElementsOf(relations));
    }

    @Test
    void modelFgaDefinesTheSameRelationsAsTheJson() {
        Map<String, Set<String>> dsl = dslRelations();
        jsonRelations().forEach((type, relations) ->
                assertThat(dsl.get(type))
                        .as("relations of %s in model.fga", type)
                        .containsExactlyInAnyOrderElementsOf(relations));
    }

    @Test
    void everyAssignableOrganizationRoleIsAMember() {
        // can_view derives from member, here and on every org-owned type. A role
        // left out of member can act but cannot read: a result_type_manager
        // could create a result type and then be refused the list.
        JsonNode organization = organization();

        Set<String> assignable = new TreeSet<>();
        organization.path("metadata").path("relations").fields().forEachRemaining(entry -> {
            if (!entry.getValue().path("directly_related_user_types").isEmpty()) {
                assignable.add(entry.getKey());
            }
        });

        Set<String> members = new TreeSet<>();
        organization.path("relations").path("member").path("union").path("child")
                .forEach(child -> members.add(child.path("computedUserset").path("relation").asText()));

        assertThat(members).containsExactlyInAnyOrderElementsOf(assignable);
    }

    private static JsonNode organization() {
        for (JsonNode definition : parsedJson().path("type_definitions")) {
            if ("organization".equals(definition.path("type").asText())) {
                return definition;
            }
        }
        throw new AssertionError("authorization-model.json has no organization type");
    }
}
