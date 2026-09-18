package com.taxonomy.interop;

import com.taxonomy.exchange.OslcRequirementsCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.AuthorityMode;
import com.taxonomy.extension.api.integration.IntegrationContracts.ExchangeDocument;
import com.taxonomy.extension.api.integration.IntegrationContracts.ExternalScope;
import com.taxonomy.extension.api.integration.IntegrationContracts.IntegrationContext;
import com.taxonomy.extension.api.integration.IntegrationContracts.IntegrationDescriptor;
import com.taxonomy.extension.api.integration.IntegrationContracts.InternalState;
import com.taxonomy.extension.api.integration.IntegrationContracts.OperationStatus;
import com.taxonomy.extension.api.integration.LifecycleIntegrationConnector;
import com.taxonomy.interop.oslc.OslcTransport;
import com.taxonomy.interop.persistence.IntegrationStore;
import com.taxonomy.interop.persistence.IntegrationStore.Connection;
import com.taxonomy.interop.persistence.IntegrationStore.Operation;
import com.taxonomy.model.WorkspaceOverlayScope;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceAccessService;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.State;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.WorkspaceDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntegrationRemoteRetryTest {

    @Test
    void retryUsesTheValidatedFrozenAbsoluteResource() throws Exception {
        IntegrationStore store = mock(IntegrationStore.class);
        ExchangeConnectorRegistry connectors = mock(ExchangeConnectorRegistry.class);
        IntegrationDomainAdapter domain = mock(IntegrationDomainAdapter.class);
        WorkspaceArchitectureIntegrationPort editor = mock(WorkspaceArchitectureIntegrationPort.class);
        IntegrationDiff diff = mock(IntegrationDiff.class);
        IntegrationJson json = new IntegrationJson(JsonMapper.builder().build());
        SystemRepositoryService repositories = mock(SystemRepositoryService.class);
        RepositoryMembershipService memberships = mock(RepositoryMembershipService.class);
        OslcTransport remote = mock(OslcTransport.class);
        WorkspaceAccessService workspaceAccess = mock(WorkspaceAccessService.class);
        IntegrationService service = new IntegrationService(store, connectors, domain, editor, diff, json,
                repositories, memberships, remote, workspaceAccess);

        RepositoryContext context = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
        UUID connectionId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        String frozenResource = "https://old.example/rm/requirement-1";
        InternalState state = new InternalState(
                "repo", WorkspaceOverlayScope.keyFor(context.workspaceId()), "draft",
                "0123456789012345678901234567890123456789", 7, 7L, "project-state");
        ExternalScope externalScope = new ExternalScope(
                "Reference", "https://old.example/rm/", null);
        Connection connection = new Connection(
                connectionId, "USER:alice", "Reference", OslcRequirementsCodec.PROFILE, "1",
                AuthorityMode.BIDIRECTIONAL, externalScope, 7L, "reference",
                0, null, null, "alice");
        IntegrationContext authority = new IntegrationContext(
                connectionId, AuthorityMode.BIDIRECTIONAL, externalScope, state,
                "alice", OslcRequirementsCodec.PROFILE, "1");
        ExchangeDocument placeholder = new ExchangeDocument(
                OslcRequirementsCodec.PROFILE, "1", "\"v1\"", false, "",
                List.of(), List.of(), List.of(), Map.of(), List.of());
        Operation previewed = new Operation(
                operationId, connectionId, authority, "INBOUND", OperationStatus.PREVIEWED,
                "frozen-fingerprint", 0, placeholder, List.of(), null, null,
                null, null, null, null, null, null, Instant.now());

        SystemRepository repository = mock(SystemRepository.class);
        when(workspaceAccess.canUsePrivateWorkspace(context)).thenReturn(true);
        when(repositories.getRepository(context.repositoryId())).thenReturn(repository);
        when(memberships.canContribute(repository, context.username())).thenReturn(true);
        when(store.read(context, connectionId)).thenReturn(connection);
        when(remote.validate(context, connection, "requirement-1"))
                .thenReturn(URI.create(frozenResource));

        WorkspaceDocument workspaceDocument = new WorkspaceDocument(
                new State(state.workspaceScopeKey(), state.commitId(), state.semanticRevision()),
                "");
        when(editor.read(context, null)).thenReturn(workspaceDocument);
        when(domain.snapshot(eq(context), eq(connection), any(), eq(workspaceDocument)))
                .thenReturn(new IntegrationDomainAdapter.Snapshot(
                        state, Map.of(), List.of(), List.of()));

        IntegrationStore.Session session = mock(IntegrationStore.Session.class);
        when(session.find(operationId)).thenReturn(null);
        when(session.identities()).thenReturn(List.of());
        when(session.operation(operationId)).thenReturn(previewed);
        when(store.locked(eq(context), eq(connectionId), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<IntegrationStore.Session, Object> action = invocation.getArgument(2);
            return action.apply(session);
        });

        service.previewRemote(
                context, connectionId,
                new IntegrationService.RemoteRequest(
                        operationId, state, "requirement-1", "\"v1\""));

        ArgumentCaptor<ExchangeDocument> persistedDocument =
                ArgumentCaptor.forClass(ExchangeDocument.class);
        verify(session).preview(
                eq(operationId), any(), eq("INBOUND"), any(),
                persistedDocument.capture(), any());
        IntegrationService.RemoteRequest frozenRequest = json.read(
                persistedDocument.getValue().metadata().get("remoteRequest"),
                IntegrationService.RemoteRequest.class);
        assertEquals(frozenResource, frozenRequest.resource());

        Operation failed = new Operation(
                operationId, connectionId, authority, "INBOUND", OperationStatus.FETCH_FAILED,
                "frozen-fingerprint", 0, persistedDocument.getValue(), List.of(), null, null,
                null, null, null, null, null, "REMOTE_UNAVAILABLE", Instant.now());
        when(store.operation(context, connectionId, operationId)).thenReturn(failed);
        LifecycleIntegrationConnector connector = mock(LifecycleIntegrationConnector.class);
        when(connectors.require(OslcRequirementsCodec.PROFILE)).thenReturn(connector);
        when(connector.descriptor()).thenReturn(new IntegrationDescriptor(
                OslcRequirementsCodec.PROFILE, "1", "OSLC", Set.of(), Set.of()));
        when(remote.read(context, connection, frozenResource, "\"v1\""))
                .thenThrow(new IntegrationProblem(
                        "REMOTE_UNAVAILABLE", 502, "fixture failure"));

        IntegrationProblem failure = assertThrows(
                IntegrationProblem.class,
                () -> service.retry(context, connectionId, operationId));

        assertEquals("REMOTE_UNAVAILABLE", failure.code());
        verify(remote).read(context, connection, frozenResource, "\"v1\"");
        verify(session).fetchFailed(operationId, "REMOTE_UNAVAILABLE");
    }
    @Test
    void createRejectsMissingConnectorBeforeRegistryLookup() {
        IntegrationStore store = mock(IntegrationStore.class);
        ExchangeConnectorRegistry connectors = mock(ExchangeConnectorRegistry.class);
        IntegrationDomainAdapter domain = mock(IntegrationDomainAdapter.class);
        WorkspaceArchitectureIntegrationPort editor = mock(WorkspaceArchitectureIntegrationPort.class);
        IntegrationDiff diff = mock(IntegrationDiff.class);
        IntegrationJson json = new IntegrationJson(JsonMapper.builder().build());
        SystemRepositoryService repositories = mock(SystemRepositoryService.class);
        RepositoryMembershipService memberships = mock(RepositoryMembershipService.class);
        OslcTransport remote = mock(OslcTransport.class);
        WorkspaceAccessService workspaceAccess = mock(WorkspaceAccessService.class);
        IntegrationService service = new IntegrationService(store, connectors, domain, editor, diff, json,
                repositories, memberships, remote, workspaceAccess);

        RepositoryContext context = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
        SystemRepository repository = mock(SystemRepository.class);
        when(workspaceAccess.canUsePrivateWorkspace(context)).thenReturn(true);
        when(repositories.getRepository(context.repositoryId())).thenReturn(repository);
        when(memberships.canContribute(repository, context.username())).thenReturn(true);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(
                        context,
                        new IntegrationService.CreateConnection(
                                UUID.randomUUID(), "Missing connector", null,
                                AuthorityMode.BIDIRECTIONAL,
                                new ExternalScope("Reference", "https://example.invalid/", null),
                                null, null)));

        assertEquals(
                "Connection identity, connector, authority and external scope are required",
                failure.getMessage());
        verify(connectors, never()).require(any());
        verify(store, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }


    @Test
    void previewRejectsMissingMediaTypeBeforeImmutableSetLookup() {
        IntegrationStore store = mock(IntegrationStore.class);
        ExchangeConnectorRegistry connectors = mock(ExchangeConnectorRegistry.class);
        IntegrationDomainAdapter domain = mock(IntegrationDomainAdapter.class);
        WorkspaceArchitectureIntegrationPort editor = mock(WorkspaceArchitectureIntegrationPort.class);
        IntegrationDiff diff = mock(IntegrationDiff.class);
        IntegrationJson json = new IntegrationJson(JsonMapper.builder().build());
        SystemRepositoryService repositories = mock(SystemRepositoryService.class);
        RepositoryMembershipService memberships = mock(RepositoryMembershipService.class);
        OslcTransport remote = mock(OslcTransport.class);
        WorkspaceAccessService workspaceAccess = mock(WorkspaceAccessService.class);
        IntegrationService service = new IntegrationService(store, connectors, domain, editor, diff, json,
                repositories, memberships, remote, workspaceAccess);

        RepositoryContext context = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
        UUID connectionId = UUID.randomUUID();
        InternalState state = new InternalState(
                "repo", WorkspaceOverlayScope.keyFor(context.workspaceId()), "draft",
                "0123456789012345678901234567890123456789", 7, null, "project-state");
        Connection connection = new Connection(
                connectionId, "USER:alice", "ReqIF", "reqif-1.2", "1",
                AuthorityMode.BIDIRECTIONAL,
                new ExternalScope("Reference", "model", null),
                7L, null, 0, null, null, "alice");
        SystemRepository repository = mock(SystemRepository.class);
        LifecycleIntegrationConnector connector = mock(LifecycleIntegrationConnector.class);

        when(workspaceAccess.canUsePrivateWorkspace(context)).thenReturn(true);
        when(repositories.getRepository(context.repositoryId())).thenReturn(repository);
        when(memberships.canContribute(repository, context.username())).thenReturn(true);
        when(store.read(context, connectionId)).thenReturn(connection);
        when(connectors.require("reqif-1.2")).thenReturn(connector);
        when(connector.descriptor()).thenReturn(new IntegrationDescriptor(
                "reqif-1.2", "1", "ReqIF",
                Set.of(com.taxonomy.extension.api.integration.IntegrationContracts.Capability.FILE_IMPORT),
                Set.of("application/reqif+xml")));

        IntegrationProblem failure = assertThrows(
                IntegrationProblem.class,
                () -> service.preview(
                        context, connectionId,
                        new IntegrationService.PreviewRequest(
                                UUID.randomUUID(), state, null, true),
                        new byte[0]));

        assertEquals(415, failure.status());
        assertEquals("UNSUPPORTED_MEDIA", failure.code());
        verify(connector, never()).previewInbound(any());
    }


    @Test
    void remotePreviewRejectsStaleProfileBeforePersistingFetchState() {
        IntegrationStore store = mock(IntegrationStore.class);
        ExchangeConnectorRegistry connectors = mock(ExchangeConnectorRegistry.class);
        IntegrationDomainAdapter domain = mock(IntegrationDomainAdapter.class);
        WorkspaceArchitectureIntegrationPort editor = mock(WorkspaceArchitectureIntegrationPort.class);
        IntegrationDiff diff = mock(IntegrationDiff.class);
        IntegrationJson json = new IntegrationJson(JsonMapper.builder().build());
        SystemRepositoryService repositories = mock(SystemRepositoryService.class);
        RepositoryMembershipService memberships = mock(RepositoryMembershipService.class);
        OslcTransport remote = mock(OslcTransport.class);
        WorkspaceAccessService workspaceAccess = mock(WorkspaceAccessService.class);
        IntegrationService service = new IntegrationService(store, connectors, domain, editor, diff, json,
                repositories, memberships, remote, workspaceAccess);

        RepositoryContext context = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
        UUID connectionId = UUID.randomUUID();
        InternalState state = new InternalState(
                "repo", WorkspaceOverlayScope.keyFor(context.workspaceId()), "draft",
                "0123456789012345678901234567890123456789", 7, 7L, "project-state");
        Connection stale = new Connection(
                connectionId, "USER:alice", "Reference", OslcRequirementsCodec.PROFILE, "0",
                AuthorityMode.BIDIRECTIONAL,
                new ExternalScope("Reference", "https://example.invalid/rm/", null),
                7L, "reference", 0, null, null, "alice");
        SystemRepository repository = mock(SystemRepository.class);
        LifecycleIntegrationConnector connector = mock(LifecycleIntegrationConnector.class);

        when(workspaceAccess.canUsePrivateWorkspace(context)).thenReturn(true);
        when(repositories.getRepository(context.repositoryId())).thenReturn(repository);
        when(memberships.canContribute(repository, context.username())).thenReturn(true);
        when(store.read(context, connectionId)).thenReturn(stale);
        when(connectors.require(OslcRequirementsCodec.PROFILE)).thenReturn(connector);
        when(connector.descriptor()).thenReturn(new IntegrationDescriptor(
                OslcRequirementsCodec.PROFILE, "1", "OSLC", Set.of(), Set.of()));

        IntegrationProblem failure = assertThrows(
                IntegrationProblem.class,
                () -> service.previewRemote(
                        context, connectionId,
                        new IntegrationService.RemoteRequest(
                                UUID.randomUUID(), state, "requirement-1", null)));

        assertEquals("PROFILE_VERSION_CHANGED", failure.code());
        verify(remote, never()).validate(any(), any(), any());
        verify(store, never()).locked(any(), any(), any());
    }


    @Test
    void remotePreviewRejectsInvalidExpectedVersionBeforePersistingFetchState() {
        IntegrationStore store = mock(IntegrationStore.class);
        ExchangeConnectorRegistry connectors = mock(ExchangeConnectorRegistry.class);
        IntegrationDomainAdapter domain = mock(IntegrationDomainAdapter.class);
        WorkspaceArchitectureIntegrationPort editor = mock(WorkspaceArchitectureIntegrationPort.class);
        IntegrationDiff diff = mock(IntegrationDiff.class);
        IntegrationJson json = new IntegrationJson(JsonMapper.builder().build());
        SystemRepositoryService repositories = mock(SystemRepositoryService.class);
        RepositoryMembershipService memberships = mock(RepositoryMembershipService.class);
        OslcTransport remote = mock(OslcTransport.class);
        WorkspaceAccessService workspaceAccess = mock(WorkspaceAccessService.class);
        IntegrationService service = new IntegrationService(store, connectors, domain, editor, diff, json,
                repositories, memberships, remote, workspaceAccess);

        RepositoryContext context = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
        UUID connectionId = UUID.randomUUID();
        InternalState state = new InternalState(
                "repo", WorkspaceOverlayScope.keyFor(context.workspaceId()), "draft",
                "0123456789012345678901234567890123456789", 7, 7L, "project-state");
        Connection connection = new Connection(
                connectionId, "USER:alice", "Reference", OslcRequirementsCodec.PROFILE, "1",
                AuthorityMode.BIDIRECTIONAL,
                new ExternalScope("Reference", "https://example.invalid/rm/", null),
                7L, "reference", 0, null, null, "alice");
        SystemRepository repository = mock(SystemRepository.class);
        LifecycleIntegrationConnector connector = mock(LifecycleIntegrationConnector.class);

        when(workspaceAccess.canUsePrivateWorkspace(context)).thenReturn(true);
        when(repositories.getRepository(context.repositoryId())).thenReturn(repository);
        when(memberships.canContribute(repository, context.username())).thenReturn(true);
        when(store.read(context, connectionId)).thenReturn(connection);
        when(connectors.require(OslcRequirementsCodec.PROFILE)).thenReturn(connector);
        when(connector.descriptor()).thenReturn(new IntegrationDescriptor(
                OslcRequirementsCodec.PROFILE, "1", "OSLC", Set.of(), Set.of()));

        for (String invalid : List.of("bad\rversion", "x".repeat(2049))) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> service.previewRemote(
                            context, connectionId,
                            new IntegrationService.RemoteRequest(
                                    UUID.randomUUID(), state, "requirement-1", invalid)));
            assertEquals("Invalid external version", failure.getMessage());
        }

        verify(remote, never()).validate(any(), any(), any());
        verify(store, never()).locked(any(), any(), any());
    }


}
