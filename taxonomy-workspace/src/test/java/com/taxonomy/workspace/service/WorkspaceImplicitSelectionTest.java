package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The implicit path must enforce the same ownership/lifecycle rules as request pins. */
@ExtendWith(MockitoExtension.class)
class WorkspaceImplicitSelectionTest {
    enum Selection { ACTIVE, LEGACY }

    @Mock private UserWorkspaceRepository repository;
    @Mock private SystemRepositoryService repositories;
    private WorkspaceManager manager;
    private WorkspaceContextResolver resolver;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        manager = new WorkspaceManager(repository, 10, repositories, mock(DslGitRepository.class));
        resolver = new WorkspaceContextResolver(manager, repositories, repository);
        // Let the old code reach a real canonical context: an unrelated null
        // repository exception must not serve as evidence of lifecycle rejection.
        lenient().when(repositories.getRepository("repo-a")).thenReturn(central());
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @EnumSource(Selection.class)
    void rejectsArchivedSelection(Selection selection) {
        select(selection).setArchived(true);
        assertUnavailable();
    }

    @ParameterizedTest
    @EnumSource(Selection.class)
    void rejectsSelectionWhoseOwnerChanged(Selection selection) {
        select(selection).setUsername("bob");
        assertUnavailable();
    }

    @ParameterizedTest
    @EnumSource(Selection.class)
    void rejectsSharedSelection(Selection selection) {
        select(selection).setShared(true);
        assertUnavailable();
    }

    @ParameterizedTest
    @EnumSource(Selection.class)
    void rejectsSelectionWithoutOwner(Selection selection) {
        select(selection).setUsername(null);
        assertUnavailable();
    }

    @Test
    void archivingAnActiveWorkspaceDoesNotReenterItThroughLegacyFallback() {
        UserWorkspace workspace = select(Selection.ACTIVE);
        manager.archiveWorkspace("workspace-a", "alice");
        when(repository.findByUsernameAndSharedFalse("alice"))
                .thenReturn(Optional.of(workspace));
        assertThat(workspace.isArchived()).isTrue();
        assertUnavailable();
    }

    @ParameterizedTest
    @EnumSource(Selection.class)
    void retainsValidImplicitSelection(Selection selection) {
        select(selection);
        assertThat(resolver.resolveRepositoryContextForUser("alice"))
                .isEqualTo(RepositoryContext.workspace("repo-a", "workspace-a", "draft", "alice"));
        WorkspaceContext legacy = resolver.resolveForUser("alice");
        assertThat(legacy.username()).isEqualTo("alice");
        assertThat(legacy.workspaceId()).isEqualTo("workspace-a");
        assertThat(legacy.branch()).isEqualTo("draft");
    }

    @Test
    void noProvisionedWorkspaceRetainsTheExplicitCentralReadFallback() {
        when(repository.findByUsernameAndSharedFalse("alice")).thenReturn(Optional.empty());
        when(repositories.getPrimaryRepository()).thenReturn(central());
        assertThat(resolver.resolveRepositoryContextForUser("alice"))
                .isEqualTo(RepositoryContext.centralRead("repo-a", "main", "alice"));
        assertThat(resolver.resolveForUser("alice")).isEqualTo(WorkspaceContext.SHARED);
    }

    private UserWorkspace select(Selection selection) {
        UserWorkspace workspace = new UserWorkspace();
        workspace.setUsername("alice");
        workspace.setWorkspaceId("workspace-a");
        workspace.setCurrentBranch("draft");
        workspace.setSourceRepositoryId("repo-a");
        if (selection == Selection.ACTIVE) {
            manager.getOrCreateWorkspace("alice", "workspace-a");
            when(repository.findByWorkspaceId("workspace-a")).thenReturn(Optional.of(workspace));
        } else {
            when(repository.findByUsernameAndSharedFalse("alice")).thenReturn(Optional.of(workspace));
        }
        return workspace;
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> resolver.resolveRepositoryContextForUser("alice"))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("not available");
        assertThatThrownBy(() -> resolver.resolveForUser("alice"))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("not available");
        verifyNoInteractions(repositories);
    }

    private static SystemRepository central() {
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId("repo-a");
        repository.setDefaultBranch("main");
        return repository;
    }
}
