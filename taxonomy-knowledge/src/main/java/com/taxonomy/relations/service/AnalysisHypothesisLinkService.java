package com.taxonomy.relations.service;

import com.taxonomy.relations.repository.RelationHypothesisRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps knowledge projection mutation inside knowledge and inside the portfolio transaction. */
@Service
public class AnalysisHypothesisLinkService implements AnalysisHypothesisLinkPort {
    private final RelationHypothesisRepository hypotheses;

    public AnalysisHypothesisLinkService(RelationHypothesisRepository hypotheses) {
        this.hypotheses = hypotheses;
    }

    @Override
    @Transactional
    public void linkSnapshot(String repositoryId, String workspaceId, String branchName, String analysisSessionId,
                             Long projectId, Long requirementId, String snapshotId) {
        var selected = hypotheses.findByAnalysisSessionIdInRepositoryWorkspaceAndBranch(
                repositoryId, workspaceId, analysisSessionId, branchName);
        for (var hypothesis : selected) {
            hypothesis.setProjectId(projectId);
            hypothesis.setRequirementId(requirementId);
            hypothesis.setAnalysisSnapshotId(snapshotId);
        }
        hypotheses.saveAll(selected);
    }
}
