package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.EnumSet;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProjectGapEvidenceReader {
    private final RequirementAnalysisSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    public ProjectGapEvidenceReader(RequirementAnalysisSnapshotRepository snapshotRepository, ObjectMapper objectMapper) {
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
    }

    public List<ProjectProductGaps.Family> read(Long projectId, Long requirementId, String scopeKey) {
        var result = new ProjectProductGaps();
        var completed = EnumSet.of(AnalysisStatus.SUCCESS, AnalysisStatus.PARTIAL);
        try (var rows = snapshotRepository.streamProductGapEvidence(projectId, scopeKey, requirementId, completed)) {
            rows.forEach(row -> {
                if (row.analysisPayload() != null) {
                    result.include(row, objectMapper.readValue(row.analysisPayload(), ProjectProductGaps.Evidence.class));
                }
            });
        }
        return result.families();
    }
}
