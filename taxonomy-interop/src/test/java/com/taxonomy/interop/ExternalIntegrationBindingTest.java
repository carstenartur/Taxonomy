package com.taxonomy.interop;

import com.taxonomy.extension.api.integration.IntegrationConnectorExtension;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.runtime.PluginCatalog;
import com.taxonomy.interop.persistence.IntegrationStore;
import com.taxonomy.interop.persistence.IntegrationStore.*;
import com.taxonomy.interop.oslc.OslcTransport;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalIntegrationBindingTest {
    @ParameterizedTest
    @EnumSource(value = AuthorityMode.class, names = {"IMPORT_COPY", "LINK_ONLY"})
    void replacementCannotApplyAnEmptyOrLinkOnlyReview(AuthorityMode mode) {
        var fixture = new Fixture(mode);
        var review = new ReviewedChangeSet(fixture.operation.id(), fixture.operation.fingerprint(), Map.of(), "Reviewed preview");
        var failure = assertThrows(IntegrationProblem.class, () -> fixture.service.apply(fixture.context, fixture.connection.id(), review));
        assertEquals("INTEGRATION_PLUGIN_CHANGED", failure.code());
        verify(fixture.session, never()).beginReview(any());
        verifyNoInteractions(fixture.editor);
    }

    @Test void readOnlyPluginRejectsPublicationAuthorityOnTheServer() {
        var fixture = new Fixture(AuthorityMode.IMPORT_COPY);
        var request = new IntegrationService.CreateConnection(UUID.randomUUID(), "Read only source", "example-model",
                AuthorityMode.BIDIRECTIONAL, new ExternalScope("EXAMPLE", "fixture", null), null, null, "1");
        var failure = assertThrows(IntegrationProblem.class, () -> fixture.service.create(fixture.context, request));
        assertEquals("AUTHORITY_MODE", failure.code());
        verify(fixture.store, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static final class Fixture {
        final RepositoryContext context = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
        final IntegrationStore store = mock(IntegrationStore.class);
        final IntegrationStore.Session session = mock(IntegrationStore.Session.class);
        final WorkspaceArchitectureIntegrationPort editor = mock(WorkspaceArchitectureIntegrationPort.class);
        final Connection connection;
        final Operation operation;
        final IntegrationService service;
        Fixture(AuthorityMode mode) {
            var catalog = new PluginCatalog();
            catalog.publish(new PluginDescriptor(new PluginIdentity("example.exchange", "1.0.0", "b".repeat(64)),
                    ">=1.3.0 & <2.0.0", List.of(), Set.of(), PluginMode.STARTUP), List.of(new Connector()));
            var registry = new ExchangeConnectorRegistry(List.of(), catalog);
            var repositories = mock(SystemRepositoryService.class);
            var memberships = mock(RepositoryMembershipService.class);
            var access = mock(WorkspaceAccessService.class);
            var repository = mock(SystemRepository.class);
            when(access.canUsePrivateWorkspace(context)).thenReturn(true);
            when(repositories.getRepository("repo")).thenReturn(repository);
            when(memberships.canContribute(repository, "alice")).thenReturn(true);
            connection = new Connection(UUID.randomUUID(), "USER:alice", "Fixture", "example-model", "1", mode,
                    new ExternalScope("EXAMPLE", "fixture", null), null, null, 0, null, null, "alice");
            var state = new InternalState("repo", context.repositoryWorkspaceScopeKey(), "draft", null, 0, null, null);
            var authority = new IntegrationContext(connection.id(), mode, connection.externalScope(), state, "alice", "example-model", "1");
            var document = new ExchangeDocument("example-model", "1", "snapshot", true, "", List.of(), List.of(), List.of(),
                    Map.of("taxonomy.plugin.id", "example.exchange", "taxonomy.plugin.version", "1.0.0", "taxonomy.plugin.sha256", "a".repeat(64)), List.of());
            operation = new Operation(UUID.randomUUID(), connection.id(), authority, "INBOUND", OperationStatus.PREVIEWED,
                    "preview", 1, document, List.of(), null, null, null, null, null, null, null, null, Instant.now());
            when(store.read(context, connection.id())).thenReturn(connection);
            when(session.connection()).thenReturn(connection);
            when(session.operation(operation.id())).thenReturn(operation);
            when(store.locked(eq(context), eq(connection.id()), any())).thenAnswer(invocation -> {
                Function<IntegrationStore.Session, Object> action = invocation.getArgument(2);
                return action.apply(session);
            });
            service = new IntegrationService(store, registry, mock(IntegrationDomainAdapter.class), editor,
                    mock(IntegrationDiff.class), new IntegrationJson(JsonMapper.builder().build()), repositories, memberships,
                    mock(OslcTransport.class), access);
        }
    }
    private static final class Connector implements IntegrationConnectorExtension {
        @Override public IntegrationDescriptor descriptor() {
            return new IntegrationDescriptor("example-model", "1", "Example model", Set.of(Capability.FILE_IMPORT), Set.of("application/example"));
        }
        @Override public ExchangeDocument previewInbound(InboundRequest request) { throw new UnsupportedOperationException(); }
        @Override public ExchangeFile previewOutbound(OutboundRequest request) { throw new UnsupportedOperationException(); }
    }
}
