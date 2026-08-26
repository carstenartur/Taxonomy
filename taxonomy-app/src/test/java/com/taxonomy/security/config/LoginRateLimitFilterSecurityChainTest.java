package com.taxonomy.security.config;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves the productive form-login chain observes authoritative authentication outcomes. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "llm.mock=true",
    "spring.datasource.url=jdbc:hsqldb:mem:login-rate-limit-it",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "taxonomy.admin-password=LoginRateLimit-Integration-Password-2026!",
    "taxonomy.security.require-password-change=false",
    "taxonomy.security.login-rate-limit.enabled=true",
    "taxonomy.security.login-rate-limit.max-attempts=2",
    "taxonomy.security.login-rate-limit.lockout-seconds=60"
})
class LoginRateLimitFilterSecurityChainTest {

    private static final String ADMIN_PASSWORD =
            "LoginRateLimit-Integration-Password-2026!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoginRateLimitFilter loginRateLimitFilter;

    @Autowired
    @Qualifier("disableContainerLoginRateLimitFilter")
    private FilterRegistrationBean<LoginRateLimitFilter> registration;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private FilterChainProxy securityFilterChain;

    @BeforeEach
    void resetLimiter() {
        loginRateLimitFilter.clearTrackers();
    }

    @Test
    void filterRunsExactlyOnceAfterContextRestoreAndBeforeAuthentication() {
        List<Filter> filters = securityFilterChain.getFilters("/login");

        int contextIndex = indexOf(filters, SecurityContextHolderFilter.class);
        int limiterIndex = filters.indexOf(loginRateLimitFilter);
        int formIndex = indexOf(filters, UsernamePasswordAuthenticationFilter.class);
        int basicIndex = indexOf(filters, BasicAuthenticationFilter.class);

        assertThat(registration.isEnabled()).isFalse();
        assertThat(contextIndex).isGreaterThanOrEqualTo(0);
        assertThat(limiterIndex).isGreaterThan(contextIndex);
        assertThat(formIndex).isGreaterThan(limiterIndex);
        assertThat(basicIndex).isGreaterThan(limiterIndex);
        assertThat(filters.stream().filter(loginRateLimitFilter::equals)).hasSize(1);
    }

    @Test
    void missingAndBearerCredentialsDoNotPoisonBasicLockoutState()
            throws Exception {
        String peer = "203.0.113.70";

        mockMvc.perform(get("/api/taxonomy").with(remoteAddress(peer)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/taxonomy")
                        .with(remoteAddress(peer))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer rejected"))
                .andExpect(status().isUnauthorized());

        assertThat(loginRateLimitFilter.trackedPeerCount()).isZero();
    }

    @Test
    void realBasicFailuresLockPeerAndValidNewCredentialsCannotBypass()
            throws Exception {
        String peer = "203.0.113.71";

        wrongBasic(peer, "198.51.100.10");
        wrongBasic(peer, "198.51.100.99");

        mockMvc.perform(get("/api/taxonomy")
                        .with(remoteAddress(peer))
                        .with(httpBasic("admin", ADMIN_PASSWORD))
                        .header("X-Forwarded-For", "192.0.2.5")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is(423))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(423))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(peer))));

        assertThat(loginRateLimitFilter.trackedPeerCount()).isEqualTo(1);
    }

    @Test
    void restoredAuthenticatedSessionBypassesAnonymousPeerLockout()
            throws Exception {
        String peer = "203.0.113.72";
        wrongBasic(peer, null);
        wrongBasic(peer, null);

        mockMvc.perform(get("/api/taxonomy")
                        .with(remoteAddress(peer))
                        .with(user("admin").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void realFormLoginFailuresAreObservedBeforeAValidNewLogin()
            throws Exception {
        String peer = "203.0.113.73";

        wrongFormLogin(peer);
        wrongFormLogin(peer);

        mockMvc.perform(post("/login")
                        .with(remoteAddress(peer))
                        .with(csrf())
                        .param("username", "admin")
                        .param("password", ADMIN_PASSWORD))
                .andExpect(status().is(423))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void prefixedBasicRequestsUseTheSamePeerLockoutContract()
            throws Exception {
        String peer = "203.0.113.74";

        prefixedBasic(peer, "wrong-password").andExpect(status().isUnauthorized());
        prefixedBasic(peer, "wrong-password").andExpect(status().isUnauthorized());
        prefixedBasic(peer, ADMIN_PASSWORD)
                .andExpect(status().is(423))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void prefixedFormLoginUsesTheSamePeerLockoutContract() throws Exception {
        String peer = "203.0.113.75";

        prefixedForm(peer, "wrong-password")
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        HttpHeaders.LOCATION, containsString("login?error")));
        prefixedForm(peer, "wrong-password")
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        HttpHeaders.LOCATION, containsString("login?error")));
        prefixedForm(peer, ADMIN_PASSWORD)
                .andExpect(status().is(423))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    private void wrongBasic(String peer, String forwardedFor) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/taxonomy")
                .with(remoteAddress(peer))
                .with(httpBasic("admin", "wrong-password"))
                .accept(MediaType.APPLICATION_JSON);
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        mockMvc.perform(request).andExpect(status().isUnauthorized());
    }

    private void wrongFormLogin(String peer) throws Exception {
        mockMvc.perform(post("/login")
                        .with(remoteAddress(peer))
                        .with(csrf())
                        .param("username", "admin")
                        .param("password", "wrong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        HttpHeaders.LOCATION, containsString("login?error")));
    }

    private ResultActions prefixedForm(
            String peer,
            String password) throws Exception {
        return mockMvc.perform(post("/taxonomy/login")
                .contextPath("/taxonomy")
                .servletPath("/login")
                .with(remoteAddress(peer))
                .with(csrf())
                .param("username", "admin")
                .param("password", password));
    }

    private ResultActions prefixedBasic(
            String peer,
            String password) throws Exception {
        return mockMvc.perform(get("/taxonomy/api/taxonomy")
                .contextPath("/taxonomy")
                .servletPath("/api/taxonomy")
                .with(remoteAddress(peer))
                .with(httpBasic("admin", password))
                .accept(MediaType.APPLICATION_JSON));
    }

    private static RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static int indexOf(List<Filter> filters, Class<? extends Filter> type) {
        for (int index = 0; index < filters.size(); index++) {
            if (type.isInstance(filters.get(index))) {
                return index;
            }
        }
        return -1;
    }
}
