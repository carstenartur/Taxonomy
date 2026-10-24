package com.taxonomy.dsl;

import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationEvidence;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationEvidenceRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.relations.service.HypothesisService;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link HypothesisService}. */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class HypothesisServiceTest {

    @Autowired
    private HypothesisService hypothesisService;

    @Autowired
    private RelationHypothesisRepository hypothesisRepository;

    @Autowired
    private RelationEvidenceRepository evidenceRepository;

    @Autowired
    private SystemRepositoryService systemRepositoryService;

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private com.taxonomy.workspace.storage.DslGitRepositoryFactory gitRepositories;
    @Autowired private com.taxonomy.relations.service.HypothesisReviewStateStore reviewStates;
    @Autowired private com.taxonomy.relations.service.AnalysisHypothesisLinkPort links;
    @Autowired private com.taxonomy.architecture.service.ArchitectureSummaryService summaries;

    private RepositoryContext centralWrite;
    private RepositoryContext alpha;
    private RepositoryContext beta;

    @BeforeEach
    void setUpContexts() {
        SystemRepository primary = systemRepositoryService.getPrimaryRepository();
        String branch = primary.getDefaultBranch();
        centralWrite = RepositoryContext.centralWrite(
                primary.getRepositoryId(), branch, "system");
        alpha = RepositoryContext.workspace(
                primary.getRepositoryId(), "workspace-alpha", "draft", "alice");
        beta = RepositoryContext.workspace(
                primary.getRepositoryId(), "workspace-beta", "draft", "bob");
    }

    @Test
    void identicalSessionOnAnotherBranchHasIndependentAuthority() throws Exception {
        RepositoryContext main = RepositoryContext.workspace(
                alpha.repositoryId(), alpha.workspaceId(), "main", alpha.username());
        var dtos = List.of(new RelationHypothesisDto("BP", "P", "CP", "C",
                "SUPPORTS", 0.7, "branch evidence"));
        var selected = hypothesisService.persistFromAnalysis(dtos, "branch-overlap", alpha);
        var other = hypothesisService.persistFromAnalysis(dtos, "branch-overlap", main);
        assertThat(other).hasSize(1);
        assertThat(other.getFirst().getBranchName()).isEqualTo("main");
        var git=gitRepositories.resolveRepository(alpha);
        String selectedHead=git.getHeadCommit(alpha.branch());
        assertThat(selectedHead).isNotBlank();
        var mutations=org.mockito.Mockito.mock(com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.class);
        var reviews=new com.taxonomy.relations.service.GitAuthoritativeHypothesisReviewService(reviewStates,mutations);
        var command=new com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata("branch-regression","selected-head");
        assertThatThrownBy(()->reviews.accept(other.getFirst().getId(),alpha,selectedHead,command))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not found");
        assertThatThrownBy(()->reviews.reject(other.getFirst().getId(),alpha,selectedHead,command))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not found");
        org.mockito.Mockito.verifyNoInteractions(mutations);
        assertThat(git.getHeadCommit(alpha.branch())).isEqualTo(selectedHead);
        assertThat(reviewStates.require(other.getFirst().getId(),main).status()).isEqualTo(HypothesisStatus.PROVISIONAL);
        assertThat(hypothesisService.findAll(alpha)).extracting(RelationHypothesis::getId)
                .contains(selected.getFirst().getId()).doesNotContain(other.getFirst().getId());
        assertThatThrownBy(() -> hypothesisService.applyForSession(other.getFirst().getId(), alpha))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> hypothesisService.accept(other.getFirst().getId(), alpha))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> reviewStates.require(other.getFirst().getId(), alpha))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> hypothesisService.findEvidence(other.getFirst().getId(), alpha))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not found");
    }

    @Test
    void exactBranchUniquenessIncludesCentralAndAbsentSession() {
        var main = RepositoryContext.centralWrite(centralWrite.repositoryId(), " main ", "system");
        var upper = RepositoryContext.centralWrite(centralWrite.repositoryId(), "Main", "system");
        var draft = RepositoryContext.centralWrite(centralWrite.repositoryId(), "draft", "system");
        RelationHypothesis a = createTestHypothesis(null, main);
        RelationHypothesis b = createTestHypothesis(null, upper);
        RelationHypothesis c = createTestHypothesis(null, draft);
        assertThat(a.getBranchName()).isEqualTo("main");
        assertThat(b.getId()).isNotEqualTo(a.getId());
        assertThat(c.getId()).isNotEqualTo(a.getId());
        assertThatThrownBy(() -> createTestHypothesis(null, main))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> new RelationHypothesis().setBranchName("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evidenceCompositeForeignKeyRejectsAConflictingBranch() {
        var parent = hypothesisService.persistFromAnalysis(List.of(new RelationHypothesisDto(
                "BP", "P", "CP", "C", "SUPPORTS", .8, "FK evidence")), "evidence-fk", alpha).getFirst();
        assertThatThrownBy(() -> jdbc.update("UPDATE relation_evidence SET branch_name=? WHERE hypothesis_id=?",
                "other", parent.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(hypothesisService.findEvidence(parent.getId(), alpha)).singleElement().satisfies(e -> {
            assertThat(e.getRepositoryId()).isEqualTo(parent.getRepositoryId());
            assertThat(e.getWorkspaceScopeKey()).isEqualTo(parent.getWorkspaceScopeKey());
            assertThat(e.getBranchName()).isEqualTo(parent.getBranchName());
        });
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void snapshotLinkingSelectsOneBranchOfAnOverlappingSession() {
        var main = RepositoryContext.workspace(alpha.repositoryId(), alpha.workspaceId(), "main", alpha.username());
        var selected=createTestHypothesis("link-overlap", alpha);
        var foreign=createTestHypothesis("link-overlap", main);
        links.linkSnapshot(alpha.repositoryId(), alpha.workspaceId(), alpha.branch(), "link-overlap", 111L, 222L, "selected-snapshot");
        assertThat(selected.getAnalysisSnapshotId()).isEqualTo("selected-snapshot");
        assertThat(foreign.getAnalysisSnapshotId()).isNull();
    }

    @Test
    void visibleCountAndStatusKeepOnlySameBranchCentralBaseline() throws Exception {
        var sameCentral=RepositoryContext.centralWrite(alpha.repositoryId(),alpha.branch(),"system");
        var other=RepositoryContext.workspace(alpha.repositoryId(),alpha.workspaceId(),"summary-other",alpha.username());
        long before=hypothesisRepository.countVisibleInRepositoryWorkspaceAndBranch(alpha.repositoryId(),alpha.workspaceId(),alpha.branch());
        var baseline=createTestHypothesis("count-baseline",sameCentral);
        var own=createTestHypothesis("count-own",alpha);
        var foreign=createTestHypothesis("count-foreign",other);
        assertThat(summaries.buildSummary(alpha).totalHypotheses()).isEqualTo((int)before+2);
        var resolver=org.mockito.Mockito.mock(com.taxonomy.workspace.service.WorkspaceResolver.class);
        org.mockito.Mockito.when(resolver.resolveCurrentRepositoryContext()).thenReturn(alpha);
        var controller=new com.taxonomy.composition.architecture.ArchitectureSummaryApiController(summaries,
                org.mockito.Mockito.mock(com.taxonomy.architecture.service.DerivedMetadataService.class),
                org.mockito.Mockito.mock(com.taxonomy.catalog.service.TaxonomyService.class),resolver);
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build()
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/architecture/summary"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totalHypotheses").value((int)before+2));
        assertThat(hypothesisRepository.countVisibleInRepositoryWorkspaceAndBranch(alpha.repositoryId(),alpha.workspaceId(),alpha.branch())).isEqualTo(before+2);
        assertThat(hypothesisService.findByStatus(HypothesisStatus.PROVISIONAL,alpha)).extracting(RelationHypothesis::getId)
                .contains(baseline.getId(),own.getId()).doesNotContain(foreign.getId());
        assertThatThrownBy(() -> hypothesisService.applyForSession(baseline.getId(),alpha))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not found");
    }

    @Test
    void persistFromAnalysisSavesHypotheses() {
        List<RelationHypothesisDto> dtos = List.of(
                new RelationHypothesisDto("BP", "Process One", "CP", "Capability One",
                        "REALIZES", 0.80, "Inferred from compatibility matrix"));

        List<RelationHypothesis> persisted =
                hypothesisService.persistFromAnalysis(dtos, "test-persist-1", centralWrite);

        assertThat(persisted).hasSize(1);
        RelationHypothesis h = persisted.get(0);
        assertThat(h.getId()).isNotNull();
        assertThat(h.getRepositoryId()).isEqualTo(centralWrite.repositoryId());
        assertThat(h.getSourceNodeId()).isEqualTo("BP");
        assertThat(h.getTargetNodeId()).isEqualTo("CP");
        assertThat(h.getStatus()).isEqualTo(HypothesisStatus.PROVISIONAL);
        assertThat(h.getConfidence()).isEqualTo(0.80);
        assertThat(h.getAnalysisSessionId()).isEqualTo("test-persist-1");
        assertThat(h.getWorkspaceId()).isNull();
    }

    @Test
    void persistFromAnalysisCreatesEvidence() {
        List<RelationHypothesisDto> dtos = List.of(
                new RelationHypothesisDto("BP", "Process", "CP", "Capability",
                        "SUPPORTS", 0.70, "Test reasoning for evidence"));

        List<RelationHypothesis> persisted =
                hypothesisService.persistFromAnalysis(dtos, "test-evidence-1", centralWrite);
        assertThat(persisted).hasSize(1);

        List<RelationEvidence> evidence =
                hypothesisService.findEvidence(persisted.get(0).getId(), centralWrite);
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).getSummary()).isEqualTo("Test reasoning for evidence");
        assertThat(evidence.get(0).getEvidenceType()).isEqualTo("analysis-rule");
    }

    @Test
    void persistFromAnalysisSkipsDuplicatesInSameRepositorySessionAndWorkspace() {
        List<RelationHypothesisDto> dtos = List.of(
                new RelationHypothesisDto("BP", "P", "CP", "C",
                        "DEPENDS_ON", 0.60, "First"));

        List<RelationHypothesis> first =
                hypothesisService.persistFromAnalysis(dtos, "test-dedup-1", alpha);
        List<RelationHypothesis> second =
                hypothesisService.persistFromAnalysis(dtos, "test-dedup-1", alpha);
        List<RelationHypothesis> otherWorkspace =
                hypothesisService.persistFromAnalysis(dtos, "test-dedup-1", beta);

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
        assertThat(otherWorkspace).hasSize(1);
    }

    @Test
    void persistFromAnalysisWithNullOrEmptyInputReturnsEmpty() {
        assertThat(hypothesisService.persistFromAnalysis(
                null, null, centralWrite)).isEmpty();
        assertThat(hypothesisService.persistFromAnalysis(
                List.of(), null, centralWrite)).isEmpty();
    }

    @Test
    void acceptAndRejectChangeStatusWithinOwningRepositoryWorkspace() {
        RelationHypothesis acceptedInput = createTestHypothesis("test-accept-status", alpha);
        RelationHypothesis rejectedInput = createTestHypothesis("test-reject-status", alpha);

        assertThat(hypothesisService.accept(acceptedInput.getId(), alpha).getStatus())
                .isEqualTo(HypothesisStatus.ACCEPTED);
        assertThat(hypothesisService.reject(rejectedInput.getId(), alpha).getStatus())
                .isEqualTo(HypothesisStatus.REJECTED);
    }

    @Test
    void mutationFromAnotherWorkspaceIsHiddenAsNotFound() {
        RelationHypothesis hypothesis = createTestHypothesis("test-isolation", alpha);

        assertThatThrownBy(() -> hypothesisService.accept(hypothesis.getId(), beta))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> hypothesisService.reject(hypothesis.getId(), beta))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> hypothesisService.applyForSession(hypothesis.getId(), beta))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void centralReadContextCannotMutateHypotheses() {
        RepositoryContext centralRead = RepositoryContext.centralRead(
                centralWrite.repositoryId(), centralWrite.branch(), "reader");
        RelationHypothesis hypothesis = createTestHypothesis(
                "test-central-read-denial", centralWrite);

        assertThatThrownBy(() -> hypothesisService.accept(hypothesis.getId(), centralRead))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit central write context");
        assertThatThrownBy(() -> hypothesisService.reject(hypothesis.getId(), centralRead))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> hypothesisService.applyForSession(hypothesis.getId(), centralRead))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void evidenceFromAnotherWorkspaceIsNotVisible() {
        List<RelationHypothesis> persisted = hypothesisService.persistFromAnalysis(
                List.of(new RelationHypothesisDto("BP", "P", "CP", "C",
                        "FULFILLS", 0.75, "Evidence test reasoning")),
                "test-private-evidence", alpha);

        assertThatThrownBy(() -> hypothesisService.findEvidence(
                persisted.get(0).getId(), beta))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void workspaceListingsContainSameRepositoryCentralAndOwnButNotOtherWorkspace() {
        RelationHypothesis shared = createTestHypothesis("test-list-shared", centralWrite);
        RelationHypothesis alphaHypothesis = createTestHypothesis("test-list-alpha", alpha);
        RelationHypothesis betaHypothesis = createTestHypothesis("test-list-beta", beta);

        assertThat(hypothesisService.findAll(alpha))
                .extracting(RelationHypothesis::getId)
                .contains(shared.getId(), alphaHypothesis.getId())
                .doesNotContain(betaHypothesis.getId());
        assertThat(hypothesisService.findAll(centralWrite))
                .extracting(RelationHypothesis::getId)
                .contains(shared.getId())
                .doesNotContain(alphaHypothesis.getId(), betaHypothesis.getId());
    }

    @Test
    void invalidStateAndUnknownIdAreRejected() {
        RelationHypothesis accepted = createTestHypothesisWithStatus(
                "test-double-accept", HypothesisStatus.ACCEPTED, centralWrite);
        RelationHypothesis rejected = createTestHypothesisWithStatus(
                "test-double-reject", HypothesisStatus.REJECTED, centralWrite);

        assertThatThrownBy(() -> hypothesisService.accept(accepted.getId(), centralWrite))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> hypothesisService.reject(rejected.getId(), centralWrite))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> hypothesisService.accept(999999L, centralWrite))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findByStatusFiltersWithinRepositoryContext() {
        createTestHypothesisWithStatus(
                "test-filter-find", HypothesisStatus.PROPOSED, alpha);
        createTestHypothesisWithStatus(
                "test-filter-other", HypothesisStatus.PROPOSED, beta);

        List<RelationHypothesis> proposed =
                hypothesisService.findByStatus(HypothesisStatus.PROPOSED, alpha);
        assertThat(proposed).isNotEmpty();
        assertThat(proposed).allMatch(h -> h.getStatus() == HypothesisStatus.PROPOSED);
        assertThat(proposed).noneMatch(h -> "workspace-beta".equals(h.getWorkspaceId()));
    }

    private RelationHypothesis createTestHypothesis(
            String sessionId, RepositoryContext context) {
        return createTestHypothesisWithStatus(
                sessionId, HypothesisStatus.PROVISIONAL, context);
    }

    private RelationHypothesis createTestHypothesisWithStatus(
            String sessionId,
            HypothesisStatus status,
            RepositoryContext context) {
        RelationHypothesis h = new RelationHypothesis();
        h.setRepositoryId(context.repositoryId());
        h.setBranchName(context.branch());
        h.setSourceNodeId("CR");
        h.setTargetNodeId("CO");
        h.setRelationType(RelationType.REALIZES);
        h.setStatus(status);
        h.setConfidence(0.80);
        h.setAnalysisSessionId(sessionId);
        h.setWorkspaceId(context.workspaceId());
        h.setOwnerUsername(context.username());
        return hypothesisRepository.save(h);
    }
}
