package com.taxonomy.relations.controller;

import com.taxonomy.relations.service.RequirementCoverageService;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CoverageApiControllerInputTest {
    private final String commit = "a".repeat(40);
    private final com.taxonomy.workspace.service.RepositoryContext selected =
            com.taxonomy.workspace.service.RepositoryContext.workspace("A", "A1", "draft", "alice");
    private CoverageApiController controller(RequirementCoverageService service) throws Exception {
        var resolver = mock(com.taxonomy.workspace.service.WorkspaceResolver.class);
        var store = mock(com.taxonomy.workspace.service.WorkspaceDslReadPort.class);
        var read = mock(com.taxonomy.workspace.service.WorkspaceDslReadPort.RepositoryRead.class);
        when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(selected);
        when(store.openRead(selected)).thenReturn(read);
        when(read.currentHead()).thenReturn(commit);
        return new CoverageApiController(service, resolver, store, tools.jackson.databind.json.JsonMapper.builder().build());
    }

    @Test
    void stalePinsAndReadOnlyCentralSelectionsNeverReachTheWriter() throws Exception {
        var service = mock(RequirementCoverageService.class);
        var resolver = mock(com.taxonomy.workspace.service.WorkspaceResolver.class);
        var store = mock(com.taxonomy.workspace.service.WorkspaceDslReadPort.class);
        var read = mock(com.taxonomy.workspace.service.WorkspaceDslReadPort.RepositoryRead.class);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new CoverageApiController(service, resolver, store, tools.jackson.databind.json.JsonMapper.builder().build()))
                .setControllerAdvice(new CoverageSourceErrors()).build();
        when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(selected, selected,
                com.taxonomy.workspace.service.RepositoryContext.centralRead("A", "draft", "alice"));
        when(store.openRead(selected)).thenReturn(read);
        when(read.currentHead()).thenReturn(commit);
        String other = "b".repeat(40);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/coverage/requirement/R")
                        .param("commitId", other))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Taxonomy-Commit", commit));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/coverage/density").param("commitId", other))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/coverage/requirement/R")
                        .param("commitId", commit))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        verifyNoInteractions(service);
        verify(store, times(2)).openRead(selected);
    }

    @Test
    void missingAndFailedSourceReadsExposeNoCommitAndNeverQueryCoverage() throws Exception {
        var service = mock(RequirementCoverageService.class);
        var resolver = mock(com.taxonomy.workspace.service.WorkspaceResolver.class);
        var store = mock(com.taxonomy.workspace.service.WorkspaceDslReadPort.class);
        var read = mock(com.taxonomy.workspace.service.WorkspaceDslReadPort.RepositoryRead.class);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new CoverageApiController(service, resolver, store, tools.jackson.databind.json.JsonMapper.builder().build()))
                .setControllerAdvice(new CoverageSourceErrors()).build();
        when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(selected);
        when(store.openRead(selected)).thenReturn(read);
        when(read.currentHead()).thenReturn(null).thenThrow(new IOException("Git read unavailable"));

        mvc.perform(get("/api/coverage/density"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Taxonomy-Source-State", "MISSING"))
                .andExpect(header().doesNotExist("X-Taxonomy-Commit"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/coverage/density"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Taxonomy-Source-State", "FAILED"))
                .andExpect(header().doesNotExist("X-Taxonomy-Commit"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(service);
    }

    @Test
    void missingScoresAreRecordedAsAnEmptyMapWhilePreservingRequirementAndThreshold() throws Exception {
        var service = mock(RequirementCoverageService.class);
        var response = controller(service).recordCoverage(
                new CoverageApiController.RecordCoverageRequest("REQ-17", "Requirement", null, 70), commit);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(service).analyzeCoverage(selected, commit, Map.of(), "REQ-17", "Requirement", 70);
        verifyNoMoreInteractions(service);
    }
    @Test
    void suppliedScoresAndExplicitThresholdAreForwardedUnchanged() throws Exception {
        var service = mock(RequirementCoverageService.class);
        var scores = Map.of("BP", 85d, "CP", 40d);
        var response = controller(service).recordCoverage(
                new CoverageApiController.RecordCoverageRequest("REQ-18", "Another requirement", scores, 50), commit);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(service).analyzeCoverage(selected, commit, scores, "REQ-18", "Another requirement", 50);
        verifyNoMoreInteractions(service);
    }

}
