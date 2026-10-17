package com.taxonomy.workspace.service;

import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.*;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;

import java.io.IOException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitNativeSyncSourceAuthorityTest {
    enum Operation { PULL, PUBLISH, EXPLICIT_PULL, EXPLICIT_PUBLISH, MERGE, TAKE_SHARED, KEEP_MINE }

    @ParameterizedTest @EnumSource(Operation.class)
    void removedPrivateSourceMembershipBlocksEverySyncBeforeAnySideEffect(Operation operation) throws Exception {
        var rows = mock(UserWorkspaceRepository.class);
        var catalog = mock(SystemRepositoryService.class);
        var factory = mock(DslGitRepositoryFactory.class);
        var states = mock(SyncStateRepository.class);
        var merger = mock(SemanticGitMergeService.class);
        var portfolio = mock(WorkspacePortfolioGitPort.class);
        var contexts = mock(WorkspaceContextResolver.class);
        var versions = mock(WorkspaceArchitectureVersionPort.class);
        var source = new SystemRepository();
        source.setRepositoryId("private-source");
        source.setVisibility(RepositoryVisibility.PRIVATE);
        source.setLifecycleState(RepositoryLifecycleState.ACTIVE);
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId("old-copy"); workspace.setUsername("alice");
        workspace.setSourceRepositoryId("private-source");
        when(rows.findByWorkspaceId("old-copy")).thenReturn(Optional.of(workspace));
        when(catalog.getRepository("private-source")).thenReturn(source);
        when(contexts.resolveRepositoryContextForUser("alice")).thenReturn(
                RepositoryContext.workspace("private-source", "old-copy", "main", "alice"));
        var memberships = new RepositoryMembershipService(mock(RepositoryMembershipRepository.class));
        assertFalse(memberships.canRead(source, "alice"));
        assertFalse(memberships.canContribute(source, "alice"));
        var service = new GitNativeSyncIntegrationService(states, rows, catalog, factory, merger, portfolio, contexts, versions, memberships);
        clearInvocations(factory);

        assertThrows(AccessDeniedException.class, () -> run(service, operation));

        verifyNoInteractions(factory, merger, states, versions);
        verify(portfolio, times(operation == Operation.MERGE ? 2 : 1)).requireAvailable(); verifyNoMoreInteractions(portfolio);
        verify(rows, never()).save(any()); verify(catalog, never()).save(any());
        assertNull(workspace.getCurrentCommit()); assertNull(workspace.getLastFetchedCommit());
    }

    @ParameterizedTest @EnumSource(value = Operation.class, names = {"PULL", "EXPLICIT_PULL", "MERGE", "TAKE_SHARED"})
    void contributorDowngradedToReaderCanStillPullFreshSource(Operation operation) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.member.setRole(RepositoryRole.READER);
            fixture.source.commitDsl("draft", dsl("Fresh source"), "owner", "After downgrade");
            run(fixture.service, operation);
            assertTrue(fixture.local.getDslAtHead("main").contains("Fresh source"));
        }
    }

    @ParameterizedTest @EnumSource(value = Operation.class, names = {"PUBLISH", "EXPLICIT_PUBLISH", "KEEP_MINE"})
    void contributorDowngradedToReaderCannotPublishOrChangeSourceMetadata(Operation operation) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.member.setRole(RepositoryRole.READER);
            fixture.local.commitDsl("main", dsl("Local edit"), "alice", "After downgrade");
            String head = fixture.source.getHeadCommit("draft");
            assertThrows(AccessDeniedException.class, () -> run(fixture.service, operation));
            assertEquals(head, fixture.source.getHeadCommit("draft"));
            verify(fixture.portfolio).requireAvailable(); verifyNoMoreInteractions(fixture.portfolio);
            verify(fixture.catalog, never()).save(any());
            verify(fixture.rows, never()).save(any());
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void primaryCompatibilityAndPublicReadRulesDoNotRestorePrivateContribution(boolean primary) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.sourceMetadata.setPrimaryRepo(primary);
            fixture.sourceMetadata.setVisibility(RepositoryVisibility.PUBLIC);
            when(fixture.memberRows.findByRepositoryIdAndUsername("private-source", "alice")).thenReturn(Optional.empty());
            fixture.source.commitDsl("draft", dsl("Public source"), "owner", "Public change");
            fixture.service.syncFromShared("alice", "main");
            assertTrue(fixture.local.getDslAtHead("main").contains("Public source"));
            fixture.local.commitDsl("main", dsl("Local edit"), "alice", "Local change");
            if (primary) fixture.service.publishToShared("alice", "main");
            else assertThrows(AccessDeniedException.class, () -> fixture.service.publishToShared("alice", "main"));
        }
    }

    private static String dsl(String title) { return "element A type System {\n  title: \"" + title + "\";\n}\n"; }
    private static final class Fixture implements AutoCloseable {
        final DslGitRepositoryFactory repositories = new DslGitRepositoryFactory(null);
        final com.taxonomy.workspace.storage.DslGitRepository source = repositories.getCentralRepository("private-source");
        final com.taxonomy.workspace.storage.DslGitRepository local = repositories.openWorkspaceRepository("old-copy");
        final RepositoryMembershipRepository memberRows = mock(RepositoryMembershipRepository.class);
        final RepositoryMembership member = new RepositoryMembership();
        final SystemRepository sourceMetadata = new SystemRepository();
        final SystemRepositoryService catalog = mock(SystemRepositoryService.class);
        final UserWorkspaceRepository rows = mock(UserWorkspaceRepository.class);
        final WorkspacePortfolioGitPort portfolio = mock(WorkspacePortfolioGitPort.class);
        final GitNativeSyncIntegrationService service;
        Fixture() throws Exception {
            sourceMetadata.setRepositoryId("private-source"); sourceMetadata.setSlug("private-source");
            sourceMetadata.setDefaultBranch("draft"); sourceMetadata.setVisibility(RepositoryVisibility.PRIVATE);
            when(catalog.getRepository("private-source")).thenReturn(sourceMetadata);
            member.setRepositoryId("private-source"); member.setUsername("alice"); member.setRole(RepositoryRole.CONTRIBUTOR);
            when(memberRows.findByRepositoryIdAndUsername("private-source", "alice")).thenReturn(Optional.of(member));
            var memberships = new RepositoryMembershipService(memberRows);
            assertTrue(memberships.canContribute(sourceMetadata, "alice"));
            var workspace = new UserWorkspace(); workspace.setWorkspaceId("old-copy"); workspace.setUsername("alice");
            workspace.setSourceRepositoryId("private-source"); workspace.setSourceBranch("draft"); workspace.setCurrentBranch("main");
            String base = source.commitDsl("draft", dsl("Original"), "owner", "Seed");
            workspace.setBaseCommit(base);
            local.commitDsl("main", dsl("Original"), "alice", "Seed");
            when(rows.findByWorkspaceId("old-copy")).thenReturn(Optional.of(workspace));
            var states = mock(SyncStateRepository.class);
            when(states.findByUsername("alice")).thenReturn(Optional.of(new SyncState()));
            var contexts = mock(WorkspaceContextResolver.class);
            when(contexts.resolveRepositoryContextForUser("alice")).thenReturn(RepositoryContext.workspace("private-source", "old-copy", "main", "alice"));
            var versions = new WorkspaceArchitectureVersionPort() {
                public <T> T version(RepositoryContext c, String rationale, GitAction<T> action) throws IOException { return action.run(); }
            };
            service = new GitNativeSyncIntegrationService(states, rows, catalog, repositories, new SemanticGitMergeService(), portfolio, contexts, versions, memberships);
        }
        public void close() { repositories.close(); }
    }

    private static String run(GitNativeSyncIntegrationService service, Operation op) throws IOException {
        return switch (op) {
            case PULL -> service.syncFromShared("alice", "main");
            case PUBLISH -> service.publishToShared("alice", "main");
            case EXPLICIT_PULL -> service.syncFromSharedToWorkspace("alice", "old-copy");
            case EXPLICIT_PUBLISH -> service.publishFromWorkspaceToShared("alice", "old-copy");
            case MERGE -> service.resolveDiverged("alice", "main", SyncIntegrationService.DivergedStrategy.MERGE);
            case TAKE_SHARED -> service.resolveDiverged("alice", "main", SyncIntegrationService.DivergedStrategy.TAKE_SHARED);
            case KEEP_MINE -> service.resolveDiverged("alice", "main", SyncIntegrationService.DivergedStrategy.KEEP_MINE);
        };
    }
}
