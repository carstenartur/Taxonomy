package com.taxonomy.setup;

import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.security.config.AuthorizationRulesConfigurer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = DatabaseSetupController.class, properties = "spring.datasource.url=jdbc:hsqldb:mem:demo")
@Import({DatabaseSetupController.class, DatabaseSetupControllerTest.Security.class, com.taxonomy.shared.config.I18nConfig.class})
class DatabaseSetupControllerTest {
    @Autowired MockMvc mvc;
    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    @MockitoBean PreferencesService preferences;
    @MockitoBean com.taxonomy.backup.runtime.BackupAccessPolicy navigationAccess;
    @MockitoBean com.taxonomy.security.service.PrincipalIdentityService navigationIdentities;
    private static final String BASE = "/api/admin/database-setup";
    private static final String REQUEST = "{\"type\":\"postgres\",\"host\":\"db.example.test\",\"port\":5432,\"database\":\"taxonomy\",\"username\":\"taxonomy\",\"password\":\"PRIVATE_SENTINEL\",\"tls\":true,\"caCertificate\":\"\"}";

    @Test void administratorCanReadAndDismissButAnonymousAndOrdinaryUsersCannot() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get(BASE).with(user("reader").roles("USER"))).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get(BASE).with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("hsqldb"))
                .andExpect(jsonPath("$.inMemory").value(true)).andExpect(jsonPath("$.promptRequired").value(true))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post(BASE + "/dismiss").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/dismiss").with(user("reader").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/dismiss").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dismissed").value(true))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(preferences).update(Map.of("setup.database.dismissed", true), "admin");
    }

    @Test void previewsRequireAdminAndCsrfAndContainNoPassword() throws Exception {
        for (String action : new String[]{"preview", "check"}) {
            mvc.perform(post(BASE + "/" + action).with(user("admin").roles("ADMIN")).contentType("application/json").content(REQUEST)).andExpect(status().isForbidden());
            mvc.perform(post(BASE + "/" + action).with(user("reader").roles("USER")).with(csrf()).contentType("application/json").content(REQUEST)).andExpect(status().isForbidden());
        }
        mvc.perform(post(BASE + "/preview").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(REQUEST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.jdbcUrl", containsString("sslmode=verify-full")))
                .andExpect(jsonPath("$.findings").isArray())
                .andExpect(content().string(not(containsString("PRIVATE_SENTINEL"))))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(preferences);
    }

    @Test void malformedDuplicateOversizedAndInvalidRequestsNeverReflectSecrets() throws Exception {
        for (String body : new String[]{"null", "[]", "\"PRIVATE_SENTINEL\"", "{PRIVATE_SENTINEL", REQUEST.replace("5432", "\"PRIVATE_SENTINEL\""), REQUEST.replace("5432", "5432.5"), REQUEST.replace("true", "\"PRIVATE_SENTINEL\""), REQUEST + " PRIVATE_SENTINEL", REQUEST.replace("\"type\":", "\"unknown\":\"PRIVATE_SENTINEL\",\"type\":"), REQUEST.replace("\"type\":", "\"type\":\"PRIVATE_SENTINEL\",\"type\":"), REQUEST.replace("db.example.test", "db;password=PRIVATE_SENTINEL")}) {
            mvc.perform(post(BASE + "/preview").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString("PRIVATE_SENTINEL"))))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(post(BASE + "/check").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(" ".repeat(16 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge()).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(preferences);
    }

    @Test void unsafeSchemaOverridesReturnFixedConflictWithoutSecretsOrCacheability() throws Exception {
        String source = "databaseSetupConflictFixture";
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(source,
                Map.of("spring.jpa.properties.hibernate.hbm2ddl.auto", "PRIVATE_SENTINEL")));
        try {
            mvc.perform(post(BASE + "/preview").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(REQUEST))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DATABASE_SETUP_SCHEMA_CONFLICT"))
                    .andExpect(jsonPath("$.message", containsString("spring.jpa.properties.hibernate.hbm2ddl.auto")))
                    .andExpect(jsonPath("$.message", containsString("spring.jpa.properties.jakarta.persistence.schema-generation.database.action")))
                    .andExpect(content().string(not(containsString("PRIVATE_SENTINEL"))))
                    .andExpect(header().string("Cache-Control", "no-store"));
            verifyNoInteractions(preferences);
        } finally { environment.getPropertySources().remove(source); }
    }

    @Test void preferenceStorageFailuresAreFixedAndNotCacheable() throws Exception {
        doThrow(new java.io.IOException("PRIVATE_SENTINEL")).when(preferences).update(anyMap(), anyString());
        mvc.perform(post(BASE + "/dismiss").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(not(containsString("PRIVATE_SENTINEL"))))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Configuration(proxyBeanMethods = false) @EnableWebSecurity static class Security {
        @Bean DatabaseSetupService setupService(Environment environment, PreferencesService preferences) { return new DatabaseSetupService(environment, preferences); }
        @Bean AuthorizationRulesConfigurer rules() { return new AuthorizationRulesConfigurer(); }
        @Bean SecurityFilterChain filters(HttpSecurity http, AuthorizationRulesConfigurer rules) throws Exception {
            return http.authorizeHttpRequests(rules::configure).httpBasic(org.springframework.security.config.Customizer.withDefaults()).build();
        }
    }
}
