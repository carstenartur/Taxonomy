package com.taxonomy.security.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorSecurityFilterTest {

    private static final String ADMIN_TOKEN = "test-admin-token";

    private ActuatorSecurityFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ActuatorSecurityFilter();
        ReflectionTestUtils.setField(filter, "adminPassword", ADMIN_TOKEN);
    }

    @Test
    void healthEndpointsRemainPublicForPlatformProbes() throws Exception {
        MockHttpServletResponse response = invoke("/actuator/health/readiness", null, null);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void sensitiveEndpointRejectsMissingCredentials() throws Exception {
        MockHttpServletResponse response = invoke("/actuator/prometheus", null, null);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Admin authentication required");
    }

    @Test
    void sensitiveEndpointAcceptsLegacyAdminHeader() throws Exception {
        MockHttpServletResponse response = invoke(
                "/actuator/prometheus", "X-Admin-Token", ADMIN_TOKEN);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void sensitiveEndpointAcceptsBearerTokenForPrometheusOperator() throws Exception {
        MockHttpServletResponse response = invoke(
                "/actuator/prometheus", HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void sensitiveEndpointRejectsIncorrectBearerToken() throws Exception {
        MockHttpServletResponse response = invoke(
                "/actuator/prometheus", HttpHeaders.AUTHORIZATION, "Bearer wrong-token");

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void blankAdminTokenPreservesDevelopmentCompatibility() throws Exception {
        ReflectionTestUtils.setField(filter, "adminPassword", "");

        MockHttpServletResponse response = invoke("/actuator/prometheus", null, null);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse invoke(String path, String header, String value)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (header != null) {
            request.addHeader(header, value);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
