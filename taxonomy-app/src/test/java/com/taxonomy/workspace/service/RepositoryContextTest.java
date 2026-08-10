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
