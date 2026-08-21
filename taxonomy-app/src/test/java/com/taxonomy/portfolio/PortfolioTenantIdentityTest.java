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
        WorkspaceContext repositoryAWorkspaceMainContext =
                context("repo-a", "workspace-1", "main");
        String repositoryAWorkspaceMain = PortfolioScope.key(
                "architect", repositoryAWorkspaceMainContext);
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
        PortfolioTenantIdentity expected = new PortfolioTenantIdentity(
                "repo-a", "WORKSPACE:workspace-1", "main");
        assertThat(PortfolioTenantIdentity.parse(repositoryAWorkspaceMain))
                .isEqualTo(expected);
        assertThat(PortfolioScope.identity("architect", repositoryAWorkspaceMainContext))
                .isEqualTo(expected);
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
    void compatibilityHelpersNormalizeOptionalUserAndWorkspaceValues() {
        WorkspaceContext contextualUser = new WorkspaceContext(
                " Alice ", "  ", " main ", " repo-a ");

        assertThat(PortfolioScope.username(null, contextualUser)).isEqualTo("alice");
        assertThat(PortfolioScope.username(" BOB ", contextualUser)).isEqualTo("bob");
        assertThat(PortfolioScope.username(null, null)).isEqualTo("anonymous");
        assertThat(PortfolioScope.workspaceId(contextualUser)).isNull();
        assertThat(PortfolioScope.repositoryId(contextualUser)).isEqualTo("repo-a");
        assertThat(PortfolioScope.branch(contextualUser)).isEqualTo("main");
    }

    @Test
    void missingContextRepositoryOrBranchFailsClosed() {
        assertThatThrownBy(() -> PortfolioScope.identity("alice", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace context");
        assertThatThrownBy(() -> PortfolioScope.repositoryId(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace context");
        assertThatThrownBy(() -> PortfolioScope.key(
                "alice", new WorkspaceContext("alice", "ws", "main", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repositoryId");
        assertThatThrownBy(() -> PortfolioScope.branch(
                new WorkspaceContext("alice", "ws", " ", "repo-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currentBranch");
    }

    @Test
    void malformedTenantComponentsAndEncodingsFailClosed() {
        assertThatThrownBy(() -> new PortfolioTenantIdentity(
                "repo-a", "WORKSPACE:", "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceScope");
        assertThatThrownBy(() -> new PortfolioTenantIdentity(
                "repo-a", "OTHER", "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceScope");
        assertThatThrownBy(() -> PortfolioTenantIdentity.parse("workspace:ws"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v2");
        assertThatThrownBy(() -> PortfolioTenantIdentity.parse("v2|x6:repo-a|s7:CENTRAL|b4:main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("component r");
        assertThatThrownBy(() -> PortfolioTenantIdentity.parse("v2|rX:repo-a|s7:CENTRAL|b4:main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid length");
        assertThatThrownBy(() -> PortfolioTenantIdentity.parse("v2|r0:|s7:CENTRAL|b4:main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> PortfolioTenantIdentity.parse("v2|r6:repo-a|s7:CENTRAL|b9:main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("truncated");
        assertThatThrownBy(() -> PortfolioTenantIdentity.parse(
                "v2|r6:repo-a|s7:CENTRAL|b4:main-extra"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing data");
    }

    private static WorkspaceContext context(
            String repositoryId,
            String workspaceId,
            String branch) {
        return new WorkspaceContext("architect", workspaceId, branch, repositoryId);
    }
}
