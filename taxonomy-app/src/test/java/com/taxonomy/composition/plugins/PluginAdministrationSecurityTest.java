package com.taxonomy.composition.plugins;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"taxonomy.plugins.dynamic.enabled=true", "llm.mock=true"})
@AutoConfigureMockMvc
class PluginAdministrationSecurityTest {
    @Autowired org.springframework.web.context.WebApplicationContext context;
    MockMvc mvc;
    @org.junit.jupiter.api.BeforeEach void actualSecurityWithoutTestDefaultCsrf() {
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
    }
    @Test @WithMockUser(roles = "ADMIN")
    void administratorReceivesStableUnknownInstalledIdReason() throws Exception {
        mvc.perform(post("/api/admin/plugins/example.missing/activate").with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("NOT_INSTALLED"));
    }
    @Test @WithMockUser(roles = "USER")
    void ordinaryUserCannotInspectOrMutatePlugins() throws Exception {
        mvc.perform(get("/api/admin/plugins")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/plugins/example.missing/activate").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/plugins/example.missing/deactivate").with(csrf())).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles = "ADMIN")
    void browserSessionMutationsRequireCsrf() throws Exception {
        mvc.perform(post("/api/admin/plugins/example.missing/activate")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/plugins/example.missing/deactivate")).andExpect(status().isForbidden());
    }
}
