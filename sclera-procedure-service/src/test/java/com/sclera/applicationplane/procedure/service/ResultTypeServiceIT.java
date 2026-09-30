package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.AbstractIntegrationTest;
import com.sclera.applicationplane.procedure.dto.ResultTypeRequest;
import com.sclera.applicationplane.procedure.dto.ResultTypeResponse;
import com.sclera.applicationplane.procedure.dto.ResultTypeUpdateRequest;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ConflictException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultTypeServiceIT extends AbstractIntegrationTest {

    @Autowired
    private ResultTypeService service;

    /** A fresh org id each test, so every case starts from the seeded pair alone. */
    private UUID newOrg() {
        return UUID.randomUUID();
    }

    private ResultTypeRequest amber(Integer severityOrder) {
        return new ResultTypeRequest("AMBER", "Amber", "#f39c12", "Attention needed", severityOrder);
    }

    @Test
    void provisioningATenantSeedsFailThenPass() {
        UUID org = newOrg();

        List<ResultTypeResponse> types = asOrg(org, () -> service.list(null));

        // The seed is written by db/tenant/V2, which derives org_id from the
        // schema name. If that derivation broke, this is where it shows.
        assertThat(types).extracting(ResultTypeResponse::key).containsExactly("FAIL", "PASS");
        assertThat(types).extracting(ResultTypeResponse::severityOrder).containsExactly(1, 2);
        assertThat(types).allMatch(ResultTypeResponse::system);
        assertThat(types).allMatch(ResultTypeResponse::active);
    }

    @Test
    void createWithoutRankAppendsAsLeastSevere() {
        UUID org = newOrg();

        ResultTypeResponse created = asOrg(org, () -> service.create(amber(null)));

        assertThat(created.severityOrder()).isEqualTo(3);
        assertThat(created.system()).isFalse();
    }

    @Test
    void createWithRankInsertsAndShiftsTheRest() {
        UUID org = newOrg();

        List<ResultTypeResponse> after = asOrg(org, () -> {
            service.create(amber(2));
            return service.list(null);
        });

        assertThat(after).extracting(ResultTypeResponse::key).containsExactly("FAIL", "AMBER", "PASS");
        assertThat(after).extracting(ResultTypeResponse::severityOrder).containsExactly(1, 2, 3);
    }

    @Test
    void createBeyondTheEndIsRejected() {
        UUID org = newOrg();

        assertThatThrownBy(() -> asOrg(org, () -> service.create(amber(99))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("between 1 and 3");
    }

    @Test
    void duplicateKeyIsRejected() {
        UUID org = newOrg();

        assertThatThrownBy(() -> asOrg(org, () -> {
            service.create(amber(null));
            return service.create(amber(null));
        })).isInstanceOf(ConflictException.class);
    }

    @Test
    void systemTypesCanBeRenamedAndRecoloured() {
        UUID org = newOrg();

        ResultTypeResponse renamed = asOrg(org, () -> {
            UUID passId = keyed(service.list(null), "PASS").id();
            return service.update(passId, new ResultTypeUpdateRequest("Compliant", "#1abc9c", "Meets criteria"));
        });

        // An org that says "Compliant" must be able to say so; the key it is
        // stored under does not move, so nothing referencing it breaks.
        assertThat(renamed.key()).isEqualTo("PASS");
        assertThat(renamed.name()).isEqualTo("Compliant");
        assertThat(renamed.color()).isEqualTo("#1abc9c");
    }

    @Test
    void systemTypesCannotBeDeletedOrDeactivated() {
        UUID org = newOrg();

        assertThatThrownBy(() -> asOrg(org, () -> {
            service.delete(keyed(service.list(null), "PASS").id());
            return null;
        })).isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot be deleted");

        assertThatThrownBy(() -> asOrg(org, () ->
                service.deactivate(keyed(service.list(null), "FAIL").id())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot be deactivated");
    }

    @Test
    void deletingACustomTypeClosesTheRankGap() {
        UUID org = newOrg();

        List<ResultTypeResponse> after = asOrg(org, () -> {
            service.create(amber(2));                       // FAIL 1, AMBER 2, PASS 3
            service.delete(keyed(service.list(null), "AMBER").id());
            return service.list(null);
        });

        // Ranks must stay contiguous: create derives the end of the list from
        // the row count, so a hole would let the next type reuse a live rank.
        assertThat(after).extracting(ResultTypeResponse::key).containsExactly("FAIL", "PASS");
        assertThat(after).extracting(ResultTypeResponse::severityOrder).containsExactly(1, 2);
    }

    @Test
    void deletedTypeIsGone() {
        UUID org = newOrg();

        assertThatThrownBy(() -> asOrg(org, () -> {
            ResultTypeResponse created = service.create(amber(null));
            service.delete(created.id());
            return service.get(created.id());
        })).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reorderRewritesEveryRank() {
        UUID org = newOrg();

        List<ResultTypeResponse> after = asOrg(org, () -> {
            service.create(amber(null));                    // FAIL 1, PASS 2, AMBER 3
            List<ResultTypeResponse> current = service.list(null);
            return service.reorder(List.of(
                    keyed(current, "AMBER").id(),
                    keyed(current, "FAIL").id(),
                    keyed(current, "PASS").id()));
        });

        assertThat(after).extracting(ResultTypeResponse::key).containsExactly("AMBER", "FAIL", "PASS");
        assertThat(after).extracting(ResultTypeResponse::severityOrder).containsExactly(1, 2, 3);
    }

    @Test
    void partialReorderListIsRejected() {
        UUID org = newOrg();

        assertThatThrownBy(() -> asOrg(org, () ->
                service.reorder(List.of(keyed(service.list(null), "FAIL").id()))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("every result type");
    }

    @Test
    void deactivatingHidesFromTheActiveListOnly() {
        UUID org = newOrg();

        asOrg(org, () -> {
            ResultTypeResponse created = service.create(amber(null));
            service.deactivate(created.id());

            assertThat(service.list(true)).extracting(ResultTypeResponse::key)
                    .containsExactly("FAIL", "PASS");
            // Still present unfiltered: past records that chose it must stay readable.
            assertThat(service.list(null)).extracting(ResultTypeResponse::key)
                    .containsExactly("FAIL", "PASS", "AMBER");
            return null;
        });
    }

    @Test
    void organizationsAreIsolated() {
        UUID orgA = newOrg();
        UUID orgB = newOrg();

        asOrg(orgA, () -> service.create(amber(null)));

        List<ResultTypeResponse> bTypes = asOrg(orgB, () -> service.list(null));
        assertThat(bTypes).extracting(ResultTypeResponse::key).containsExactly("FAIL", "PASS");

        // And A's own row is untouched by B existing.
        List<ResultTypeResponse> aTypes = asOrg(orgA, () -> service.list(null));
        assertThat(aTypes).extracting(ResultTypeResponse::key).containsExactly("FAIL", "PASS", "AMBER");
    }

    private ResultTypeResponse keyed(List<ResultTypeResponse> types, String key) {
        return types.stream()
                .filter(rt -> rt.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No result type with key " + key));
    }
}
