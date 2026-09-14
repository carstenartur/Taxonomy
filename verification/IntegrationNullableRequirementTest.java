package com.taxonomy.interop;

import com.taxonomy.exchange.OslcRdf;
import com.taxonomy.exchange.ReqifExchangeCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.IntegrationPortfolioPort.*;
import com.taxonomy.interop.oslc.OslcProviderService;
import com.taxonomy.interop.persistence.IntegrationStore.Connection;
import com.taxonomy.interop.persistence.IntegrationStore.Identity;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceArchitectureReadPort.State;
import com.taxonomy.workspace.service.WorkspaceArchitectureReadPort.WorkspaceDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Optional metadata must not become an NPE, an invented version link or silent data loss. */
class IntegrationNullableRequirementTest {
    private final IntegrationPortfolioPort projects = mock(IntegrationPortfolioPort.class);
    private final IntegrationJson json = new IntegrationJson(JsonMapper.builder().build());
    private final IntegrationDomainAdapter domain = new IntegrationDomainAdapter(projects, json);
    private final RepositoryContext context = RepositoryContext.workspace("repo-a", "workspace-a", "draft", "alice");
    private final Connection connection = new Connection(UUID.randomUUID(), "USER:alice", "ReqIF", ReqifExchangeCodec.PROFILE, "1",
            AuthorityMode.BIDIRECTIONAL, new ExternalScope("remote", "scope", null), 7L, null, 0, null, null, "alice");
    private final WorkspaceDocument document = new WorkspaceDocument(new State("workspace-scope", "checkpoint", 1), "");
    private final Artifact artifact = new Artifact("external", ArtifactKind.REQUIREMENT, "requirement", "Title", "Body", Map.of(), Map.of());
    private final Identity mapping = new Identity("REQUIREMENT:external", "REQ-1", 9L, "v1", "fingerprint", artifact, artifact, UUID.randomUUID(), false);
    private final OslcProviderService provider = new OslcProviderService(projects, null, null, null, null);
    private final OslcProviderService.Links links = path -> "https://example.test/oslc" + path + "?scope=test";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private RequirementData requirement(String status, Long versionId, Instant updatedAt, VersionData version) {
        return new RequirementData(9L, "REQ-1", "Title", status, versionId, updatedAt, version);
    }

    @Test
    void snapshotFingerprintsPreserveNullMetadataRatherThanInventingValues() {
        var requirement = requirement(null, null, null, new VersionData("Body", null));
        when(projects.listRequirements(eq(7L), eq("alice"), any())).thenReturn(List.of(requirement));
        var result = assertDoesNotThrow(() -> domain.snapshot(context, connection, List.of(), document));
        var expected = new InternalState("repo-a", "workspace-scope", "draft", "checkpoint", 1, 7L,
                json.fingerprint(List.of(Arrays.asList(9L, "Title", null, null, null))));
        assertEquals(expected, result.state());
        assertEquals(requirement, result.requirements().getFirst());
    }

    @Test
    void snapshotRejectsMissingVersionInsteadOfTreatingMappedRequirementAsDeleted() {
        when(projects.listRequirements(eq(7L), eq("alice"), any()))
                .thenReturn(List.of(requirement("DRAFT", 10L, NOW, null)));
        var failure = assertThrows(IntegrationProblem.class,
                () -> domain.snapshot(context, connection, List.of(mapping), document));
        assertTrue(failure.getMessage().contains("current version"));
    }

    @Test
    void exportRejectsMissingVersionInsteadOfFabricatingEmptyRequirementText() {
        var snapshot = new IntegrationDomainAdapter.Snapshot(null, Map.of(),
                List.of(requirement("DRAFT", 10L, NOW, null)), List.of());
        var failure = assertThrows(IntegrationProblem.class,
                () -> domain.exportDocument(connection, snapshot, document, List.of(), null));
        assertTrue(failure.getMessage().contains("current version"));
    }

    @Test
    void applyRejectsMissingVersionBeforeIssuingAnyMutation() {
        when(projects.getRequirement(eq(7L), eq(9L), eq("alice"), any()))
                .thenReturn(requirement("DRAFT", 10L, NOW, null));
        var failure = assertThrows(IntegrationProblem.class,
                () -> domain.applyRequirement(context, connection, artifact, mapping, "Reviewed import"));
        assertTrue(failure.getMessage().contains("current version"));
        verify(projects).getRequirement(eq(7L), eq(9L), eq("alice"), any());
        verifyNoMoreInteractions(projects);
    }

    @Test
    void oslcOmitsUnknownModificationTimesForCurrentAndVersionResources() {
        when(projects.getRequirement(eq(7L), eq(9L), eq("alice"), any()))
                .thenReturn(requirement("APPROVED", 10L, null, new VersionData("Body", null)));
        for (Long version : Arrays.asList(null, 10L)) {
            var graph = assertDoesNotThrow(() -> provider.requirement(context, 7, 9, version, links));
            assertFalse(graph.triples().stream().anyMatch(t -> t.predicate().equals(OslcRdf.DCT + "modified")));
            assertTrue(graph.triples().stream().anyMatch(t -> t.predicate().equals(OslcRdf.DCT + "description") && t.value().equals("Body")));
        }
    }

    @Test
    void oslcDoesNotAdvertiseANullVersionIdentifier() {
        when(projects.getRequirement(eq(7L), eq(9L), eq("alice"), any()))
                .thenReturn(requirement("APPROVED", null, NOW, new VersionData("Body", NOW)));
        var graph = assertDoesNotThrow(() -> provider.requirement(context, 7, 9, null, links));
        assertFalse(graph.triples().stream().anyMatch(t -> t.predicate().equals(OslcRdf.DCT + "hasVersion")));
        assertFalse(graph.triples().stream().anyMatch(t -> t.subject().contains("/versions/null") || t.value().contains("/versions/null")));
        assertThrows(IntegrationProblem.class, () -> provider.requirement(context, 7, 9, 10L, links));
    }
}
