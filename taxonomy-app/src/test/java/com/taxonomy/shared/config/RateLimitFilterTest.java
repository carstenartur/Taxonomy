package com.taxonomy.shared.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Principal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private AtomicInteger passedRequests;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        ReflectionTestUtils.setField(filter, "maxRequestsPerMinute", 1);
        passedRequests = new AtomicInteger();
    }

    @Test
    void servletContextPathDoesNotBypassProtectedEndpointMatching() throws Exception {
        MockHttpServletResponse first = invoke(request(
                "/taxonomy/api/analyze", "/taxonomy", "alice", null));
        MockHttpServletResponse second = invoke(request(
                "/taxonomy/api/analyze", "/taxonomy", "alice", null));

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(passedRequests).hasValue(1);
    }

    @Test
    void spoofedForwardedForDoesNotCreateAnotherAuthenticatedUserBudget() throws Exception {
        MockHttpServletResponse first = invoke(request(
                "/api/analyze", "", "alice", "198.51.100.10"));
        MockHttpServletResponse second = invoke(request(
                "/api/analyze", "", "alice", "203.0.113.20"));

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(passedRequests).hasValue(1);
    }

    @Test
    void authenticatedUsersHaveIndependentBudgets() throws Exception {
        MockHttpServletResponse alice = invoke(request(
                "/api/analyze", "", "alice", "198.51.100.10"));
        MockHttpServletResponse bob = invoke(request(
                "/api/analyze", "", "bob", "198.51.100.10"));

        assertThat(alice.getStatus()).isEqualTo(200);
        assertThat(bob.getStatus()).isEqualTo(200);
        assertThat(passedRequests).hasValue(2);
    }

    private MockHttpServletResponse invoke(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response,
                (servletRequest, servletResponse) -> passedRequests.incrementAndGet());
        return response;
    }

    private static MockHttpServletRequest request(
            String requestUri,
            String contextPath,
            String username,
            String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", requestUri);
        request.setContextPath(contextPath);
        request.setRemoteAddr("10.0.0.15");
        Principal principal = () -> username;
        request.setUserPrincipal(principal);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }
}
