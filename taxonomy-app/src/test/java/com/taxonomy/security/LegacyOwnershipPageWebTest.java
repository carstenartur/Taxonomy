package com.taxonomy.security;

import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.controller.LegacyOwnershipPage;
import com.taxonomy.security.service.LegacyOwnerMigrationReadiness;
import com.taxonomy.security.service.VerifiedLegacyOwnerMigration;
import com.taxonomy.shared.config.I18nConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import com.taxonomy.shared.config.WebMvcConfig;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LegacyOwnershipPage.class)
@ContextConfiguration(classes = {LegacyOwnershipPage.class, I18nConfig.class, WebMvcConfig.class, UserManagementPageTest.Security.class})
class LegacyOwnershipPageWebTest {
    @Autowired WebApplicationContext context;
    @MockitoBean com.taxonomy.workspace.service.ExplicitWorkspacePinValidationInterceptor explicitPin;
    @MockitoBean com.taxonomy.versioning.controller.DslWorkspacePreResolutionInterceptor dslSelection;
    @MockitoBean VerifiedLegacyOwnerMigration migration;
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test @WithMockUser(roles = "ADMIN")
    void rendersTheSameBoundedReportInBothLanguages() throws Exception {
        var id = PrincipalId.create();
        var row = new VerifiedLegacyOwnerMigration.UnresolvedOwner(id, "controlled <archive> owner");
        var inventory = new LegacyOwnerMigrationReadiness.Inventory(104, 1, 103, 104);
        var preview = new VerifiedLegacyOwnerMigration.Preview(103, List.of(row), "private-service-fingerprint", true);
        when(migration.reservationStatus(any(), eq(100)))
                .thenReturn(new VerifiedLegacyOwnerMigration.ReservationStatus(inventory, preview));
        for (var language : List.of("de", "en")) {
            mvc.perform(get("/admin/identity/ownership").param("lang", language))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control",containsString("no-store")))
                    .andExpect(content().string(containsString(language.equals("de") ? "Historische Eigentümerschaft" : "Historical ownership")))
                    .andExpect(content().string(containsString(language.equals("de") ? "1 von 103" : "1 of 103")))
                    .andExpect(content().string(containsString("controlled &lt;archive&gt; owner")))
                    .andExpect(content().string(containsString(id.value().toString())))
                    .andExpect(content().string(not(containsString("private-service-fingerprint"))));
        }
        verify(migration, times(2)).reservationStatus(any(), eq(100));
        verifyNoMoreInteractions(migration);
    }

    @Test @WithMockUser(roles = "USER")
    void ordinaryUserCannotOpenTheInventory() throws Exception {
        mvc.perform(get("/admin/identity/ownership")).andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("Reservations"))));
        verifyNoInteractions(migration);
    }
}
