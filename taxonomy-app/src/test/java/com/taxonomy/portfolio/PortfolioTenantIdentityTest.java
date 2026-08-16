package com.taxonomy.portfolio;

import com.taxonomy.portfolio.model.PortfolioTenantIdentity;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortfolioTenantIdentityTest {

    @Test
    void repositoryWorkspaceAndBranchAreIndependentIsolationDimensions() {
        String repositoryAWorkspaceMain = PortfolioScope.key(
                "architect", context("repo-a", "workspace-1", "main"));
        String repositoryBWorkspaceMain = PortfolioScope.key(
                "architect", context("repo-b", "workspace-1", "main"));
        String repositoryAWorkspaceDraft = PortfolioScope.key(
                "architect", context("repo-a", "workspace-1", "draft"));
        String repositoryASecondWorkspace = PortfolioScope.key(
                "architect", context("repo-a", "workspace-2", "main"));

        assertThat(Set.of(
                repositoryAWorkspaceMain,
                repositoryBWorkspaceMain,
                repositoryAWorkspaceDraft,
                repositoryASecondWorkspace)).hasSize(4);
        assertThat(PortfolioTenantIdentity.parse(repositoryAWorkspaceMain))
                .isEqualTo(new PortfolioTenantIdentity(
                        "repo-a", "WORKSPACE:workspace-1", "main"));
    }

    @Test
    void centralScopeIsRepositoryAndBranchOwnedRatherThanUserOwned() {
        WorkspaceContext repositoryAMain = context("repo-a", null, "main");
        WorkspaceContext repositoryBMain = context("repo-b", null, "main");
        WorkspaceContext repositoryADraft = context("repo-a", null, "draft");

        assertThat(PortfolioScope.key("alice", repositoryAMain))
                .isEqualTo(PortfolioScope.key("bob", repositoryAMain));
        assertThat(PortfolioScope.key("alice", repositoryAMain))
                .isNotEqualTo(PortfolioScope.key("alice", repositoryBMain))
                .isNotEqualTo(PortfolioScope.key("alice", repositoryADraft));
        assertThat(PortfolioTenantIdentity.parse(PortfolioScope.key("alice", repositoryAMain))
                .workspaceScope()).isEqualTo(PortfolioScope.CENTRAL_SCOPE);
    }

    @Test
    void lengthPrefixRoundTripsSeparatorsAndUnicode() {
        PortfolioTenantIdentity identity = new PortfolioTenantIdentity(
                "repo|ä", "WORKSPACE:ws|一", "feature/a|β");

        assertThat(PortfolioTenantIdentity.parse(identity.scopeKey())).isEqualTo(identity);
    }

    @Test
    void missingRepositoryOrMalformedEncodingFailsClosed() {
        assertThatThrownBy(() -> PortfolioScope.key(
                "alice", new WorkspaceContext("alice", "ws", "main", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repositoryId");
        assertThatThrownBy(() -> PortfolioTenantIdentity.parse("workspace:ws"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v2");
        assertThatThrownBy(() -> PortfolioTenantIdentity.parse("v2|r6:repo-a|s2:x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static WorkspaceContext context(
            String repositoryId,
            String workspaceId,
            String branch) {
        return new WorkspaceContext("architect", workspaceId, branch, repositoryId);
    }
}
