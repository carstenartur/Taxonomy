package com.taxonomy.workspace.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RepositoryContextTest {

    @Test
    void contextCanonicalizesRoutingIdentifiers() {
        RepositoryContext context = RepositoryContext.workspace(
                " repo-a ", " workspace-a ", " feature/a ", " alice ");

        assertThat(context.repositoryId()).isEqualTo("repo-a");
        assertThat(context.workspaceId()).isEqualTo("workspace-a");
        assertThat(context.branch()).isEqualTo("feature/a");
        assertThat(context.username()).isEqualTo("alice");
    }

    @Test
    void workspaceScopeKeyPreservesPersistedRoutingIdentityAndIgnoresActor() {
        RepositoryContext alice = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        RepositoryContext bob = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "bob");
        RepositoryContext anotherBranch = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/b", "alice");

        assertThat(alice.workspaceScopeKey())
                .isEqualTo("e292cc8d7644151fe4311a598f1a90c5fb243486f79ab6e9dbc9969e775b0177")
                .isEqualTo(bob.workspaceScopeKey())
                .isNotEqualTo(anotherBranch.workspaceScopeKey());
    }

    @Test
    void everyNonWorkspaceContextRejectsWorkspaceIdentity() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RepositoryContext(
                "repo-a",
                "workspace-a",
                "main",
                "alice",
                RepositoryScope.CENTRAL_READ));
        assertThatIllegalArgumentException().isThrownBy(() -> new RepositoryContext(
                "repo-a",
                "workspace-a",
                "main",
                "alice",
                RepositoryScope.CENTRAL_WRITE));
        assertThatIllegalArgumentException().isThrownBy(() -> new RepositoryContext(
                "repo-a",
                "workspace-a",
                "main",
                "alice",
                RepositoryScope.FORK));
    }

    @Test
    void centralWriteFactoryCreatesAnExplicitNonWorkspaceScope() {
        RepositoryContext context = RepositoryContext.centralWrite(
                "repo-a", "main", "alice");

        assertThat(context.repositoryId()).isEqualTo("repo-a");
        assertThat(context.workspaceId()).isNull();
        assertThat(context.scope()).isEqualTo(RepositoryScope.CENTRAL_WRITE);
    }

    @Test
    void workspaceContextRequiresWorkspaceIdentity() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RepositoryContext(
                "repo-a",
                null,
                "main",
                "alice",
                RepositoryScope.WORKSPACE));
    }

    @Test
    void contextRejectsMissingRepositoryBranchUsernameAndScope() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                RepositoryContext.centralRead(" ", "main", "alice"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                RepositoryContext.centralRead("repo-a", " ", "alice"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                RepositoryContext.centralRead("repo-a", "main", " "));
        assertThatIllegalArgumentException().isThrownBy(() -> new RepositoryContext(
                "repo-a", null, "main", "alice", null));
    }
}
