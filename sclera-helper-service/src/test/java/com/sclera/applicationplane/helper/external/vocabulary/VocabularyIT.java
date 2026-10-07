package com.sclera.applicationplane.helper.external.vocabulary;

import com.sclera.applicationplane.helper.support.PostgresIntegrationTest;
import com.sclera.applicationplane.helper.tenancy.TenantSchemas;
import com.sclera.controlplane.common.security.OrgContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The property vocabulary against real Postgres: what a new organization is
 * seeded with, that each organization has its own rows, and both ways in.
 *
 * Each test runs in a brand-new organization, which starts with only the seeds.
 * Rows an organization "extends" its lists with are inserted straight into its
 * schema, since nothing in this service writes them yet.
 */
class VocabularyIT extends PostgresIntegrationTest {

    @Autowired
    private VocabularyService service;

    @Autowired
    private VocabularyController publicApi;

    @Autowired
    private InternalVocabularyController internalApi;

    @Autowired
    private JdbcTemplate jdbc;

    private static List<String> keys(List<VocabularyEntryResponse> entries) {
        return entries.stream().map(VocabularyEntryResponse::key).toList();
    }

    /** Adds a row to one of an organization's lists, the way an organization extends them. */
    private void extend(UUID org, String table, String key, String name, int order, boolean active) {
        jdbc.update("INSERT INTO " + TenantSchemas.schemaFor(org) + "." + table
                + " (key, name, display_order, active) VALUES (?, ?, ?, ?)", key, name, order, active);
    }

    // --- what a new organization starts with ------------------------------------------

    @Test
    void aNewOrganizationIsSeededWithTheHierarchyLevelsAndLocationTypes() {
        actAsNewOrg();

        assertThat(keys(service.list(VocabularyKind.HIERARCHY_LEVEL, false)))
                .containsExactly("BUILDING", "FLOOR", "LOCATION", "ASSET", "SUB_ASSET");
        assertThat(keys(service.list(VocabularyKind.LOCATION_TYPE, false)))
                .containsExactly("ROOM", "CORRIDOR", "OPEN_AREA", "PLANT_ROOM");
        assertThat(service.list(VocabularyKind.HIERARCHY_LEVEL, false))
                .allSatisfy(e -> assertThat(e.active()).isTrue());
    }

    @Test
    void assetClassesAndTagsStartEmptyBecauseTheyAreWhateverTheOrganizationUses() {
        actAsNewOrg();

        assertThat(service.list(VocabularyKind.ASSET_CLASS, false)).isEmpty();
        assertThat(service.list(VocabularyKind.ASSET_TAG, false)).isEmpty();
    }

    @Test
    void allCarriesEveryKindEvenWhenAListIsEmpty() {
        actAsNewOrg();

        Map<VocabularyKind, List<VocabularyEntryResponse>> all = service.all(false);

        assertThat(all).containsOnlyKeys(VocabularyKind.values());
        assertThat(all.get(VocabularyKind.ASSET_CLASS)).isEmpty();
        assertThat(all.get(VocabularyKind.HIERARCHY_LEVEL)).hasSize(5);
    }

    @Test
    void theTenantMigrationsAreAtTwoSoAnyNewOneIsNoticed() {
        // The pin is the point, as in the procedure service: a tenant migration
        // has to reach every existing tenant schema, so nobody adds one without
        // seeing this line and thinking about that.
        UUID org = actAsNewOrg();

        String latest = jdbc.queryForObject("SELECT max(version::int)::text FROM "
                + TenantSchemas.schemaFor(org) + ".flyway_schema_history WHERE success", String.class);
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ?", String.class, TenantSchemas.schemaFor(org));

        assertThat(latest).isEqualTo("2");
        assertThat(tables).contains("hierarchy_level", "location_type", "asset_class", "asset_tag");
    }

    // --- one organization's rows are its own ----------------------------------------------

    @Test
    void anOrganizationExtendsItsOwnListsAndNoOtherOrganizationSeesThem() {
        UUID first = actAsNewOrg();
        extend(first, "asset_class", "EXTINGUISHER", "Fire extinguisher", 1, true);
        UUID second = actAsNewOrg();

        assertThat(asOrg(first, () -> keys(service.list(VocabularyKind.ASSET_CLASS, false))))
                .containsExactly("EXTINGUISHER");
        assertThat(asOrg(second, () -> service.list(VocabularyKind.ASSET_CLASS, false))).isEmpty();
    }

    // --- retiring a key, and the order ------------------------------------------------------

