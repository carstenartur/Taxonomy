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

    private static final String METRICS_TOKEN = "test-metrics-token";

    private ActuatorSecurityFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ActuatorSecurityFilter();
        ReflectionTestUtils.setField(filter, "metricsToken", METRICS_TOKEN);
        ReflectionTestUtils.setField(filter, "allowUnauthenticated", false);
    }

    @Test
    void healthEndpointsRemainPublicForPlatformProbes() throws Exception {
        assertThat(invoke("/actuator/health/readiness", null, null).getStatus())
                .isEqualTo(200);
    }

    @Test
    void sensitiveEndpointRejectsMissingCredentials() throws Exception {
        MockHttpServletResponse response = invoke("/actuator/prometheus", null, null);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/problem+json");
    }

    @Test
    void sensitiveEndpointAcceptsDedicatedHeader() throws Exception {
        assertThat(invoke(
                "/actuator/prometheus", "X-Metrics-Token", METRICS_TOKEN).getStatus())
                .isEqualTo(200);
    }

    @Test
    void sensitiveEndpointAcceptsBearerMetricsToken() throws Exception {
        assertThat(invoke(
                "/actuator/prometheus", HttpHeaders.AUTHORIZATION,
                "Bearer " + METRICS_TOKEN).getStatus())
                .isEqualTo(200);
    }

    @Test
    void sensitiveEndpointRejectsIncorrectBearerToken() throws Exception {
        assertThat(invoke(
                "/actuator/prometheus", HttpHeaders.AUTHORIZATION,
                "Bearer wrong-token").getStatus())
                .isEqualTo(401);
    }

    @Test
    void blankTokenFailsClosedByDefault() throws Exception {
        ReflectionTestUtils.setField(filter, "metricsToken", "");
        assertThat(invoke("/actuator/prometheus", null, null).getStatus())
                .isEqualTo(401);
    }

    @Test
    void developmentCompatibilityMustBeExplicit() throws Exception {
        ReflectionTestUtils.setField(filter, "metricsToken", "");
        ReflectionTestUtils.setField(filter, "allowUnauthenticated", true);
        assertThat(invoke("/actuator/prometheus", null, null).getStatus())
                .isEqualTo(200);
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
