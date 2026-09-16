package com.taxonomy.workspace.service;

import com.taxonomy.workspace.controller.WorkspaceController;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkspaceCentralReadBoundaryTest {
    private final WorkspaceManager manager = mock(WorkspaceManager.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);

    @AfterEach
    void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    private MockMvc mvc() {
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        return MockMvcBuilders.standaloneSetup(new WorkspaceController(
                manager, resolver, null, null, null, null, null, null))
                .addInterceptors(new ExplicitWorkspacePinValidationInterceptor(resolver)).build();
    }

    private MockHttpServletRequestBuilder centralPin(MockHttpServletRequestBuilder request, String transport) {
        String value = transport.endsWith("whitespace") ? "  " : "";
        return transport.startsWith("header")
                ? request.header(WorkspaceContextResolver.WORKSPACE_HEADER, value)
                : request.param(WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER, value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "query", "header-whitespace", "query-whitespace"})
    void centralCurrentIsNoContentWithoutSelectingAMetadataRow(String transport) {
        MockMvc mvc = mvc();
        assertDoesNotThrow(() -> mvc.perform(centralPin(get("/api/workspace/current"), transport))
                .andExpect(status().isNoContent()).andExpect(content().string("")));
        verifyNoInteractions(manager);
        verify(resolver, never()).resolveCurrentWorkspaceMetadata();
        verify(resolver, never()).resolveCurrentRepositoryContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "query", "header-whitespace", "query-whitespace"})
    void centralProvisionIsRejectedBeforeAnyProvisioningOrLookup(String transport) {
        MockMvc mvc = mvc();
        assertDoesNotThrow(() -> mvc.perform(centralPin(post("/api/workspace/provision"), transport))
                .andExpect(status().isForbidden()));
        verifyNoInteractions(manager);
        verify(resolver, never()).resolveCurrentWorkspaceMetadata();
        verify(resolver, never()).resolveCurrentRepositoryContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "query", "header-whitespace", "query-whitespace"})
    void centralProvisioningStatusRemainsAReadOnlyAbsentResult(String transport) {
        MockMvc mvc = mvc();
        assertDoesNotThrow(() -> mvc.perform(centralPin(get("/api/workspace/provisioning-status"), transport))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_PROVISIONED")));
        verifyNoInteractions(manager);
        verify(resolver, never()).resolveCurrentRepositoryContext();
    }

    @Test
    void namedCurrentStillUsesOnlyTheNamedMetadata() throws Exception {
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId("chosen-workspace");
        when(resolver.resolveCurrentWorkspaceMetadata()).thenReturn(workspace);
        mvc().perform(get("/api/workspace/current")
                .header(WorkspaceContextResolver.WORKSPACE_HEADER, "chosen-workspace"))
                .andExpect(status().isOk());
        verify(manager).getWorkspaceMetadataInfo(workspace);
        verify(manager, never()).getWorkspaceInfo(anyString());
        verify(resolver, never()).resolveCurrentRepositoryContext();
    }

    @Test
    void namedProvisionStillUsesTheAuthorizedNamedWorkspace() throws Exception {
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId("chosen-workspace");
        workspace.setCurrentBranch("work");
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
        when(resolver.resolveCurrentWorkspaceMetadata()).thenReturn(workspace);
        when(manager.provisionWorkspaceRepository("alice", "chosen-workspace")).thenReturn(workspace);
        mvc().perform(post("/api/workspace/provision")
                .header(WorkspaceContextResolver.WORKSPACE_HEADER, "chosen-workspace"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.branch").value("work"));
        verify(manager).provisionWorkspaceRepository("alice", "chosen-workspace");
        verify(manager, never()).provisionWorkspaceRepository("alice");
    }

    @ParameterizedTest
    @CsvSource({"MERGE,true", "KEEP_MINE,true", "TAKE_SHARED,true", "PULL,true", "PUBLISH,true",
                "MERGE,false", "KEEP_MINE,false", "TAKE_SHARED,false", "PULL,false", "PUBLISH,false"})
    void centralReadCannotReachAnySynchronizationWrite(String operation, boolean explicitEmptyPin) {
        var request = new MockHttpServletRequest();
        if (explicitEmptyPin) request.addHeader(WorkspaceContextResolver.WORKSPACE_HEADER, "");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var catalog = mock(SystemRepositoryService.class);
        var metadata = new SystemRepository();
        metadata.setRepositoryId("selected-source");
        metadata.setDefaultBranch("release");
        when(catalog.getPrimaryRepository()).thenReturn(metadata);
        var rows = mock(UserWorkspaceRepository.class);
        var syncRows = mock(SyncStateRepository.class);
        var factory = mock(DslGitRepositoryFactory.class);
        var semantic = mock(SemanticGitMergeService.class);
        var portfolio = mock(WorkspacePortfolioGitPort.class);
        var editor = mock(WorkspaceArchitectureVersionPort.class);
        var contexts = new WorkspaceContextResolver(manager, catalog, rows);
        var service = new GitNativeSyncIntegrationService(syncRows, rows, catalog, factory,
                semantic, portfolio, contexts, editor);
        clearInvocations(factory); // The legacy constructor obtains its primary handle once.

        assertThrows(AccessDeniedException.class, () -> {
            switch (operation) {
                case "PULL" -> service.syncFromShared("alice", "work");
                case "PUBLISH" -> service.publishToShared("alice", "work");
                default -> service.resolveDiverged("alice", "work",
                        SyncIntegrationService.DivergedStrategy.valueOf(operation));
            }
        });
        verifyNoInteractions(factory, semantic, portfolio, editor, rows, syncRows);
        if (explicitEmptyPin) verifyNoInteractions(manager);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void emptyHeaderOverridesAStaleQueryAcrossMetadataEndpoints(String header) {
        MockMvc mvc = mvc();
        for (String endpoint : new String[] {"current", "provisioning-status", "provision"}) {
            var request = endpoint.equals("provision")
                    ? post("/api/workspace/" + endpoint) : get("/api/workspace/" + endpoint);
            int expectedStatus = endpoint.equals("current") ? 204 : endpoint.equals("provision") ? 403 : 200;
            assertDoesNotThrow(() -> mvc.perform(request.header(WorkspaceContextResolver.WORKSPACE_HEADER, header)
                    .param(WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER, "inaccessible-stale-query"))
                    .andExpect(status().is(expectedStatus)));
        }
        verifyNoInteractions(manager);
        // Only provisioning-status legitimately resolves absent, read-only metadata.
        // The interceptor must not add a lookup based on the overridden query pin.
        verify(resolver).resolveCurrentWorkspaceMetadata();
        verify(resolver, never()).resolveCurrentRepositoryContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void emptyHeaderOverridesAStaleQueryForNonMetadataHandlers(String header) {
        var request = new MockHttpServletRequest();
        request.addHeader(WorkspaceContextResolver.WORKSPACE_HEADER, header);
        request.addParameter(WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER, "foreign-query");
        var interceptor = new ExplicitWorkspacePinValidationInterceptor(resolver);
        assertDoesNotThrow(() -> assertTrue(interceptor.preHandle(request,
                new org.springframework.mock.web.MockHttpServletResponse(), new Object())));
        verifyNoInteractions(resolver);
    }

}
