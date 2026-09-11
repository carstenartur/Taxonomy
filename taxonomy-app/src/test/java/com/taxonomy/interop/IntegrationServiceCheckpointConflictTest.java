package com.taxonomy.interop;

import com.taxonomy.extension.api.integration.IntegrationContracts.AuthorityMode;
import com.taxonomy.extension.api.integration.IntegrationContracts.ExchangeDocument;
import com.taxonomy.extension.api.integration.IntegrationContracts.ExternalScope;
import com.taxonomy.extension.api.integration.IntegrationContracts.IntegrationContext;
import com.taxonomy.extension.api.integration.IntegrationContracts.InternalState;
import com.taxonomy.extension.api.integration.IntegrationContracts.OperationStatus;
import com.taxonomy.extension.api.integration.IntegrationContracts.ReviewedChangeSet;
import com.taxonomy.interop.oslc.OslcTransport;
import com.taxonomy.interop.persistence.IntegrationStore;
import com.taxonomy.interop.persistence.IntegrationStore.Operation;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceAccessService;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort;
import com.taxonomy.workspace.service.WorkspaceRevisionConflict;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

class IntegrationServiceCheckpointConflictTest {

    @Test
    void workspaceRevisionConflictBecomesTerminalCheckpointConflict() throws Exception {
        IntegrationStore store = mock(IntegrationStore.class);
        ExchangeConnectorRegistry connectors = mock(ExchangeConnectorRegistry.class);
        IntegrationDomainAdapter domain = mock(IntegrationDomainAdapter.class);
        WorkspaceArchitectureIntegrationPort editor = mock(WorkspaceArchitectureIntegrationPort.class);
        IntegrationDiff diff = mock(IntegrationDiff.class);
        IntegrationJson json = mock(IntegrationJson.class);
        SystemRepositoryService repositories = mock(SystemRepositoryService.class);
        RepositoryMembershipService memberships = mock(RepositoryMembershipService.class);
        OslcTransport remote = mock(OslcTransport.class);
        WorkspaceAccessService workspaceAccess = mock(WorkspaceAccessService.class);
        IntegrationService service = new IntegrationService(store, connectors, domain, editor, diff, json,
                repositories, memberships, remote, workspaceAccess);

        RepositoryContext context = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
        UUID connectionId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        InternalState state = new InternalState("repo", context.repositoryWorkspaceScopeKey(), "draft",
                "0123456789012345678901234567890123456789", 7, null, "project-state");
        IntegrationContext authority = new IntegrationContext(connectionId, AuthorityMode.BIDIRECTIONAL,
                new ExternalScope("Reference", "model", null), state, "alice", "archimate-3.1", "1");
        ExchangeDocument document = new ExchangeDocument("archimate-3.1", "1", "v1", true, "",
                List.of(), List.of(), List.of(), Map.of(), List.of());
        ReviewedChangeSet review = new ReviewedChangeSet(operationId, "preview-fingerprint", Map.of(),
                "Reviewed before a concurrent semantic edit advanced the workspace");
        Operation operation = new Operation(operationId, connectionId, authority, "INBOUND",
                OperationStatus.CHECKPOINT_PENDING, "operation-fingerprint", 1, document, List.of(), review,
                "review-fingerprint", null, 7L, document, state, null, null, Instant.now());

        SystemRepository repository = mock(SystemRepository.class);
        when(workspaceAccess.canUsePrivateWorkspace(context)).thenReturn(true);
        when(repositories.getRepository(context.repositoryId())).thenReturn(repository);
        when(memberships.canContribute(repository, context.username())).thenReturn(true);
        when(store.operation(context, connectionId, operationId)).thenReturn(operation);
        when(editor.checkpoint(eq(context), any(), any())).thenThrow(new WorkspaceRevisionConflict(7, 8));

        IntegrationStore.Session session = mock(IntegrationStore.Session.class);
        when(store.locked(eq(context), eq(connectionId), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<IntegrationStore.Session, Object> action = invocation.getArgument(2);
            return action.apply(session);
        });

        IntegrationProblem problem = assertThrows(IntegrationProblem.class,
                () -> service.retry(context, connectionId, operationId));

        assertEquals(409, problem.status());
        assertEquals("CHECKPOINT_CONFLICT", problem.code());
        verify(session).checkpointConflict(operationId);
        verify(session, never()).failure(operationId, "CHECKPOINT_RETRY_REQUIRED");
    }
}
