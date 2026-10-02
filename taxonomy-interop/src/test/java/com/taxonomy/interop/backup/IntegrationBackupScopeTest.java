package com.taxonomy.interop.backup;

import com.taxonomy.backup.BackupIntegrationScope;
import com.taxonomy.backup.BackupRepositoryKey;
import com.taxonomy.editor.ArchitectureCommandPort;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationBackupScopeTest {
    @Test void matchesThePersistedInteropRoutingIdentityWithoutConflatingSiblingScopes() {
        var contexts = List.of(
                RepositoryContext.workspace("repo-a", "private-a", "draft", "alice"),
                RepositoryContext.workspace("repo-a", "private-b", "draft", "alice"),
                RepositoryContext.workspace("repo-b", "private-a", "draft", "alice"),
                RepositoryContext.workspace("repo-a", "private-a", "feature", "alice"),
                RepositoryContext.workspace("répo", "私用", "feature/ä", "alice"));
        var hashes = new HashSet<String>();
        for (var context : contexts) {
            var selection = new BackupIntegrationScope(new BackupRepositoryKey(context.repositoryId(), context.workspaceId()), context.branch());
            assertEquals(context.repositoryWorkspaceScopeKey(), selection.scopeId());
            assertEquals(64, selection.scopeId().length());
            assertTrue(hashes.add(selection.scopeId()));
            var otherActor = RepositoryContext.workspace(context.repositoryId(), context.workspaceId(), context.branch(), "bob");
            assertEquals(otherActor.repositoryWorkspaceScopeKey(), selection.scopeId());
        }
    }

    @Test void theJournalHashIsNotTheEmbeddedArchitectureWorkspaceKey() {
        var context = RepositoryContext.workspace("repo-a", "private-a", "draft", "alice");
        var selection = new BackupIntegrationScope(new BackupRepositoryKey("repo-a", "private-a"), "draft");
        var state = ArchitectureCommandPort.Context.of(context, null);
        assertEquals(selection.repository().workspaceId(), state.workspaceScopeKey());
        assertNotEquals(state.workspaceScopeKey(), selection.scopeId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " draft", "draft ", "draft\nother", "draft\u0000other"})
    void refusesUncanonicalBranchesInsteadOfChangingThePersistedIdentity(String branch) {
        assertThrows(IllegalArgumentException.class, () -> new BackupIntegrationScope(new BackupRepositoryKey("repo", "workspace"), branch));
    }

    @Test void centralRepositoriesAndMissingIdentityCannotOwnInteropJournals() {
        assertThrows(IllegalArgumentException.class, () -> new BackupIntegrationScope(new BackupRepositoryKey("repo", null), "draft"));
        assertThrows(NullPointerException.class, () -> new BackupIntegrationScope(null, "draft"));
        assertThrows(NullPointerException.class, () -> new BackupIntegrationScope(new BackupRepositoryKey("repo", "workspace"), null));
    }
}
