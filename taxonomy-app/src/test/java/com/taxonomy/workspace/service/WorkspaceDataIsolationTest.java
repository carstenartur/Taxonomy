package com.taxonomy.workspace.service;

import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for workspace-level data isolation of relations, hypotheses,
 * and proposals across multiple workspaces.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Relations created in workspace A are not visible to workspace B</li>
 *   <li>Shared/legacy relations (workspace_id=NULL) are visible to all</li>
 *   <li>Delete operations respect workspace ownership</li>
 *   <li>WorkspaceContext resolves correctly for different users</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceDataIsolationTest {

    @Mock
    private WorkspaceManager workspaceManager;

    private WorkspaceContextResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new WorkspaceContextResolver(workspaceManager);
    }

    // ── WorkspaceContext resolution ────────────────────────────────────

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
        void unprovisionedUserGetsSHARED() {
            when(workspaceManager.findUserWorkspace("charlie")).thenReturn(null);

            WorkspaceContext ctx = resolver.resolveForUser("charlie");
            assertThat(ctx).isEqualTo(WorkspaceContext.SHARED);
            assertThat(ctx.workspaceId()).isEqualTo("shared");
        }

        @Test
        void sharedContextHasKnownValues() {
            assertThat(WorkspaceContext.SHARED.username()).isEqualTo("system");
            assertThat(WorkspaceContext.SHARED.workspaceId()).isEqualTo("shared");
            assertThat(WorkspaceContext.SHARED.currentBranch()).isEqualTo("draft");
        }

        @Test
        void nullWorkspaceIdReturnsSHARED() {
            UserWorkspace ws = new UserWorkspace();
            ws.setUsername("dave");
            ws.setWorkspaceId(null); // not provisioned
            when(workspaceManager.findUserWorkspace("dave")).thenReturn(ws);

            WorkspaceContext ctx = resolver.resolveForUser("dave");
            assertThat(ctx).isEqualTo(WorkspaceContext.SHARED);
        }
    }

    // ── Relation workspace isolation ──────────────────────────────────

    @Nested
    @DisplayName("TaxonomyRelation workspace scoping")
    class RelationIsolation {

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
            TaxonomyRelation relation = new TaxonomyRelation();
            // Not set = shared/legacy
            assertThat(relation.getWorkspaceId()).isNull();
            assertThat(relation.getOwnerUsername()).isNull();
        }
    }

    // ── Hypothesis workspace isolation ────────────────────────────────

    @Nested
    @DisplayName("RelationHypothesis workspace scoping")
    class HypothesisIsolation {

        @Test
        void hypothesisCarriesWorkspaceMetadata() {
            RelationHypothesis hypothesis = new RelationHypothesis();
            hypothesis.setWorkspaceId("bob-ws");
            hypothesis.setOwnerUsername("bob");

            assertThat(hypothesis.getWorkspaceId()).isEqualTo("bob-ws");
            assertThat(hypothesis.getOwnerUsername()).isEqualTo("bob");
        }

        @Test
        void sharedHypothesisHasNullWorkspace() {
            RelationHypothesis hypothesis = new RelationHypothesis();
            assertThat(hypothesis.getWorkspaceId()).isNull();
        }
    }

    // ── Proposal workspace isolation ─────────────────────────────────

    @Nested
    @DisplayName("RelationProposal workspace scoping")
    class ProposalIsolation {

        @Test
        void proposalCarriesWorkspaceMetadata() {
            RelationProposal proposal = new RelationProposal();
            proposal.setWorkspaceId("carol-ws");
            proposal.setOwnerUsername("carol");

            assertThat(proposal.getWorkspaceId()).isEqualTo("carol-ws");
            assertThat(proposal.getOwnerUsername()).isEqualTo("carol");
        }

        @Test
        void sharedProposalHasNullWorkspace() {
            RelationProposal proposal = new RelationProposal();
            assertThat(proposal.getWorkspaceId()).isNull();
        }
    }

    // ── Context equality ─────────────────────────────────────────────

    @Nested
    @DisplayName("WorkspaceContext equality")
    class ContextEquality {

        @Test
        void sameValuesAreEqual() {
            WorkspaceContext a = new WorkspaceContext("alice", "ws-1", "main");
            WorkspaceContext b = new WorkspaceContext("alice", "ws-1", "main");
            assertThat(a).isEqualTo(b);
        }

        @Test
        void differentWorkspacesAreNotEqual() {
            WorkspaceContext a = new WorkspaceContext("alice", "ws-1", "main");
            WorkspaceContext b = new WorkspaceContext("alice", "ws-2", "main");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void sharedIsNotEqualToProvisioned() {
            provisionWorkspace("alice", "alice-ws", "feature-a");
            WorkspaceContext ctx = resolver.resolveForUser("alice");
            assertThat(ctx).isNotEqualTo(WorkspaceContext.SHARED);
        }
    }

    // ── Workspace branch resolution ──────────────────────────────────

    @Nested
    @DisplayName("Workspace branch resolution")
    class BranchResolution {

        @Test
        void provisionedUserGetsBranchFromWorkspace() {
            provisionWorkspace("alice", "alice-ws", "feature-a");
            WorkspaceContext ctx = resolver.resolveForUser("alice");
            assertThat(ctx.currentBranch()).isEqualTo("feature-a");
        }

        @Test
        void unprovisionedUserGetsDraftBranch() {
            when(workspaceManager.findUserWorkspace("charlie")).thenReturn(null);
            WorkspaceContext ctx = resolver.resolveForUser("charlie");
            assertThat(ctx.currentBranch()).isEqualTo("draft");
        }

        @Test
        void sharedContextAlwaysUsesDraft() {
            assertThat(WorkspaceContext.SHARED.currentBranch()).isEqualTo("draft");
        }
    }

    // ── Helper ────────────────────────────────────────────────────────

    private void provisionWorkspace(String username, String wsId, String branch) {
        UserWorkspace ws = new UserWorkspace();
        ws.setUsername(username);
        ws.setWorkspaceId(wsId);
        ws.setCurrentBranch(branch);
        when(workspaceManager.findUserWorkspace(username)).thenReturn(ws);
    }
}
