package com.taxonomy.security.webdav;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebDavApplicationCredentialFilterTest {

    private static final String TOKEN =
            "taxdav_" + "a".repeat(24) + "_" + "A".repeat(43);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesAnApplicationSecretForTheFilterChainAndErasesItAfterward()
            throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        when(service.authenticate("admin", TOKEN)).thenReturn(Optional.of(principal(true)));
        WebDavApplicationCredentialFilter filter = filter(service);
        MockHttpServletRequest request = request("GET");
        request.setContextPath("/taxonomy");
        request.setRequestURI("/taxonomy/dav/templates/report.dotx");
        request.addHeader("Authorization", basic("admin", TOKEN));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> observed = new AtomicReference<>();
        FilterChain chain = (req, res) -> observed.set(
                SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, response, chain);

        assertThat(observed.get().getName()).isEqualTo("admin");
        assertThat(observed.get().getCredentials()).isEqualTo("[PROTECTED]");
        assertThat(observed.get().getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .contains("ROLE_ADMIN", "SCOPE_template:write");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void readOnlyCredentialCannotPutLockOrUnlock() throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        when(service.authenticate("admin", TOKEN)).thenReturn(Optional.of(principal(false)));
        WebDavApplicationCredentialFilter filter = filter(service);
        MockHttpServletRequest request = request("PUT");
        request.addHeader("Authorization", basic("admin", TOKEN));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(called).isFalse();
    }

    @Test
    void invalidOrAbsentCredentialGetsABasicChallengeWithoutLoginHtml()
            throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        when(service.authenticate("admin", TOKEN)).thenReturn(Optional.empty());
        WebDavApplicationCredentialFilter filter = filter(service);

        MockHttpServletRequest invalid = request("GET");
        invalid.addHeader("Authorization", basic("admin", TOKEN));
        MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
        filter.doFilter(invalid, invalidResponse, (req, res) -> { });
        assertThat(invalidResponse.getStatus()).isEqualTo(401);
        assertThat(invalidResponse.getHeader("WWW-Authenticate"))
                .startsWith("Basic realm=\"Taxonomy WebDAV\"");

        MockHttpServletRequest absent = request("OPTIONS");
        MockHttpServletResponse absentResponse = new MockHttpServletResponse();
        filter.doFilter(absent, absentResponse, (req, res) -> { });
        assertThat(absentResponse.getStatus()).isEqualTo(401);
        assertThat(absentResponse.getContentAsString()).doesNotContain("<html");
    }

    @Test
    void ordinaryLocalAccountBasicAuthenticationFallsThroughToTheExistingFilter()
            throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        WebDavApplicationCredentialFilter filter = filter(service);
        MockHttpServletRequest request = request("GET");
        request.addHeader("Authorization", basic("admin", "ordinary-password"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(called).isTrue();
    }

    private static WebDavApplicationCredentialFilter filter(
            WebDavApplicationCredentialService service) {
        return new WebDavApplicationCredentialFilter(
                service,
                Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));
    }

    private static MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                method, "/dav/templates/report.dotx");
        request.setRequestURI("/dav/templates/report.dotx");
        return request;
    }

    private static WebDavApplicationCredentialService.CredentialPrincipal principal(
            boolean write) {
        List<SimpleGrantedAuthority> authorities = write
                ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("SCOPE_template:read"),
                    new SimpleGrantedAuthority("SCOPE_template:write"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("SCOPE_template:read"));
        return new WebDavApplicationCredentialService.CredentialPrincipal(
                "a".repeat(24), "admin", true, write, List.copyOf(authorities));
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
