package com.taxonomy.versioning.service;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationEvidenceRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.ReviewAction;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.ReviewResult;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceDslPublicationPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Contracts of the productive compatibility adapter, not just its lifecycle superclass. */
class GitAuthoritativeHypothesisServiceContractTest {

    private static final String HEAD = "a".repeat(40);
    private static final RepositoryContext CONTEXT =
            RepositoryContext.workspace("repo-a", "workspace-a", "review", "alice");

    private final RelationHypothesisRepository hypotheses = mock(RelationHypothesisRepository.class);
    private final RelationEvidenceRepository evidence = mock(RelationEvidenceRepository.class);
    private final TaxonomyRelationService relations = mock(TaxonomyRelationService.class);
    private final TaxonomyNodeRepository nodes = mock(TaxonomyNodeRepository.class);
    private final WorkspaceDslPublicationPort publication = mock(WorkspaceDslPublicationPort.class);
    private final SystemRepositoryService repositories = mock(SystemRepositoryService.class);
    private final UserWorkspaceRepository workspaces = mock(UserWorkspaceRepository.class);
    private final GitAuthoritativeHypothesisReviewService reviews =
            mock(GitAuthoritativeHypothesisReviewService.class);
    private final RelationBranchProjectionReadinessService readiness =
            mock(RelationBranchProjectionReadinessService.class);
    private final GitAuthoritativeHypothesisService service = new GitAuthoritativeHypothesisService(
            hypotheses, evidence, relations, nodes, publication, repositories, workspaces, reviews, readiness);

    @ParameterizedTest
    @EnumSource(ReviewAction.class)
    void explicitReviewForwardsTheCallersExactHeadAndMetadata(ReviewAction action) throws Exception {
        CommandMetadata metadata = new CommandMetadata("review-42", "Reviewed explicitly");
        ReviewResult result = result(action);
        when(reviewCall(reviews, action, CONTEXT, HEAD, metadata)).thenReturn(result);

        assertThat(service.review(42L, CONTEXT, HEAD, metadata, action)).isSameAs(result);

        reviewCall(verify(reviews), action, CONTEXT, HEAD, metadata);
        verifyNoMoreInteractions(reviews);
        verifyNoInteractions(readiness, publication, hypotheses, evidence, relations, nodes, repositories, workspaces);
    }

    @ParameterizedTest
    @EnumSource(ReviewAction.class)
    void compatibilityMutationChecksVisibilityBeforeReadingHeadAndReviewing(ReviewAction action)
            throws Exception {
        ReviewResult result = arrangeReadyReview(action, CONTEXT);

        RelationHypothesis transitioned = switch (action) {
            case ACCEPT -> service.accept(42L, CONTEXT);
            case REJECT -> service.reject(42L, CONTEXT);
            case REVERT -> service.revert(42L, CONTEXT);
        };

        assertThat(transitioned).isSameAs(result.hypothesis());
        InOrder order = inOrder(reviews, readiness);
        order.verify(reviews).requireReviewable(42L, CONTEXT, action);
        order.verify(readiness).inspect(CONTEXT);
        reviewCall(order.verify(reviews), action, CONTEXT, HEAD, compatibilityMetadata(action));
        order.verifyNoMoreInteractions();
        verifyNoInteractions(publication, hypotheses, evidence, relations, nodes, repositories, workspaces);
    }

    @Test
    void invisibleHypothesisFailsBeforeBranchStateCanBeObserved() {
        IllegalArgumentException hidden = new IllegalArgumentException("Hypothesis not found: 42");
        doThrow(hidden).when(reviews).requireReviewable(42L, CONTEXT, ReviewAction.ACCEPT);

        assertThatThrownBy(() -> service.accept(42L, CONTEXT)).isSameAs(hidden);

        verify(reviews).requireReviewable(42L, CONTEXT, ReviewAction.ACCEPT);
        verifyNoMoreInteractions(reviews);
        verifyNoInteractions(readiness, publication);
    }

