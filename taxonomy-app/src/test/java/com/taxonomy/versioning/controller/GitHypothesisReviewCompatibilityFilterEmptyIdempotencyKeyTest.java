package com.taxonomy.versioning.controller;

import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisService;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class GitHypothesisReviewCompatibilityFilterEmptyIdempotencyKeyTest {

    @Test
    void presentEmptyOrWhitespaceOnlyKeyFailsBeforeRepositoryWork()
            throws Exception {
        for (String value : List.of("", " ", "\t")) {
            GitAuthoritativeHypothesisService hypothesisService =
                    mock(GitAuthoritativeHypothesisService.class);
            RelationBranchProjectionReadinessService readinessService =
                    mock(RelationBranchProjectionReadinessService.class);
            WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);
            SystemRepositoryService repositoryService =
                    mock(SystemRepositoryService.class);
            RepositoryMembershipService membershipService =
                    mock(RepositoryMembershipService.class);
            GitHypothesisReviewCompatibilityFilter filter =
                    new GitHypothesisReviewCompatibilityFilter(
                            hypothesisService,
                            readinessService,
                            workspaceResolver,
                            repositoryService,
                            membershipService);

            MockHttpServletRequest request = new MockHttpServletRequest(
                    "POST", "/api/dsl/hypotheses/42/accept");
            request.addHeader("Idempotency-Key", value);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, mock(FilterChain.class));

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                    .isEqualTo("no-store");
            assertThat(response.getContentType()).startsWith("application/json");
            assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
            assertThat(response.getContentAsString())
                    .contains("\"code\":\"INVALID_IDEMPOTENCY_KEY\"")
                    .contains("between 1 and 128 visible ASCII characters")
                    .doesNotContain("repo", "workspace");
            verifyNoInteractions(
                    workspaceResolver,
                    readinessService,
                    hypothesisService,
                    repositoryService,
                    membershipService);
        }
    }
}
