package com.taxonomy.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.ORIGIN;

class MailStatusPortalTest {
    private static final String TOKEN = "11111111-2222-4333-8444-555555555555";
    private static final String JOB = "/mail-analysis/jobs/" + TOKEN;
    private static final String PORTAL = "https://portal.example.test:8443";

    @Test
    void keepsCanonicalBackendContextAndTokenInFragment() {
        var portal = URI.create(PORTAL + "/monitor/");
        var external = MailRequestParserTest.properties(5000, portal);
        URI link = URI.create(external.statusLink(TOKEN));
        assertThat(link.getRawQuery()).isNull();
        assertThat(link.getRawPath()).isEqualTo("/monitor/");
        assertThat(link.getRawFragment()).startsWith("status=https%3A%2F%2F");
        String api = URLDecoder.decode(link.getRawFragment().substring("status=".length()), StandardCharsets.UTF_8);
        String page = MailRequestParserTest.properties(5000).statusLink(TOKEN);
        assertThat(page).isEqualTo("https://example.test/taxonomy" + JOB);
        assertThat(api).isEqualTo(page + "/status");
    }

    @Test
    void emptyEnvironmentPlaceholderUsesBuiltInPortal() {
        var empty = MailRequestParserTest.properties(5000, URI.create(""));
        assertThat(empty.statusPortalUrl()).isNull();
        assertThat(empty.statusLink(TOKEN)).isEqualTo("https://example.test/taxonomy" + JOB);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://portal.example.test", "https://user@portal.example.test/",
            "https://portal.example.test/?param=1", "https://portal.example.test/#fragment", "/portal"})
    void rejectsUnsupportedPortalAddresses(String value) {
        assertThatThrownBy(() -> MailRequestParserTest.properties(5000, URI.create(value)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsOnlyThePortalOriginAndTheJsonReceiptRoute() throws Exception {
        var request = cors("GET", JOB + "/status", PORTAL, null);
        assertThat(request.getHeader(ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(PORTAL);
        assertThat(request.getHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
        assertThat(cors("GET", JOB + "/status", "https://other.example.test", null).getStatus()).isEqualTo(403);
        assertThat(cors("GET", JOB, PORTAL, null).getHeader(ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        assertThat(cors("GET", "/mail-analysis/jobs/invalid/status", PORTAL, null)
                .getHeader(ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        assertThat(cors("GET", "/actuator/health", PORTAL, null).getHeader(ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        var configuration = new MailTransportConfiguration();
        assertThat(configuration.mailStatusPortalCors(MailRequestParserTest.properties(5000)).isEnabled()).isFalse();
    }

    @Test
    void answersGetPreflightsAndRejectsWrites() throws Exception {
        String api = JOB + "/status";
        var allowed = cors("OPTIONS", api, PORTAL, "GET");
        assertThat(allowed.getHeader(ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(PORTAL);
        assertThat(allowed.getHeader(ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("GET");
        assertThat(cors("OPTIONS", api, PORTAL, "POST").getStatus()).isEqualTo(403);
        assertThat(cors("POST", api, PORTAL, null).getStatus()).isEqualTo(403);
    }

    private MockHttpServletResponse cors(String method, String path, String origin, String requestedMethod) throws Exception {
        var properties = MailRequestParserTest.properties(5000, URI.create(PORTAL + "/monitor/"));
        var filter = new MailTransportConfiguration().mailStatusPortalCors(properties).getFilter();
        var request = new MockHttpServletRequest(method, "/taxonomy" + path);
        request.setContextPath("/taxonomy");
        request.addHeader(ORIGIN, origin);
        if (requestedMethod != null) request.addHeader(ACCESS_CONTROL_REQUEST_METHOD, requestedMethod);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
