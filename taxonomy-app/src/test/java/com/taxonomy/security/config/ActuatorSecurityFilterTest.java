package com.taxonomy.security.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorSecurityFilterTest {

    private static final String ADMIN_TOKEN = "test-admin-token";

    private ActuatorSecurityFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ActuatorSecurityFilter(ADMIN_TOKEN);
    }

    @Test
    void healthAndInfoEndpointsRemainPublicForPlatformProbes() throws Exception {
        assertThat(invoke("/actuator/health/readiness", null, null).getStatus())
                .isEqualTo(200);
        assertThat(invoke("/actuator/info", null, null).getStatus())
                .isEqualTo(200);
    }

    @Test
    void exactActuatorDiscoveryRootIsProtected() throws Exception {
        MockHttpServletResponse missing = invoke("/actuator", null, null);
        MockHttpServletResponse valid = invoke(
                "/actuator", "X-Admin-Token", ADMIN_TOKEN);

        assertUnauthorized(missing);
        assertThat(valid.getStatus()).isEqualTo(200);
    }

    @Test
    void sensitiveEndpointRejectsMissingCredentialsWithStableNoStoreJson()
            throws Exception {
        MockHttpServletResponse response = invoke(
                "/actuator/prometheus", null, null);

        assertUnauthorized(response);
        assertThat(response.getContentAsString())
                .contains("\"status\":401")
                .contains("\"code\":\"ACTUATOR_MACHINE_TOKEN_REQUIRED\"")
                .contains("A valid Actuator machine token is required.");
    }

    @Test
    void contextPathCannotBypassSensitiveEndpointOrDiscoveryRootProtection()
            throws Exception {
        assertUnauthorized(invokeContextPath(
                "/taxonomy/actuator/prometheus", "/taxonomy", null, null));
        assertUnauthorized(invokeContextPath(
                "/taxonomy/actuator", "/taxonomy", null, null));
    }

    @Test
    void sensitiveEndpointAcceptsLegacyAdminHeader() throws Exception {
        MockHttpServletResponse response = invoke(
                "/actuator/prometheus", "X-Admin-Token", ADMIN_TOKEN);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void sensitiveEndpointAcceptsCaseInsensitiveBearerScheme() throws Exception {
        MockHttpServletResponse response = invoke(
                "/actuator/prometheus",
                HttpHeaders.AUTHORIZATION,
                "bearer " + ADMIN_TOKEN);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void malformedBearerSyntaxIsRejectedWithoutNormalization() throws Exception {
        assertUnauthorized(invoke(
                "/actuator/prometheus",
                HttpHeaders.AUTHORIZATION,
                "Bearer  " + ADMIN_TOKEN));
        assertUnauthorized(invoke(
                "/actuator/prometheus",
                HttpHeaders.AUTHORIZATION,
                "Bearer " + ADMIN_TOKEN + " "));
        assertUnauthorized(invoke(
                "/actuator/prometheus",
                HttpHeaders.AUTHORIZATION,
                "Bearer\t" + ADMIN_TOKEN));
    }

    @Test
    void oversizedHeaderCandidatesAreRejectedWithoutEcho() throws Exception {
        String oversized = "x".repeat(
                ActuatorSecurityFilter.MAX_TOKEN_CANDIDATE_LENGTH + 1);

        MockHttpServletResponse legacy = invoke(
                "/actuator/prometheus", "X-Admin-Token", oversized);
        MockHttpServletResponse bearer = invoke(
                "/actuator/prometheus",
                HttpHeaders.AUTHORIZATION,
                "Bearer " + oversized);

        assertUnauthorized(legacy);
        assertUnauthorized(bearer);
        assertThat(legacy.getContentAsString()).doesNotContain(oversized);
        assertThat(bearer.getContentAsString()).doesNotContain(oversized);
    }

    @Test
    void incorrectTokenIsRejected() throws Exception {
        assertUnauthorized(invoke(
                "/actuator/prometheus",
                HttpHeaders.AUTHORIZATION,
                "Bearer wrong-token"));
    }

    @Test
    void blankAdminTokenDefersToTheFollowingSecurityChain() throws Exception {
        filter = new ActuatorSecurityFilter("");

        MockHttpServletResponse response = invoke(
                "/actuator/prometheus", null, null);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void unrelatedApplicationPathPassesThrough() throws Exception {
        MockHttpServletResponse response = invoke("/api/projects", null, null);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse invoke(
            String path,
            String header,
            String value) throws Exception {
        return invokeContextPath(path, "", header, value);
    }

    private MockHttpServletResponse invokeContextPath(
            String path,
            String contextPath,
            String header,
            String value) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setContextPath(contextPath);
        if (header != null) {
            request.addHeader(header, value);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static void assertUnauthorized(MockHttpServletResponse response)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentAsString())
                .contains("\"code\":\""
                        + ActuatorSecurityFilter.UNAUTHORIZED_CODE
                        + "\"");
    }
}
