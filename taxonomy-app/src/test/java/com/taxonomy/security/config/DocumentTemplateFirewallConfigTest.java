package com.taxonomy.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.firewall.RequestRejectedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTemplateFirewallConfigTest {
    private final DocumentTemplateFirewallConfig.TemplateHttpFirewall firewall =
            new DocumentTemplateFirewallConfig.TemplateHttpFirewall();

    @Test
    void additionalVerbsAreLimitedToExactDavPathSegments() {
        for (String context : List.of("", "/taxonomy", "/dav/templates")) {
            for (String method : List.of("PROPFIND", "LOCK", "UNLOCK")) {
                for (String path : List.of("/dav/templates", "/dav/templates/", "/dav/templates/test.dotx")) {
                    assertThatCode(() -> firewall.getFirewalledRequest(request(context, path, method)))
                            .doesNotThrowAnyException();
                }
                for (String path : List.of("/login", "/api/templates", "/dav/templates-extra/")) {
                    assertThatThrownBy(() -> firewall.getFirewalledRequest(request(context, path, method)))
                            .isInstanceOf(RequestRejectedException.class);
                }
            }
        }
    }

    @Test
    void normalMethodPolicyIsUnchangedAndUnsupportedVerbsRemainRejected() {
        for (String method : List.of("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT")) {
            assertThatCode(() -> firewall.getFirewalledRequest(request("", "/login", method)))
                    .doesNotThrowAnyException();
        }
        for (String method : List.of("TRACE", "CONNECT", "MKCOL", "COPY", "MOVE", "PROPPATCH", "propfind", "")) {
            assertThatThrownBy(() -> firewall.getFirewalledRequest(request("", "/dav/templates/", method)))
                    .isInstanceOf(RequestRejectedException.class);
        }
    }

    @Test
    void strictPathValidationRemainsInFrontOfDavRouting() {
        for (String path : List.of("/dav/templates/../login", "/dav/templates/%2e%2e/login",
                "/dav/templates;ignored/", "/dav//templates/", "/dav/templates/%2fescape",
                "/dav/templates/%5cescape", "/dav/templates/%00")) {
            assertThatThrownBy(() -> firewall.getFirewalledRequest(request("/taxonomy", path, "PROPFIND")))
                    .isInstanceOf(RequestRejectedException.class);
        }
    }

    @Test
    void queryStringCannotTurnAnUnrelatedEndpointIntoDav() {
        var request = request("/taxonomy", "/login", "LOCK");
        request.setQueryString("returnTo=/dav/templates/file.dotx");
        assertThatThrownBy(() -> firewall.getFirewalledRequest(request))
                .isInstanceOf(RequestRejectedException.class);
    }

    private static MockHttpServletRequest request(String context, String path, String method) {
        var request = new MockHttpServletRequest(method, context + path);
        request.setContextPath(context);
        request.setServletPath(path);
        return request;
    }
}
