package com.taxonomy.shared.config;

import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves the productive form-login chain authorizes before consuming LLM quota. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "llm.mock=true",
    "taxonomy.rate-limit.per-minute=1",
    "taxonomy.init.async=false",
    "taxonomy.git.bootstrap=false"
})
class RateLimitFilterSecurityChainTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Autowired
    private FilterRegistrationBean<RateLimitFilter> rateLimitRegistration;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private FilterChainProxy securityFilterChain;

    @Autowired
    private TaxonomyService taxonomyService;

    @Autowired
    private TaxDslExportService exportService;

    @Autowired
    private SystemRepositoryService systemRepositoryService;

    @Autowired
    private DslGitRepositoryFactory repositories;

    @MockitoBean
    private PreferencesService preferencesService;

    @BeforeEach
    void configureLimit() throws IOException {
        when(preferencesService.getInt(
                eq("rate-limit.per-minute"), anyInt())).thenReturn(1);
        rateLimitFilter.clearCounters();

        // The production bootstrap guard is JVM-wide and may belong to a different
        // test database. Own the real source checkpoint needed by analysis pinning.
        assertThat(taxonomyService.isInitialized()).as("real catalogue startup completed").isTrue();
        var primary = systemRepositoryService.getPrimaryRepository();
        var source = repositories.getCentralRepository(primary.getRepositoryId());
        String branch = primary.getDefaultBranch();
        if (source.getHeadCommit(branch) == null) {
            source.commitDsl(branch, exportService.exportAll("rate-limit-security-chain"),
                    "system", "Catalogue source for rate-limit security-chain fixture");
        }
        assertThat(source.getDslAtHead(branch)).as("readable source checkpoint for analysis workspace").isNotBlank();
    }

    @Test
    void limiterRunsExactlyOnceAfterAuthorizationAndNotAsAContainerFilter() {
        List<Filter> filters = securityFilterChain.getFilters("/api/analyze-node");

        int authorizationIndex = indexOf(filters, AuthorizationFilter.class);
        int limiterIndex = filters.indexOf(rateLimitFilter);

        assertThat(rateLimitRegistration.isEnabled()).isFalse();
        assertThat(authorizationIndex).isGreaterThanOrEqualTo(0);
        assertThat(limiterIndex).isGreaterThan(authorizationIndex);
        assertThat(filters.stream().filter(rateLimitFilter::equals)).hasSize(1);
    }

    @Test
    void unauthenticatedRequestIsRejectedBeforeLimiterAllocatesState()
            throws Exception {
        mockMvc.perform(get("/api/analyze-node")
                        .param("parentCode", "BP")
                        .param("businessText", "resilient communications"))
                .andExpect(status().isUnauthorized());

        assertThat(rateLimitFilter.trackedPrincipalCount()).isZero();
    }

    @Test
    void invalidCsrfMutationDoesNotConsumeOrAllocateQuota() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .with(user("alice").roles("ADMIN"))
                        .with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"resilient communications\"}"))
                .andExpect(status().isForbidden());

        assertThat(rateLimitFilter.trackedPrincipalCount()).isZero();
    }

    @Test
    void forwardingHeaderCannotResetAuthenticatedPrincipalBudget()
            throws Exception {
        analyzeNode("", "alice", "203.0.113.10")
                .andExpect(status().isOk());
        analyzeNode("", "alice", "203.0.113.99")
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void authenticatedPrincipalsBehindOnePeerHaveIndependentBudgets()
            throws Exception {
        analyzeNode("", "alice", "203.0.113.10")
                .andExpect(status().isOk());
        analyzeNode("", "bob", "203.0.113.10")
                .andExpect(status().isOk());
    }

    @Test
    void servletContextPathReceivesTheSamePrincipalQuota() throws Exception {
        analyzeNode("/taxonomy", "alice", "203.0.113.10")
                .andExpect(status().isOk());
        analyzeNode("/taxonomy", "alice", "203.0.113.99")
                .andExpect(status().isTooManyRequests());
    }

    private ResultActions analyzeNode(
            String contextPath,
            String username,
            String forwardedFor) throws Exception {
        String requestPath = contextPath + "/api/analyze-node";
        var request = get(requestPath)
                .with(user(username).roles("ADMIN"))
                .header("X-Forwarded-For", forwardedFor)
                .param("parentCode", "BP")
                .param("businessText", "resilient communications");
        if (!contextPath.isEmpty()) {
            request.contextPath(contextPath).servletPath("/api/analyze-node");
        }
        return mockMvc.perform(request);
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
