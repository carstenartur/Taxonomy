package com.taxonomy.security;

import com.taxonomy.security.controller.LegacyOwnershipPage;
import com.taxonomy.security.service.VerifiedLegacyOwnerMigration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyOwnershipPageTest {
    private final VerifiedLegacyOwnerMigration migration = mock(VerifiedLegacyOwnerMigration.class);
    private final LegacyOwnershipPage page = new LegacyOwnershipPage(migration);
    private final Authentication administrator = mock(Authentication.class);

    @Test void aSingleBoundedReadOwnsTheWholeDisplay() {
        var status = mock(VerifiedLegacyOwnerMigration.ReservationStatus.class);
        when(migration.reservationStatus(administrator, 100)).thenReturn(status);
        var model = new ExtendedModelMap();
        var response = new MockHttpServletResponse();
        assertThat(page.view(administrator, model, response)).isEqualTo("legacy-ownership");
        assertThat(model.get("status")).isSameAs(status);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        verify(migration).reservationStatus(administrator, 100);
        verifyNoMoreInteractions(migration);
    }

    @Test void aDeniedReadNeverPopulatesTheModelOrCachesIt() {
        doThrow(new AccessDeniedException("Unavailable")).when(migration).reservationStatus(administrator, 100);
        var model = new ExtendedModelMap();
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> page.view(administrator, model, response)).isInstanceOf(AccessDeniedException.class);
        assertThat(model).isEmpty();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");

        verify(migration).reservationStatus(administrator, 100);
        verifyNoMoreInteractions(migration);
    }
}
