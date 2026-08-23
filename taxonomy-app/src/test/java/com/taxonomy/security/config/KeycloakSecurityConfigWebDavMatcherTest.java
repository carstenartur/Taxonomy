package com.taxonomy.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakSecurityConfigWebDavMatcherTest {

    @Test
    void basicApplicationCredentialsAreStatelessOnlyForTemplateWebDavPaths()
            throws Exception {
        assertThat(matches(
                request("/dav/templates", "Basic dGVzdDp0ZXN0"))).isTrue();
        assertThat(matches(request(
                "/dav/templates/decision-rationale-report.dotx",
                "basic dGVzdDp0ZXN0"))).isTrue();

        assertThat(matches(request(
                "/admin/document-templates", "Basic dGVzdDp0ZXN0"))).isFalse();
        assertThat(matches(request("/dav/templates", null))).isFalse();
        assertThat(matches(request("/dav/templates", "Bearer token"))).isFalse();
    }

    private static boolean matches(MockHttpServletRequest request) throws Exception {
        Method method = KeycloakSecurityConfig.class.getDeclaredMethod(
                "isStatelessWebDavApplicationClient",
                jakarta.servlet.http.HttpServletRequest.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, request);
    }

    private static MockHttpServletRequest request(
            String uri,
            String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", uri);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }
}