    @Test
    void aRetiredKeyIsStillListedWithItsFlagAndLeftOutOfTheActiveOnlyView() {
        UUID org = actAsNewOrg();
        extend(org, "asset_class", "EXTINGUISHER", "Fire extinguisher", 1, true);
        extend(org, "asset_class", "HOSE_REEL", "Hose reel", 2, false);

        assertThat(service.list(VocabularyKind.ASSET_CLASS, false))
                .extracting(VocabularyEntryResponse::key, VocabularyEntryResponse::active)
                .containsExactly(tuple("EXTINGUISHER", true), tuple("HOSE_REEL", false));
        assertThat(keys(service.list(VocabularyKind.ASSET_CLASS, true))).containsExactly("EXTINGUISHER");
    }

    @Test
    void entriesComeInDisplayOrderThenKeySoTheOrderIsTheSameEveryTime() {
        UUID org = actAsNewOrg();
        extend(org, "asset_tag", "ZEBRA", "Zebra", 1, true);
        extend(org, "asset_tag", "APPLE", "Apple", 2, true);
        extend(org, "asset_tag", "MANGO", "Mango", 2, true);

        assertThat(keys(service.list(VocabularyKind.ASSET_TAG, false)))
                .containsExactly("ZEBRA", "APPLE", "MANGO");
    }

    @Test
    void aKeyIsUppercaseOrTheDatabaseRefusesIt() {
        UUID org = actAsNewOrg();

        assertThatThrownBy(() -> extend(org, "asset_class", "extinguisher", "x", 1, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- the public way in ----------------------------------------------------------------

    @Test
    void thePublicEndpointsReturnEveryEntryIncludingRetiredOnes() {
        UUID org = actAsNewOrg();
        extend(org, "asset_class", "HOSE_REEL", "Hose reel", 1, false);

        assertThat(publicApi.one(VocabularyKind.ASSET_CLASS))
                .extracting(VocabularyEntryResponse::key, VocabularyEntryResponse::active)
                .containsExactly(tuple("HOSE_REEL", false));
        assertThat(publicApi.all().get(VocabularyKind.ASSET_CLASS)).hasSize(1);
    }

    @Test
    void everyPublicEndpointNeedsCanViewAndTheInternalOneCarriesNoGuard() {
        // Guards fail closed, so a typo in the relation name is a 403 for
        // everyone and passes every other test.
        for (Method method : VocabularyController.class.getDeclaredMethods()) {
            PreAuthorize guard = method.getAnnotation(PreAuthorize.class);
            if (method.getName().equals("all") || method.getName().equals("one")) {
                assertThat(guard).as(method.getName()).isNotNull();
                assertThat(guard.value()).isEqualTo("@fga.checkOrg('can_view')");
            }
        }
        // No JWT reaches an internal endpoint, so a guard there could never pass.
        for (Method method : InternalVocabularyController.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(PreAuthorize.class)).as(method.getName()).isNull();
        }
    }

    // --- the internal way in ---------------------------------------------------------------

    @Test
    void theInternalEndpointReadsAnOrganizationsRowsWithNoOrganizationContext() {
        UUID org = actAsNewOrg();
        extend(org, "asset_class", "EXTINGUISHER", "Fire extinguisher", 1, true);
        OrgContext.clear();   // a Dapr call carries no JWT

        Map<VocabularyKind, List<VocabularyEntryResponse>> all = internalApi.all(org);

        assertThat(keys(all.get(VocabularyKind.ASSET_CLASS))).containsExactly("EXTINGUISHER");
        assertThat(all.get(VocabularyKind.HIERARCHY_LEVEL)).hasSize(5);
    }

    @Test
    void theInternalEndpointPicksTheOrganizationFromThePathNotFromWhoIsSignedIn() {
        UUID first = actAsNewOrg();
        extend(first, "asset_class", "EXTINGUISHER", "Fire extinguisher", 1, true);
        UUID second = actAsNewOrg();      // the current context is now the second organization

        assertThat(keys(internalApi.all(first).get(VocabularyKind.ASSET_CLASS))).containsExactly("EXTINGUISHER");
        assertThat(internalApi.all(second).get(VocabularyKind.ASSET_CLASS)).isEmpty();
    }

    @Test
    void anOrganizationNeverSeenBeforeIsProvisionedWithItsSeedsRatherThanFailing() {
        OrgContext.clear();

        Map<VocabularyKind, List<VocabularyEntryResponse>> all = internalApi.all(UUID.randomUUID());

        assertThat(keys(all.get(VocabularyKind.LOCATION_TYPE)))
                .containsExactly("ROOM", "CORRIDOR", "OPEN_AREA", "PLANT_ROOM");
    }
}