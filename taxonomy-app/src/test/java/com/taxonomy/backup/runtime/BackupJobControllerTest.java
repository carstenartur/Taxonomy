package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.backup.jobs.*;
import com.taxonomy.backup.web.*;
import com.taxonomy.security.config.AuthorizationRulesConfigurer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = BackupJobController.class, properties = "taxonomy.backup.enabled=true")
@Import({BackupJobController.class, BackupJobControllerTest.Security.class, com.taxonomy.shared.config.I18nConfig.class})
class BackupJobControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean BackupJobService jobs;
    @MockitoBean BackupPrincipalResolver principals;
    final PrincipalId actor = PrincipalId.create();
    final BackupRequest request = new BackupRequest(BackupProfile.CURRENT_STATE, new BackupScope.Workspace("repo", "workspace"),
            new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
    @Test void ordinaryAuthenticatedUserCanQueueWithCsrfButAnonymousAndMissingCsrfCannot() throws Exception {
        when(principals.require(any())).thenReturn(actor); var id = BackupJobId.create(); when(jobs.submit(request, actor)).thenReturn(id);
        byte[] body = new BackupManifestCodec().writeRequest(request);
        mvc.perform(post("/api/backups/jobs").with(user("reader").roles("USER")).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id.value().toString())).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/backups/jobs").with(user("reader").roles("USER")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(get("/api/backups/jobs")).andExpect(status().isUnauthorized());
        verify(jobs, times(1)).submit(request, actor);
    }
    @Test void oversizedDuplicateAndUnknownRequestFieldsCannotReachJobCreation() throws Exception {
        when(principals.require(any())).thenReturn(actor);
        mvc.perform(post("/api/backups/jobs").with(user("reader")).with(csrf()).contentType("application/json")
                .content(new byte[BackupManifestCodec.MAX_REQUEST_BYTES + 1])).andExpect(status().isPayloadTooLarge());
        for (String body : List.of("null", "[]", "\"NEVER_ECHO_THIS\"", "{\"profile\":\"CURRENT_STATE\",\"profile\":\"REPOSITORY_HISTORY\"}", "{\"password\":\"NEVER_ECHO_THIS\"}"))
            mvc.perform(post("/api/backups/jobs").with(user("reader")).with(csrf()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("NEVER_ECHO_THIS"))));
        verifyNoInteractions(jobs);
    }
    @Test void failuresNeverEchoSourcePathsAndStatusIsNeverCacheable() throws Exception {
        when(principals.require(any())).thenReturn(actor);
        when(jobs.list(actor)).thenThrow(new IllegalStateException("password=NEVER_ECHO_THIS /private/path"));
        mvc.perform(get("/api/backups/jobs").with(user("reader"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("BACKUP_UNAVAILABLE")).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("NEVER_ECHO_THIS"))));
        mvc.perform(put("/api/backups/jobs/anything").with(user("reader")).with(csrf())).andExpect(status().isForbidden());
    }
    @Test void statusPageRendersEnglishAndGermanWithoutExposingInternalJobData() throws Exception {
        when(principals.require(any())).thenReturn(actor);
        mvc.perform(get("/backup-jobs").with(user("reader")).locale(Locale.ENGLISH)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Backup jobs")));
        mvc.perform(get("/backup-jobs").with(user("reader")).locale(Locale.GERMAN)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sicherungsaufträge")))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(jobs);
    }
    @Configuration(proxyBeanMethods = false) @EnableWebSecurity static class Security {
        @Bean AuthorizationRulesConfigurer rules() { return new AuthorizationRulesConfigurer(); }
        @Bean SecurityFilterChain filters(HttpSecurity http, AuthorizationRulesConfigurer rules) throws Exception {
            return http.authorizeHttpRequests(rules::configure).httpBasic(org.springframework.security.config.Customizer.withDefaults()).build();
        }
    }
}
