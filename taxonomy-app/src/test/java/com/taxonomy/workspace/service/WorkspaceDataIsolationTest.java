package com.taxonomy.workspace.service;

import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.workspace.model.UserWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for workspace-level data isolation metadata and explicit legacy
 * shared-mode resolution.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceDataIsolationTest {

    @Mock
    private WorkspaceManager workspaceManager;

    @Mock
    private SystemRepositoryService systemRepositoryService;

    private WorkspaceContextResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new WorkspaceContextResolver(
                workspaceManager, systemRepositoryService, true);
    }

    @Nested
    @DisplayName("WorkspaceContext resolution")
    class ContextResolution {

        @Test
        void aliceAndBobGetDifferentContexts() {
            provisionWorkspace("alice", "alice-ws", "alice/workspace");
            provisionWorkspace("bob", "bob-ws", "bob/workspace");

            WorkspaceContext aliceCtx = resolver.resolveForUser("alice");
            WorkspaceContext bobCtx = resolver.resolveForUser("bob");

            assertThat(aliceCtx.workspaceId()).isEqualTo("alice-ws");
            assertThat(bobCtx.workspaceId()).isEqualTo("bob-ws");
            assertThat(aliceCtx.workspaceId()).isNotEqualTo(bobCtx.workspaceId());
            assertThat(aliceCtx.currentBranch()).isNotEqualTo(bobCtx.currentBranch());
        }

        @Test
        void explicitlySharedUnprovisionedUserGetsSharedContext() {
            when(workspaceManager.findActiveWorkspace("charlie")).thenReturn(null);
            when(workspaceManager.findUserWorkspace("charlie")).thenReturn(null);

            WorkspaceContext context = resolver.resolveForUser("charlie");
            assertThat(context).isEqualTo(WorkspaceContext.SHARED);
            assertThat(context.workspaceId()).isNull();
        }

        @Test
        void sharedContextHasNullWorkspaceId() {
            assertThat(WorkspaceContext.SHARED.username()).isEqualTo("system");
            assertThat(WorkspaceContext.SHARED.workspaceId()).isNull();
            assertThat(WorkspaceContext.SHARED.currentBranch()).isEqualTo("draft");
        }

        @Test
        void nullWorkspaceIdUsesExplicitSharedMode() {
            UserWorkspace workspace = new UserWorkspace();
            workspace.setUsername("dave");
            workspace.setWorkspaceId(null);
            when(workspaceManager.findActiveWorkspace("dave")).thenReturn(null);
            when(workspaceManager.findUserWorkspace("dave")).thenReturn(workspace);

            assertThat(resolver.resolveForUser("dave"))
                    .isEqualTo(WorkspaceContext.SHARED);
        }
    }

    @Nested
    @DisplayName("Workspace-scoped entity metadata")
    class EntityMetadata {

        @Test
        void relationCarriesWorkspaceMetadata() {
            TaxonomyRelation relation = new TaxonomyRelation();
            relation.setWorkspaceId("alice-ws");
            relation.setOwnerUsername("alice");

            assertThat(relation.getWorkspaceId()).isEqualTo("alice-ws");
            assertThat(relation.getOwnerUsername()).isEqualTo("alice");
        }

        @Test
        void sharedRelationHasNullWorkspaceId() {
            assertThat(new TaxonomyRelation().getWorkspaceId()).isNull();
        }

        @Test
        void hypothesisCarriesWorkspaceMetadata() {
            RelationHypothesis hypothesis = new RelationHypothesis();
            hypothesis.setWorkspaceId("bob-ws");
            hypothesis.setOwnerUsername("bob");

            assertThat(hypothesis.getWorkspaceId()).isEqualTo("bob-ws");
            assertThat(hypothesis.getOwnerUsername()).isEqualTo("bob");
        }

        @Test
        void proposalCarriesWorkspaceMetadata() {
            RelationProposal proposal = new RelationProposal();
            proposal.setWorkspaceId("carol-ws");
            proposal.setOwnerUsername("carol");

            assertThat(proposal.getWorkspaceId()).isEqualTo("carol-ws");
            assertThat(proposal.getOwnerUsername()).isEqualTo("carol");
        }
    }

    @Nested
    @DisplayName("WorkspaceContext equality and branches")
    class ContextEqualityAndBranches {

        @Test
        void sameValuesAreEqual() {
            WorkspaceContext left = new WorkspaceContext("alice", "ws-1", "main");
            WorkspaceContext right = new WorkspaceContext("alice", "ws-1", "main");
            assertThat(left).isEqualTo(right);
        }

        @Test
        void differentWorkspacesAreNotEqual() {
            WorkspaceContext left = new WorkspaceContext("alice", "ws-1", "main");
            WorkspaceContext right = new WorkspaceContext("alice", "ws-2", "main");
            assertThat(left).isNotEqualTo(right);
        }

        @Test
        void provisionedUserGetsWorkspaceBranch() {
            provisionWorkspace("alice", "alice-ws", "feature-a");
            assertThat(resolver.resolveForUser("alice").currentBranch())
                    .isEqualTo("feature-a");
        }

        @Test
        void workspaceWithoutBranchUsesConfiguredSharedBranchName() {
            UserWorkspace workspace = new UserWorkspace();
            workspace.setUsername("eve");
            workspace.setWorkspaceId("eve-ws");
            workspace.setCurrentBranch("");
            when(workspaceManager.findActiveWorkspace("eve")).thenReturn(workspace);
            when(systemRepositoryService.getSharedBranch()).thenReturn("draft");

            assertThat(resolver.resolveForUser("eve").currentBranch())
                    .isEqualTo("draft");
        }
    }

    private void provisionWorkspace(String username, String workspaceId, String branch) {
        UserWorkspace workspace = new UserWorkspace();
        workspace.setUsername(username);
        workspace.setWorkspaceId(workspaceId);
        workspace.setCurrentBranch(branch);
        when(workspaceManager.findActiveWorkspace(username)).thenReturn(workspace);
    }
}
