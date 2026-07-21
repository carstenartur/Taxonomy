package com.taxonomy.dsl;

import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationEvidence;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationEvidenceRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.versioning.service.HypothesisService;
import com.taxonomy.workspace.service.WorkspaceContext;
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

    private static final WorkspaceContext SHARED = WorkspaceContext.SHARED;
    private static final WorkspaceContext ALPHA = new WorkspaceContext("alice", "workspace-alpha", "draft");
    private static final WorkspaceContext BETA = new WorkspaceContext("bob", "workspace-beta", "draft");

    @Autowired
    private HypothesisService hypothesisService;

    @Autowired
    private RelationHypothesisRepository hypothesisRepository;

    @Autowired
    private RelationEvidenceRepository evidenceRepository;

    @Test
    void persistFromAnalysisSavesHypotheses() {
        List<RelationHypothesisDto> dtos = List.of(
                new RelationHypothesisDto("BP", "Process One", "CP", "Capability One",
                        "REALIZES", 0.80, "Inferred from compatibility matrix"));

        List<RelationHypothesis> persisted =
                hypothesisService.persistFromAnalysis(dtos, "test-persist-1", SHARED);

        assertThat(persisted).hasSize(1);
        RelationHypothesis h = persisted.get(0);
        assertThat(h.getId()).isNotNull();
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
                hypothesisService.persistFromAnalysis(dtos, "test-evidence-1", SHARED);
        assertThat(persisted).hasSize(1);

        List<RelationEvidence> evidence =
                hypothesisService.findEvidence(persisted.get(0).getId(), SHARED);
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).getSummary()).isEqualTo("Test reasoning for evidence");
        assertThat(evidence.get(0).getEvidenceType()).isEqualTo("analysis-rule");
    }

    @Test
    void persistFromAnalysisSkipsDuplicatesInSameSessionAndWorkspace() {
        List<RelationHypothesisDto> dtos = List.of(
                new RelationHypothesisDto("BP", "P", "CP", "C",
                        "DEPENDS_ON", 0.60, "First"));

        List<RelationHypothesis> first =
                hypothesisService.persistFromAnalysis(dtos, "test-dedup-1", ALPHA);
        List<RelationHypothesis> second =
                hypothesisService.persistFromAnalysis(dtos, "test-dedup-1", ALPHA);
        List<RelationHypothesis> otherWorkspace =
                hypothesisService.persistFromAnalysis(dtos, "test-dedup-1", BETA);

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
        assertThat(otherWorkspace).hasSize(1);
    }

    @Test
    void persistFromAnalysisWithNullOrEmptyInputReturnsEmpty() {
        assertThat(hypothesisService.persistFromAnalysis(null, null, SHARED)).isEmpty();
        assertThat(hypothesisService.persistFromAnalysis(List.of(), null, SHARED)).isEmpty();
    }

    @Test
    void acceptAndRejectChangeStatusWithinOwningWorkspace() {
        RelationHypothesis acceptedInput = createTestHypothesis("test-accept-status", ALPHA);
        RelationHypothesis rejectedInput = createTestHypothesis("test-reject-status", ALPHA);

        assertThat(hypothesisService.accept(acceptedInput.getId(), ALPHA).getStatus())
                .isEqualTo(HypothesisStatus.ACCEPTED);
        assertThat(hypothesisService.reject(rejectedInput.getId(), ALPHA).getStatus())
                .isEqualTo(HypothesisStatus.REJECTED);
    }

    @Test
    void mutationFromAnotherWorkspaceIsHiddenAsNotFound() {
        RelationHypothesis hypothesis = createTestHypothesis("test-isolation", ALPHA);

        assertThatThrownBy(() -> hypothesisService.accept(hypothesis.getId(), BETA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> hypothesisService.reject(hypothesis.getId(), BETA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> hypothesisService.applyForSession(hypothesis.getId(), BETA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void evidenceFromAnotherWorkspaceIsNotVisible() {
        List<RelationHypothesis> persisted = hypothesisService.persistFromAnalysis(
                List.of(new RelationHypothesisDto("BP", "P", "CP", "C",
                        "FULFILLS", 0.75, "Evidence test reasoning")),
                "test-private-evidence", ALPHA);

        assertThatThrownBy(() -> hypothesisService.findEvidence(persisted.get(0).getId(), BETA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void workspaceListingsContainSharedAndOwnButNotOtherWorkspace() {
        RelationHypothesis shared = createTestHypothesis("test-list-shared", SHARED);
        RelationHypothesis alpha = createTestHypothesis("test-list-alpha", ALPHA);
        RelationHypothesis beta = createTestHypothesis("test-list-beta", BETA);

        assertThat(hypothesisService.findAll(ALPHA))
                .extracting(RelationHypothesis::getId)
                .contains(shared.getId(), alpha.getId())
                .doesNotContain(beta.getId());
        assertThat(hypothesisService.findAll(SHARED))
                .extracting(RelationHypothesis::getId)
                .contains(shared.getId())
                .doesNotContain(alpha.getId(), beta.getId());
    }

    @Test
    void invalidStateAndUnknownIdAreRejected() {
        RelationHypothesis accepted =
                createTestHypothesisWithStatus("test-double-accept", HypothesisStatus.ACCEPTED, SHARED);
        RelationHypothesis rejected =
                createTestHypothesisWithStatus("test-double-reject", HypothesisStatus.REJECTED, SHARED);

        assertThatThrownBy(() -> hypothesisService.accept(accepted.getId(), SHARED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> hypothesisService.reject(rejected.getId(), SHARED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> hypothesisService.accept(999999L, SHARED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findByStatusFiltersWithinContext() {
        createTestHypothesisWithStatus("test-filter-find", HypothesisStatus.PROPOSED, ALPHA);
        createTestHypothesisWithStatus("test-filter-other", HypothesisStatus.PROPOSED, BETA);

        List<RelationHypothesis> proposed =
                hypothesisService.findByStatus(HypothesisStatus.PROPOSED, ALPHA);
        assertThat(proposed).isNotEmpty();
        assertThat(proposed).allMatch(h -> h.getStatus() == HypothesisStatus.PROPOSED);
        assertThat(proposed).noneMatch(h -> "workspace-beta".equals(h.getWorkspaceId()));
    }

    private RelationHypothesis createTestHypothesis(String sessionId, WorkspaceContext context) {
        return createTestHypothesisWithStatus(sessionId, HypothesisStatus.PROVISIONAL, context);
    }

    private RelationHypothesis createTestHypothesisWithStatus(
            String sessionId, HypothesisStatus status, WorkspaceContext context) {
        RelationHypothesis h = new RelationHypothesis();
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
