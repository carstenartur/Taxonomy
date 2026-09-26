package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The actual MVC interceptor must reject central writes as a client error, not an internal failure. */
class ExplicitCentralAnalysisAdmissionTest {
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final RepositoryStateService state = mock(RepositoryStateService.class);
    private final DslWorkspacePreResolutionInterceptor interceptor =
            new DslWorkspacePreResolutionInterceptor(resolver, state);

    private void context(boolean isolated) {
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(isolated
                ? RepositoryContext.workspace("repo-a", "workspace-a", "draft", "alice")
                : RepositoryContext.centralRead("repo-a", "draft", "alice"));
        when(resolver.resolveCurrentContext()).thenReturn(
                new WorkspaceContext("alice", isolated ? "workspace-a" : null, "draft", "repo-a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "query"})
    void explicitlySelectedReadOnlyCentralScopeNeverEntersFullAnalysisController(
            String pinSource) throws Exception {
        context(false);
        var controller = new Probe();
        var mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(interceptor).build();
        var request = MockMvcRequestBuilders.post("/api/analyze");
        if ("header".equals(pinSource)) request.header("X-Taxonomy-Workspace-Id", "");
        else request.param("workspaceId", "");
        mvc.perform(request).andExpect(status().isForbidden());
        assertThat(controller.calls).isZero();
    }

    @Test
    void unexpectedCentralFallbackWithoutExplicitSelectionStillFailsTheInvariant() {
        context(false);
        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Probe()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not resolve an isolated workspace");
    }

    @Test
    void validIsolatedAnalysisRetainsItsExistingAdmission() throws Exception {
        context(true);
        var controller = new Probe();
        var mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(interceptor).build();
        mvc.perform(MockMvcRequestBuilders.post("/api/analyze")
                        .header("X-Taxonomy-Workspace-Id", "workspace-a"))
                .andExpect(status().isOk());
        assertThat(controller.calls).isEqualTo(1);
    }

    @RestController
    static class Probe {
        int calls;
        @PostMapping("/api/analyze") String analyze() { calls++; return "accepted"; }
    }
}
