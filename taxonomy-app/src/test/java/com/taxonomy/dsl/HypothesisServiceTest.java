package com.taxonomy.dsl;

import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationEvidence;
import com.taxonomy.model.RelationHypothesis;
import com.taxonomy.repository.RelationEvidenceRepository;
import com.taxonomy.repository.RelationHypothesisRepository;
import com.taxonomy.service.HypothesisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link HypothesisService}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HypothesisServiceTest {

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
                        "REALIZES", 0.80, "Inferred from compatibility matrix")
        );

        List<RelationHypothesis> persisted = hypothesisService.persistFromAnalysis(dtos, "test-persist-1");

        assertThat(persisted).hasSize(1);
        RelationHypothesis h = persisted.get(0);
        assertThat(h.getId()).isNotNull();
        assertThat(h.getSourceNodeId()).isEqualTo("BP");
        assertThat(h.getTargetNodeId()).isEqualTo("CP");
        assertThat(h.getStatus()).isEqualTo(HypothesisStatus.PROVISIONAL);
        assertThat(h.getConfidence()).isEqualTo(0.80);
        assertThat(h.getAnalysisSessionId()).isEqualTo("test-persist-1");
    }

    @Test
    void persistFromAnalysisCreatesEvidence() {
        List<RelationHypothesisDto> dtos = List.of(
                new RelationHypothesisDto("BP", "Process", "CP", "Capability",
                        "SUPPORTS", 0.70, "Test reasoning for evidence")
        );

        List<RelationHypothesis> persisted = hypothesisService.persistFromAnalysis(dtos, "test-evidence-1");
        assertThat(persisted).hasSize(1);

        List<RelationEvidence> evidence = evidenceRepository.findByHypothesisId(persisted.get(0).getId());
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).getSummary()).isEqualTo("Test reasoning for evidence");
        assertThat(evidence.get(0).getEvidenceType()).isEqualTo("analysis-rule");
    }

    @Test
    void persistFromAnalysisSkipsDuplicatesInSameSession() {
        List<RelationHypothesisDto> dtos = List.of(
                new RelationHypothesisDto("BP", "P", "CP", "C",
                        "DEPENDS_ON", 0.60, "First")
        );

        List<RelationHypothesis> first = hypothesisService.persistFromAnalysis(dtos, "test-dedup-1");
        assertThat(first).hasSize(1);

        // Same session, same source/target/type → should be skipped
        List<RelationHypothesis> second = hypothesisService.persistFromAnalysis(dtos, "test-dedup-1");
        assertThat(second).isEmpty();
    }

    @Test
    void persistFromAnalysisWithNullInputReturnsEmpty() {
        List<RelationHypothesis> result = hypothesisService.persistFromAnalysis(null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void persistFromAnalysisWithEmptyListReturnsEmpty() {
        List<RelationHypothesis> result = hypothesisService.persistFromAnalysis(List.of(), null);
        assertThat(result).isEmpty();
    }

    @Test
    void acceptHypothesisChangesStatus() {
        RelationHypothesis h = createTestHypothesis("test-accept-status");
        RelationHypothesis accepted = hypothesisService.accept(h.getId());

        assertThat(accepted.getStatus()).isEqualTo(HypothesisStatus.ACCEPTED);
    }

    @Test
    void rejectHypothesisChangesStatus() {
        RelationHypothesis h = createTestHypothesis("test-reject-status");
        RelationHypothesis rejected = hypothesisService.reject(h.getId());

        assertThat(rejected.getStatus()).isEqualTo(HypothesisStatus.REJECTED);
    }

    @Test
    void acceptAlreadyAcceptedThrows() {
        // Create a hypothesis directly as ACCEPTED to test the guard
        RelationHypothesis h = createTestHypothesisWithStatus("test-double-accept", HypothesisStatus.ACCEPTED);

        assertThatThrownBy(() -> hypothesisService.accept(h.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectAlreadyRejectedThrows() {
        RelationHypothesis h = createTestHypothesis("test-double-reject");
        hypothesisService.reject(h.getId());

        assertThatThrownBy(() -> hypothesisService.reject(h.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptNonExistentThrows() {
        assertThatThrownBy(() -> hypothesisService.accept(999999L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findByStatusFilters() {
        createTestHypothesisWithStatus("test-filter-find", HypothesisStatus.PROPOSED);

        List<RelationHypothesis> proposed = hypothesisService.findByStatus(HypothesisStatus.PROPOSED);
        assertThat(proposed).isNotEmpty();
        assertThat(proposed).allMatch(h -> h.getStatus() == HypothesisStatus.PROPOSED);
    }

    @Test
    void findEvidenceReturnsResults() {
        List<RelationHypothesisDto> dtos = List.of(
                new RelationHypothesisDto("BP", "P", "CP", "C",
                        "FULFILLS", 0.75, "Evidence test reasoning")
        );
        List<RelationHypothesis> persisted = hypothesisService.persistFromAnalysis(dtos, "test-find-evidence");
        assertThat(persisted).hasSize(1);

        List<RelationEvidence> evidence = hypothesisService.findEvidence(persisted.get(0).getId());
        assertThat(evidence).hasSize(1);
    }

    private RelationHypothesis createTestHypothesis(String sessionId) {
        return createTestHypothesisWithStatus(sessionId, HypothesisStatus.PROVISIONAL);
    }

    private RelationHypothesis createTestHypothesisWithStatus(String sessionId, HypothesisStatus status) {
        RelationHypothesis h = new RelationHypothesis();
        // Use root codes that exist but with a relation type not preloaded from CSV
        h.setSourceNodeId("CR");
        h.setTargetNodeId("CO");
        h.setRelationType(com.taxonomy.model.RelationType.REALIZES);
        h.setStatus(status);
        h.setConfidence(0.80);
        h.setAnalysisSessionId(sessionId);
        return hypothesisRepository.save(h);
    }
}
