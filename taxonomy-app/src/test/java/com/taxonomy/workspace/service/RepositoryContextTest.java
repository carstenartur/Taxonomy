package com.taxonomy.workspace.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepositoryContextTest {

    @Test
    void centralContextAlwaysCarriesRepositoryIdentity() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-a", "main", "alice");

        assertEquals("repo-a", context.repositoryId());
        assertEquals("main", context.branch());
        assertEquals(RepositoryScope.CENTRAL_READ, context.scope());
    }

    @Test
    void workspaceContextRequiresRepositoryAndWorkspaceIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryContext.workspace("", "workspace-a", "main", "alice"));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryContext.workspace("repo-a", "", "main", "alice"));
    }

    @Test
    void workspaceScopeCannotBeConstructedWithoutWorkspace() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryContext(
                        "repo-a", null, "main", "alice", RepositoryScope.WORKSPACE));
    }
}
