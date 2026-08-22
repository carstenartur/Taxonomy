package com.taxonomy.templates;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTemplateWebDavAuthorizationFilterTest {

    private final DocumentTemplateWebDavAuthorizationFilter filter =
            new DocumentTemplateWebDavAuthorizationFilter();

    @Test
    void rejectsTemplateWritesWithoutAdministratorRole() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/dav/templates/decision-report.dotx");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void acceptsTemplateWritesForAdministrators() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "LOCK", "/taxonomy/dav/templates/decision-report.dotx");
        request.setContextPath("/taxonomy");
        request.addUserRole("ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void keepsReadOperationsAvailableAfterAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/dav/templates/decision-report.dotx");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresUnrelatedApplicationEndpoints() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/api/projects/42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
