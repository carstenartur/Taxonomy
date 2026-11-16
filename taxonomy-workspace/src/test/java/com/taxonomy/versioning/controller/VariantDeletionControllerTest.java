package com.taxonomy.versioning.controller;

import com.taxonomy.workspace.service.WorkspaceBranchDeletion;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VariantDeletionControllerTest {

    @Test
    void previewReturnsANonCacheableErrorWhenTheSelectedWorkspaceHasNoPublicErrorReason() throws Exception {
        var resolver = mock(WorkspaceResolver.class);
        var deletion = mock(WorkspaceBranchDeletion.class);
        when(resolver.resolveUnambiguousRepositoryForRead())
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        var mvc = MockMvcBuilders.standaloneSetup(new VariantDeletionController(resolver, deletion)).build();

        mvc.perform(get("/api/dsl/branch/deletion-preview").param("name", "candidate"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.error").value("Variant unavailable"));
        verifyNoInteractions(deletion);
    }
}