    @Test
    void absentHeadNeverDispatchesAReviewOrPublishesASnapshot() {
        when(readiness.inspect(CONTEXT)).thenReturn(
                new Readiness(ReadinessState.BRANCH_MISSING, null, null, List.of()));

        assertThatThrownBy(() -> service.reject(42L, CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Selected hypothesis branch does not have an authoritative Git head");

        verify(reviews).requireReviewable(42L, CONTEXT, ReviewAction.REJECT);
        verifyNoMoreInteractions(reviews);
        verifyNoInteractions(publication);
    }

    @Test
    void compatibilityIoFailurePreservesItsCauseAndDoesNotRetry() throws Exception {
        when(readiness.inspect(CONTEXT)).thenReturn(ready());
        IOException failure = new IOException("Git unavailable");
        when(reviews.revert(42L, CONTEXT, HEAD, compatibilityMetadata(ReviewAction.REVERT)))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.revert(42L, CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Git hypothesis review failed before an authority commit was created")
                .hasCause(failure);

        verify(reviews).requireReviewable(42L, CONTEXT, ReviewAction.REVERT);
        verify(reviews).revert(42L, CONTEXT, HEAD, compatibilityMetadata(ReviewAction.REVERT));
        verifyNoMoreInteractions(reviews);
        verifyNoInteractions(publication);
    }

    @Test
    void explicitReviewPreservesIoFailureWithoutCompatibilityWrapping() throws Exception {
        CommandMetadata metadata = new CommandMetadata("review-42");
        IOException failure = new IOException("Git unavailable");
        when(reviews.accept(42L, CONTEXT, HEAD, metadata)).thenThrow(failure);

        assertThatThrownBy(() -> service.review(42L, CONTEXT, HEAD, metadata, ReviewAction.ACCEPT))
                .isSameAs(failure);

        verify(reviews).accept(42L, CONTEXT, HEAD, metadata);
        verifyNoMoreInteractions(reviews);
        verifyNoInteractions(readiness, publication);
    }

    @Test
    void nullContextOrActionFailsBeforeAccessingCollaborators() {
        assertThatThrownBy(() -> service.accept(42L, (RepositoryContext) null))
                .isInstanceOf(NullPointerException.class).hasMessage("context");
        assertThatThrownBy(() -> service.requireReviewable(42L, null, ReviewAction.ACCEPT))
                .isInstanceOf(NullPointerException.class).hasMessage("context");
        assertThatThrownBy(() -> service.review(42L, CONTEXT, HEAD, new CommandMetadata("review-42"), null))
                .isInstanceOf(NullPointerException.class).hasMessage("action");

        verifyNoInteractions(reviews, readiness, publication, hypotheses, repositories, workspaces);
    }

    @Test
    void legacyWorkspaceCallUsesNormalizedOwnerAndExplicitBranch() throws Exception {
        arrangeWorkspace("alice", "stored-branch", "repo-a");
        ReviewResult result = arrangeReadyReview(ReviewAction.ACCEPT, CONTEXT);

        assertThat(service.accept(42L, new WorkspaceContext(" alice ", " workspace-a ", " review ")))
                .isSameAs(result.hypothesis());

        verify(workspaces).findByWorkspaceId("workspace-a");
        verify(reviews).accept(42L, CONTEXT, HEAD, compatibilityMetadata(ReviewAction.ACCEPT));
        verifyNoInteractions(repositories, publication);
    }

    @Test
    void legacySystemCallUsesTheStoredWorkspaceBranch() throws Exception {
        arrangeWorkspace("bob", " stored-branch ", "repo-b");
        RepositoryContext selected = RepositoryContext.workspace("repo-b", "workspace-a", "stored-branch", "system");
        ReviewResult result = arrangeReadyReview(ReviewAction.REJECT, selected);

        assertThat(service.reject(42L, new WorkspaceContext("system", "workspace-a", null)))
                .isSameAs(result.hypothesis());

        verify(reviews).reject(42L, selected, HEAD, compatibilityMetadata(ReviewAction.REJECT));
        verifyNoInteractions(repositories, publication);
    }

    @Test
    void ownerlessLegacyWorkspaceDefaultsBlankActorAndMissingBranch() throws Exception {
        arrangeWorkspace(null, null, "repo-a");
        RepositoryContext selected = RepositoryContext.workspace("repo-a", "workspace-a", "draft", "system");
        ReviewResult result = arrangeReadyReview(ReviewAction.ACCEPT, selected);

        assertThat(service.accept(42L, new WorkspaceContext(" ", "workspace-a", " ")))
                .isSameAs(result.hypothesis());

        verify(reviews).accept(42L, selected, HEAD, compatibilityMetadata(ReviewAction.ACCEPT));
        verifyNoInteractions(repositories, publication);
    }

    @Test
    void missingLegacyWorkspaceFailsBeforeReviewOrBranchAccess() {
        when(workspaces.findByWorkspaceId("workspace-a")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reject(42L, new WorkspaceContext("alice", "workspace-a", "draft")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workspace not found while resolving repository context: workspace-a");

        verifyNoInteractions(reviews, readiness, publication, repositories);
    }

    @Test
    void foreignLegacyWorkspaceFailsBeforeReviewOrBranchAccess() {
        arrangeWorkspace("bob", "draft", "repo-a");

        assertThatThrownBy(() -> service.accept(42L, new WorkspaceContext("alice", "workspace-a", "draft")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workspace does not belong to the active user: workspace-a");

        verifyNoInteractions(reviews, readiness, publication, repositories);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void missingWorkspaceRepositoryProvenanceFailsClosed(String sourceRepositoryId) {
        arrangeWorkspace("alice", "draft", sourceRepositoryId);

        assertThatThrownBy(() -> service.accept(42L, new WorkspaceContext("alice", "workspace-a", "draft")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("workspace.sourceRepositoryId must not be blank");

        verifyNoInteractions(reviews, readiness, publication, repositories);
    }

    @Test
    void centralLegacySentinelUsesTheCatalogDefaultAndKeepsReadOnlyScope() {
        arrangePrimary();
        RepositoryContext selected = RepositoryContext.centralRead("primary-repo", "main", "system");
        IllegalStateException readOnly = new IllegalStateException("read-only");
        doThrow(readOnly).when(reviews).requireReviewable(42L, selected, ReviewAction.ACCEPT);

        assertThatThrownBy(() -> service.accept(42L, new WorkspaceContext(null, null, null)))
                .isSameAs(readOnly);

        verify(reviews).requireReviewable(42L, selected, ReviewAction.ACCEPT);
        verifyNoMoreInteractions(reviews);
        verifyNoInteractions(workspaces, readiness, publication);
    }

    @Test
    void nullLegacyContextUsesTheSharedDraftViewWithoutEscalatingScope() {
        arrangePrimary();
        RepositoryContext selected = RepositoryContext.centralRead("primary-repo", "draft", "system");
        IllegalStateException readOnly = new IllegalStateException("read-only");
        doThrow(readOnly).when(reviews).requireReviewable(42L, selected, ReviewAction.REJECT);

        assertThatThrownBy(() -> service.reject(42L, (WorkspaceContext) null)).isSameAs(readOnly);

        verify(reviews).requireReviewable(42L, selected, ReviewAction.REJECT);
        verifyNoMoreInteractions(reviews);
        verifyNoInteractions(workspaces, readiness, publication);
    }

    @Test
    void retryBindsTheFirstExactPersistedIdentityWithoutRepublishingOrReturningOldRows() {
        arrangeWorkspace("alice", "review", "repo-a");
        RelationHypothesisDto dto = dto();
        when(hypotheses.existsInRepositoryWorkspaceSession("repo-a",
                RelationHypothesis.scopeKeyFor("workspace-a"), RelationHypothesis.sessionScopeKeyFor("session-1"),
                "BP", "CP", RelationType.REALIZES)).thenReturn(true);
        when(hypotheses.findByAnalysisSessionIdInRepositoryWorkspace("repo-a", "workspace-a", "session-1"))
                .thenReturn(List.of(
                        hypothesis(11L, RelationType.SUPPORTS, "BP", "CP"),
                        hypothesis(12L, RelationType.REALIZES, "CR", "CP"),
                        hypothesis(13L, RelationType.REALIZES, "BP", "CO"),
                        hypothesis(42L, RelationType.REALIZES, "BP", "CP"),
                        hypothesis(99L, RelationType.REALIZES, "BP", "CP")));

        assertThat(service.persistFromAnalysis(List.of(dto), " session-1 ",
                new WorkspaceContext("alice", "workspace-a", "review"))).isEmpty();

        assertThat(dto.getHypothesisId()).isEqualTo(42L);
        verify(hypotheses).findByAnalysisSessionIdInRepositoryWorkspace("repo-a", "workspace-a", "session-1");
        verify(hypotheses, never()).save(any(RelationHypothesis.class));
        verifyNoInteractions(publication, evidence, nodes, relations, reviews, readiness, repositories);
    }

    @Test
    void generatedSessionBindsNewIdentityAndPublishesThroughTheConfiguredPort() throws Exception {
        RelationHypothesisDto dto = dto();
        when(hypotheses.save(any(RelationHypothesis.class))).thenAnswer(invocation -> {
            RelationHypothesis saved = invocation.getArgument(0);
            saved.setId(42L);
            return saved;
        });
        when(nodes.findByCode(anyString())).thenReturn(Optional.empty());

        List<RelationHypothesis> persisted = service.persistFromAnalysis(List.of(dto), " ", CONTEXT);

        assertThat(persisted).hasSize(1);
        assertThat(dto.getHypothesisId()).isEqualTo(42L);
        RelationHypothesis saved = persisted.getFirst();
        assertThat(saved.getRepositoryId()).isEqualTo("repo-a");
        assertThat(saved.getWorkspaceId()).isEqualTo("workspace-a");
        assertThat(saved.getAnalysisSessionId()).isNotBlank();
        verify(publication).publishSnapshot(org.mockito.ArgumentMatchers.eq(CONTEXT),
                org.mockito.ArgumentMatchers.eq("draft"),
                org.mockito.ArgumentMatchers.contains("analysis-session:" + saved.getAnalysisSessionId()),
                org.mockito.ArgumentMatchers.eq("Auto-generated from analysis session " + saved.getAnalysisSessionId()));
        verifyNoMoreInteractions(publication);
        verify(hypotheses, never()).findByAnalysisSessionIdInRepositoryWorkspace(anyString(), anyString(), anyString());
        verifyNoInteractions(repositories, workspaces, reviews, readiness);
    }

    @Test
    void emptyAnalysisNeverLooksUpIdentitiesOrPublishes() {
        assertThat(service.persistFromAnalysis(null, "session-1", CONTEXT)).isEmpty();
        assertThat(service.persistFromAnalysis(List.of(), "session-1", CONTEXT)).isEmpty();

        verifyNoInteractions(hypotheses, evidence, nodes, relations, publication, repositories, workspaces, reviews, readiness);
    }

    private ReviewResult arrangeReadyReview(ReviewAction action, RepositoryContext context) throws IOException {
        ReviewResult result = result(action);
        when(readiness.inspect(context)).thenReturn(ready());
        when(reviewCall(reviews, action, context, HEAD, compatibilityMetadata(action))).thenReturn(result);
        return result;
    }

    private static ReviewResult reviewCall(GitAuthoritativeHypothesisReviewService target, ReviewAction action,
                                           RepositoryContext context, String head, CommandMetadata metadata)
            throws IOException {
        return switch (action) {
            case ACCEPT -> target.accept(42L, context, head, metadata);
            case REJECT -> target.reject(42L, context, head, metadata);
            case REVERT -> target.revert(42L, context, head, metadata);
        };
    }

    private static ReviewResult result(ReviewAction action) {
        RelationHypothesis entity = hypothesis(42L, RelationType.REALIZES, "BP", "CP");
        entity.setStatus(action.targetStatus());
        return new ReviewResult(42L, action, entity, mock(MutationResult.class));
    }

    private static Readiness ready() {
        return new Readiness(ReadinessState.READY, HEAD, HEAD, List.of());
    }

    private static CommandMetadata compatibilityMetadata(ReviewAction action) {
        return new CommandMetadata("legacy-hypothesis-" + action.name().toLowerCase(Locale.ROOT) + "-42-" + HEAD,
                "Git-first hypothesis review through a compatibility call site");
    }

    private void arrangeWorkspace(String owner, String branch, String repositoryId) {
        UserWorkspace workspace = new UserWorkspace();
        workspace.setUsername(owner);
        workspace.setCurrentBranch(branch);
        workspace.setSourceRepositoryId(repositoryId);
        when(workspaces.findByWorkspaceId("workspace-a")).thenReturn(Optional.of(workspace));
    }

    private void arrangePrimary() {
        SystemRepository primary = mock(SystemRepository.class);
        when(primary.getRepositoryId()).thenReturn("primary-repo");
        when(primary.getDefaultBranch()).thenReturn("main");
        when(repositories.getPrimaryRepository()).thenReturn(primary);
    }

    private static RelationHypothesisDto dto() {
        return new RelationHypothesisDto("BP", "Business Processes", "CP", "Capabilities",
                "REALIZES", 0.8, null);
    }

    private static RelationHypothesis hypothesis(Long id, RelationType type, String source, String target) {
        RelationHypothesis entity = new RelationHypothesis();
        entity.setId(id);
        entity.setRelationType(type);
        entity.setSourceNodeId(source);
        entity.setTargetNodeId(target);
        return entity;
    }
}
