package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalysisProgressAdmissionTest {
    private final AnalysisProgressRegistry registry = new AnalysisProgressRegistry(new StandardEnvironment());
    private final WorkspaceContext scope = new WorkspaceContext("alice", "work-a", "draft", "repo-a");
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);

    private MockMvc mvc(AnalysisProgressRegistry source) {
        return MockMvcBuilders.standaloneSetup(new AnalysisProgressController(source, resolver)).build();
    }

    @Test void observationBeforeRegistrationDoesNotAllocateWorkOrProduceAnHttpError() throws Exception {
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenReturn(scope);
        MockMvc mvc = mvc(registry);
        String id = UUID.randomUUID().toString();
        String path = "/api/analysis-runs/" + id;
        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(get(path).param("waitForRegistration", "true"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().string(""));
        assertThat(registry.recent("alice", scope, null, null)).isEmpty();
        try (var run = registry.open(id, "alice", scope, null)) {
            mvc.perform(get(path).param("waitForRegistration", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operationId").value(id))
                    .andExpect(jsonPath("$.status").value("RUNNING"));
            run.finish("SUCCESS");
        }
    }

    @Test void pendingObservationDoesNotDiscloseForeignOwnersOrBranches() throws Exception {
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenReturn(scope);
        MockMvc mvc = mvc(registry);
        try (var run = registry.open(null, "alice", scope, null)) {
            String path = "/api/analysis-runs/" + run.id();
            when(resolver.resolveCurrentUsername()).thenReturn("bob");
            mvc.perform(get(path).param("waitForRegistration", "true"))
                    .andExpect(status().isAccepted()).andExpect(content().string(""));
            mvc.perform(get(path)).andExpect(status().isNotFound());
            mvc.perform(get(path + "/calls/1").param("waitForRegistration", "true"))
                    .andExpect(status().isNotFound());
            mvc.perform(post(path + "/cancel").param("waitForRegistration", "true"))
                    .andExpect(status().isNotFound());
            when(resolver.resolveCurrentUsername()).thenReturn("alice");
            when(resolver.resolveCurrentContext()).thenReturn(
                    new WorkspaceContext("alice", "work-a", "other", "repo-a"));
            mvc.perform(get(path).param("waitForRegistration", "true"))
                    .andExpect(status().isAccepted()).andExpect(content().string(""));
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test void pendingObservationDoesNotHideOtherRegistryFailures() throws Exception {
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenReturn(scope);
        AnalysisProgressRegistry failing = mock(AnalysisProgressRegistry.class);
        String id = UUID.randomUUID().toString();
        when(failing.snapshot(id, "alice", scope))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        mvc(failing).perform(get("/api/analysis-runs/" + id).param("waitForRegistration", "true"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test void workspaceResolutionFailuresAreNotPendingAdmission() throws Exception {
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        mvc(registry).perform(get("/api/analysis-runs/" + UUID.randomUUID())
                        .param("waitForRegistration", "true"))
                .andExpect(status().isForbidden());
    }
}
