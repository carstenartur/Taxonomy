package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Database-backed regression evidence for repository/workspace quality isolation.
 *
 * <p>Unlike the focused mock tests, these tests execute the actual JPQL queries
 * and lifecycle normalization used by every supported database profile.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "quality-admin", roles = {"USER", "ARCHITECT", "ADMIN"})
class RelationQualityRepositoryIsolationTests {

    private static final String REPOSITORY_A = "qa-quality-repository-a";
    private static final String REPOSITORY_B = "qa-quality-repository-b";
    private static final String WORKSPACE_A1 = "qa-quality-workspace-a1";
    private static final String WORKSPACE_A2 = "qa-quality-workspace-a2";

    @Autowired private TaxonomyNodeRepository nodeRepository;
    @Autowired private RelationProposalRepository proposalRepository;
    @Autowired private SystemRepositoryRepository systemRepositoryRepository;
    @Autowired private RelationQualityService qualityService;
    @Autowired private MockMvc mvc;
    @Autowired private UserWorkspaceRepository workspaces;

    @BeforeEach
    void persistReferencedRepositoryCatalogRows() {
        persistRepository(REPOSITORY_A);
        persistRepository(REPOSITORY_B);
        systemRepositoryRepository.flush();
    }

    @Test
    void publicInboxAndMetricsReadOneVisibleRepositoryHistoryWithoutSelectingAnArchitectureBranch() throws Exception {
        var from = node("INBOX-FROM", "CAP");
        var to = node("INBOX-TO", "APP");
        nodeRepository.saveAllAndFlush(List.of(from, to));
        proposalRepository.saveAllAndFlush(List.of(
                proposal(REPOSITORY_A, null, from, to, ProposalStatus.ACCEPTED, 0.6),
                proposal(REPOSITORY_A, WORKSPACE_A1, from, to, ProposalStatus.REJECTED, 0.8),
                proposal(REPOSITORY_B, null, from, to, ProposalStatus.REJECTED, 0.9)));
        String dashboard = "/api/relations/metrics/dashboard";
        for (String path : List.of("/api/proposals", "/api/proposals/pending", "/api/node/INBOX-FROM/proposals",
                "/api/relations/metrics", "/api/relations/metrics/by-type", "/api/relations/metrics/by-provenance",
                "/api/relations/metrics/top-rejected", dashboard, "/api/proposals/head")) {
            mvc.perform(get(path)).andExpect(status().isConflict()).andExpect(header().doesNotExist("ETag"));
        }
        String tag = mvc.perform(get(dashboard).param("repositoryId", REPOSITORY_A).param("workspaceId", "").param("branch", "main"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.metrics.totalProposals").value(1))
                .andExpect(jsonPath("$.metrics.accepted").value(1)).andExpect(jsonPath("$.topRejected.length()").value(0))
                .andExpect(header().string("X-Taxonomy-Relation-Read-Model", "PROPOSAL_REVIEW_HISTORY"))
                .andExpect(header().string("Content-Location", "?repositoryId=" + REPOSITORY_A + "&workspaceId=&branch=main"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("X-Taxonomy-Commit"))
                .andReturn().getResponse().getHeader("ETag");
        assertThat(tag).matches("W/\"[0-9a-f]{64}\"");
        mvc.perform(get(dashboard).param("repositoryId", REPOSITORY_A).param("workspaceId", "").param("branch", "draft")
                .header("If-None-Match", tag)).andExpect(status().isNotModified());
        mvc.perform(get(dashboard).param("repositoryId", REPOSITORY_B).param("workspaceId", "").param("branch", "main")
                .header("If-None-Match", tag)).andExpect(status().isOk()).andExpect(jsonPath("$.topRejected.length()").value(1))
                .andExpect(jsonPath("$.metrics.accepted").value(0));
        mvc.perform(get("/api/proposals").param("repositoryId", REPOSITORY_A).param("workspaceId", "")
                .param("branch", "draft")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"))
                .andExpect(header().string("X-Taxonomy-Relation-Read-Model", "PROPOSAL_REVIEW_HISTORY"));
        String pendingTag = mvc.perform(get("/api/proposals/pending").param("repositoryId", REPOSITORY_A)
                .param("workspaceId", "")).andExpect(jsonPath("$.length()").value(0))
                .andReturn().getResponse().getHeader("ETag");
        mvc.perform(get("/api/proposals/pending").param("repositoryId", REPOSITORY_B).param("workspaceId", "")
                .header("If-None-Match", pendingTag)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0))
                .andExpect(header().string("ETag", org.hamcrest.Matchers.not(pendingTag)));
        mvc.perform(get(dashboard).param("repositoryId", REPOSITORY_A).param("workspaceId", "")
                .param("branch", "main").param("limit", "1")).andExpect(status().isOk())
                .andExpect(header().string("Content-Location", "?repositoryId=" + REPOSITORY_A + "&workspaceId=&branch=main&limit=1"));
        for (String path : List.of("/api/proposals", dashboard)) {
            mvc.perform(get(path).param("repositoryId", REPOSITORY_A).param("workspaceId", "").param("commitId", "a".repeat(40)))
                    .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("ETag"));
        }
        var revised = proposalRepository.findCentralByRepository(REPOSITORY_A).getFirst();
        revised.setStatus(ProposalStatus.REJECTED);
        proposalRepository.saveAndFlush(revised);
        mvc.perform(get(dashboard).param("repositoryId", REPOSITORY_A).param("workspaceId", "").param("branch", "main")
                .header("If-None-Match", tag)).andExpect(status().isOk())
                .andExpect(header().string("ETag", org.hamcrest.Matchers.not(tag)))
                .andExpect(jsonPath("$.metrics.rejected").value(1)).andExpect(jsonPath("$.topRejected.length()").value(1));
        assertThat(workspaces.findByUsernameAndSharedFalse("quality-admin")).isEmpty();
    }

    @Test
    void metricsAndTopRejectedExcludeForeignRepositoriesAndSiblingWorkspaces() {
        TaxonomyNode source = node("qa-quality-metrics-source", "BP");
        TaxonomyNode centralTarget = node("qa-quality-central-target", "CP");
        TaxonomyNode workspaceA1Target = node("qa-quality-a1-target", "CP");
        TaxonomyNode workspaceA2Target = node("qa-quality-a2-target", "CP");
        TaxonomyNode repositoryBTarget = node("qa-quality-b-target", "CP");
        nodeRepository.saveAllAndFlush(List.of(
                source,
                centralTarget,
                workspaceA1Target,
                workspaceA2Target,
                repositoryBTarget));

        proposalRepository.saveAllAndFlush(List.of(
                proposal(REPOSITORY_A, null, source, centralTarget,
                        ProposalStatus.ACCEPTED, 0.80),
                proposal(REPOSITORY_A, WORKSPACE_A1, source, workspaceA1Target,
                        ProposalStatus.REJECTED, 0.90),
                proposal(REPOSITORY_A, WORKSPACE_A2, source, workspaceA2Target,
                        ProposalStatus.REJECTED, 0.95),
                proposal(REPOSITORY_B, null, source, repositoryBTarget,
                        ProposalStatus.REJECTED, 0.99)));

        RepositoryContext centralA = RepositoryContext.centralRead(
                REPOSITORY_A, "main", "quality-admin");
        RepositoryContext workspaceA1 = RepositoryContext.workspace(
                REPOSITORY_A, WORKSPACE_A1, "feature/a1", "quality-admin");
        RepositoryContext workspaceA2 = RepositoryContext.workspace(
                REPOSITORY_A, WORKSPACE_A2, "feature/a2", "quality-admin");
        RepositoryContext centralB = RepositoryContext.centralRead(
                REPOSITORY_B, "main", "quality-admin");

        var centralMetrics = qualityService.calculateMetrics(centralA);
        assertThat(centralMetrics.totalProposals()).isEqualTo(1);
        assertThat(centralMetrics.accepted()).isEqualTo(1);
        assertThat(centralMetrics.rejected()).isZero();

        var workspaceA1Metrics = qualityService.calculateMetrics(workspaceA1);
        assertThat(workspaceA1Metrics.totalProposals()).isEqualTo(2);
        assertThat(workspaceA1Metrics.accepted()).isEqualTo(1);
        assertThat(workspaceA1Metrics.rejected()).isEqualTo(1);
        assertThat(qualityService.topRejected(10, workspaceA1))
                .extracting(proposal -> proposal.targetCode())
                .containsExactly(workspaceA1Target.getCode());

        var workspaceA2Metrics = qualityService.calculateMetrics(workspaceA2);
        assertThat(workspaceA2Metrics.totalProposals()).isEqualTo(2);
        assertThat(qualityService.topRejected(10, workspaceA2))
                .extracting(proposal -> proposal.targetCode())
                .containsExactly(workspaceA2Target.getCode());

        var repositoryBMetrics = qualityService.calculateMetrics(centralB);
        assertThat(repositoryBMetrics.totalProposals()).isEqualTo(1);
        assertThat(repositoryBMetrics.accepted()).isZero();
        assertThat(repositoryBMetrics.rejected()).isEqualTo(1);

        // Switching back must not reuse data from the preceding workspace/repository.
        assertThat(qualityService.calculateMetrics(centralA).totalProposals())
                .isEqualTo(1);
    }

    @Test
    void acceptanceHistoryIsIndependentPerRepositoryAndExactWorkspace() {
        TaxonomyNode source = node("qa-quality-history-source", "BP");
        TaxonomyNode target = node("qa-quality-history-target", "CP");
        nodeRepository.saveAllAndFlush(List.of(source, target));

        proposalRepository.saveAllAndFlush(List.of(
                proposal(REPOSITORY_A, null, source, target,
                        ProposalStatus.ACCEPTED, 0.80),
                proposal(REPOSITORY_A, WORKSPACE_A1, source, target,
                        ProposalStatus.REJECTED, 0.70),
                proposal(REPOSITORY_A, WORKSPACE_A2, source, target,
                        ProposalStatus.ACCEPTED, 0.90),
                proposal(REPOSITORY_B, null, source, target,
                        ProposalStatus.REJECTED, 0.95)));

        assertThat(historyWeight(RepositoryContext.centralRead(
                REPOSITORY_A, "main", "quality-admin")))
                .isEqualTo(1.0);
        assertThat(historyWeight(RepositoryContext.workspace(
                REPOSITORY_A, WORKSPACE_A1, "feature/a1", "quality-admin")))
                .isEqualTo(0.5);
        assertThat(historyWeight(RepositoryContext.workspace(
                REPOSITORY_A, WORKSPACE_A2, "feature/a2", "quality-admin")))
                .isEqualTo(1.0);
        assertThat(historyWeight(RepositoryContext.centralRead(
                REPOSITORY_B, "main", "quality-admin")))
                .isEqualTo(0.0);
    }

    private double historyWeight(RepositoryContext context) {
        return qualityService.acceptanceHistoryWeight(
                "BP", "CP", RelationType.RELATED_TO, context);
    }

    private void persistRepository(String repositoryId) {
        if (systemRepositoryRepository.findByRepositoryId(repositoryId).isPresent()) {
            return;
        }
        Instant now = Instant.now();
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId(repositoryId);
        repository.setStorageRepositoryName(repositoryId);
        repository.setSlug(repositoryId);
        repository.setDisplayName("Quality isolation " + repositoryId);
        repository.setDescription("Database-profile tenant isolation fixture");
        repository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        repository.setDefaultBranch("main");
        repository.setOwnerId("quality-admin");
        repository.setCreatedBy("quality-admin");
        repository.setCreatedAt(now);
        repository.setUpdatedAt(now);
        repository.setPrimaryRepo(false);
        systemRepositoryRepository.save(repository);
    }

    private TaxonomyNode node(String code, String taxonomyRoot) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn("Quality isolation " + code);
        node.setTaxonomyRoot(taxonomyRoot);
        node.setLevel(1);
        return node;
    }

    private RelationProposal proposal(
            String repositoryId,
            String workspaceId,
            TaxonomyNode source,
            TaxonomyNode target,
            ProposalStatus status,
            double confidence) {
        RelationProposal proposal = new RelationProposal();
        proposal.setRepositoryId(repositoryId);
        proposal.setWorkspaceId(workspaceId);
        proposal.setOwnerUsername(
                workspaceId == null ? "central-quality-admin" : workspaceId);
        proposal.setSourceNode(source);
        proposal.setTargetNode(target);
        proposal.setRelationType(RelationType.RELATED_TO);
        proposal.setStatus(status);
        proposal.setConfidence(confidence);
        proposal.setRationale("quality tenant isolation evidence");
        proposal.setProvenance("qa-quality-isolation");
        return proposal;
    }
}
