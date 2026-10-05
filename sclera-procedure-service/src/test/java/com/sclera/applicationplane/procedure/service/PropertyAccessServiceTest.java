package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.authz.FgaAuthorizationService;
import com.sclera.applicationplane.procedure.dto.PropertyResponse;
import com.sclera.applicationplane.procedure.tenancy.PropertyLabels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the property switcher is offered, with OpenFGA replaced by a mock.
 * Whether the OpenFGA query itself is right is ViewablePropertiesIT's job.
 */
class PropertyAccessServiceTest {

    private static final UUID VDMS001 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID VDMS002 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID UNLABELLED = UUID.fromString("0badc0de-1111-2222-3333-444455556666");

    private FgaAuthorizationService fga;
    private PropertyLabels labels;
    private PropertyAccessService service;

    @BeforeEach
    void setUp() {
        fga = mock(FgaAuthorizationService.class);
        labels = new PropertyLabels();
        labels.setLabels(Map.of(VDMS001.toString(), "VDMS001", VDMS002.toString(), "VDMS002"));
        service = new PropertyAccessService(fga, labels);
        when(fga.isEnabled()).thenReturn(true);
    }

    @Test
    void offersOnlyThePropertiesTheCallerMayOpen() {
        when(fga.orgObjects("property")).thenReturn(List.of(VDMS002, VDMS001));
        when(fga.check("property", VDMS001, "can_view")).thenReturn(true);
        when(fga.check("property", VDMS002, "can_view")).thenReturn(false);

        assertThat(service.viewable()).containsExactly(new PropertyResponse(VDMS001, "VDMS001"));
    }

    @Test
    void sortsByCodeAndLabelsUnknownIdsWithTheFirstBlockOfTheId() {
        when(fga.orgObjects("property")).thenReturn(List.of(VDMS002, UNLABELLED, VDMS001));
        when(fga.check(eq("property"), any(), eq("can_view"))).thenReturn(true);

        assertThat(service.viewable()).extracting(PropertyResponse::code)
                .containsExactly("0badc0de", "VDMS001", "VDMS002");
    }

    @Test
    void anOrganizationWithNoPropertiesOffersNone() {
        when(fga.orgObjects("property")).thenReturn(List.of());

        assertThat(service.viewable()).isEmpty();
    }

    @Test
    void withOpenFgaDisabledTheLabelledPropertiesStandIn() {
        when(fga.isEnabled()).thenReturn(false);
        when(fga.check(eq("property"), any(), eq("can_view"))).thenReturn(true);

        assertThat(service.viewable()).extracting(PropertyResponse::id).containsExactly(VDMS001, VDMS002);
        verify(fga, never()).orgObjects(any());
    }
}
