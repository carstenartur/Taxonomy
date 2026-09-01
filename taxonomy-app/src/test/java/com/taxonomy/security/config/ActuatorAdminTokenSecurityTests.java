package com.taxonomy.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies the real servlet-filter and Spring-Security interaction for monitoring clients. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "admin.token=metrics-admin-token-1234567890",
        "taxonomy.admin-password=local-admin-password-1234567890",
        "taxonomy.security.require-password-change=false",
        "gemini.api.key=",
        "openai.api.key=",
        "deepseek.api.key=",
        "qwen.api.key=",
        "llama.api.key=",
        "mistral.api.key="
})
class ActuatorAdminTokenSecurityTests {

    private static final String ADMIN_TOKEN = "metrics-admin-token-1234567890";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActuatorSecurityFilter actuatorSecurityFilter;

    @Autowired
    @Qualifier(ActuatorSecurityFilter.REGISTRATION_NAME)
    private FilterRegistrationBean<ActuatorSecurityFilter> registration;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void filterHasOneExplicitRegistrationImmediatelyAfterSpringSecurity() {
        Map<String, FilterRegistrationBean> registrationBeans =
                applicationContext.getBeansOfType(FilterRegistrationBean.class);
        var actuatorRegistrations = registrationBeans.values().stream()
                .filter(candidate -> candidate.getFilter() == actuatorSecurityFilter)
                .toList();

        assertThat(actuatorRegistrations).containsExactly(registration);
        assertThat(registration.isEnabled()).isTrue();
        assertThat(registration.getUrlPatterns()).containsExactly("/*");
        assertThat(registration.getOrder())
                .isEqualTo(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1)
                .isEqualTo(ActuatorAdminTokenSecurityConfig.ACTUATOR_FILTER_ORDER);
        assertThat(ActuatorSecurityFilter.class.getAnnotation(
                org.springframework.stereotype.Component.class)).isNull();
    }

    @Test
    void configuredBearerTokenCanReadPrometheusWithoutInteractiveLogin()
            throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void configuredLegacyHeaderCanReadPrometheusWithoutInteractiveLogin()
            throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void exactDiscoveryRootUsesTheSameMachineTokenBoundary() throws Exception {
        assertUnauthorized(mockMvc.perform(get("/actuator")));
        mockMvc.perform(get("/actuator")
                        .header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void configuredBearerTokenWorksBehindAContextPath() throws Exception {
        mockMvc.perform(get("/taxonomy/actuator/prometheus")
                        .contextPath("/taxonomy")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(get("/taxonomy/actuator")
                        .contextPath("/taxonomy")
                        .header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void missingTokenCannotReadSensitivePaths() throws Exception {
        assertUnauthorized(mockMvc.perform(get("/actuator/prometheus")));
        assertUnauthorized(mockMvc.perform(get("/taxonomy/actuator/prometheus")
                .contextPath("/taxonomy")));
    }

    @Test
    void malformedAndOversizedCandidatesReturnSanitizedNoStoreJson()
            throws Exception {
        assertUnauthorized(mockMvc.perform(get("/actuator/prometheus")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer  " + ADMIN_TOKEN)));
        assertUnauthorized(mockMvc.perform(get("/actuator/prometheus")
                .header("X-Admin-Token", "x".repeat(
                        ActuatorSecurityFilter.MAX_TOKEN_CANDIDATE_LENGTH + 1))));
        assertUnauthorized(mockMvc.perform(get("/actuator/prometheus")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + "x".repeat(
                        ActuatorSecurityFilter.MAX_TOKEN_CANDIDATE_LENGTH + 1))));
    }

    @Test
    void tokenAuthenticationDoesNotDisableCsrfForWritingRequests() throws Exception {
        mockMvc.perform(post("/actuator/metrics")
                        .with(csrf().useInvalidToken())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthAndInfoProbesRemainPublicWhenAdminTokenIsConfigured()
            throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    private static void assertUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().encoding("UTF-8"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"code\":\""
                                + ActuatorSecurityFilter.UNAUTHORIZED_CODE
                                + "\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(ADMIN_TOKEN))));
    }
}
