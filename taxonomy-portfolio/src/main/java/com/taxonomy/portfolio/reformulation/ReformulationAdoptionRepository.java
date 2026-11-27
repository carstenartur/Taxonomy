package com.taxonomy.portfolio.reformulation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
public interface ReformulationAdoptionRepository extends JpaRepository<ReformulationAdoption,String> {
    record EvidenceSource(String proposalId, String previewId, String scopeKey, Long requirementId, Long targetVersionId,
                          String receiptPayload, String previewPayload, String previewHash,
                          String targetScope, Integer targetVersion, String targetHash,
                          Long actualRequirementId, String requirementScope, String requirementKey,
                          String projectScope, String projectKey) { }

    // Composite-key associations can initialize proposals and large analysis snapshots even when marked lazy.
    // Keep broken joins visible so the evidence codec can reject incomplete or inconsistent rows.
    String EVIDENCE_SOURCE = """
            select new com.taxonomy.portfolio.reformulation.ReformulationAdoptionRepository$EvidenceSource(
                a.proposalId, a.previewId, a.scopeKey, a.requirementId, a.targetVersionId,
                a.payload, preview.payload, preview.contentHash, target.scopeKey, target.versionNumber,
                target.contentHash, requirement.id, requirement.scopeKey, requirement.requirementKey,
                project.scopeKey, project.projectKey)
            from ReformulationAdoption a
            left join a.preview preview
            left join a.version target
            left join target.requirement requirement
            left join requirement.project project
            where a.scopeKey = :scopeKey
            """;

    Optional<ReformulationAdoption> findByIdAndProposalIdAndRequirementIdAndScopeKey(
            String id, String proposalId, Long requirementId, String scopeKey);
    boolean existsByPreviewId(String previewId);
    List<ReformulationAdoption> findByProposalIdAndScopeKeyOrderByCreatedAtDesc(String proposalId,String scopeKey);
    @EntityGraph(attributePaths = {"preview", "version", "version.requirement", "version.requirement.project"})
    List<ReformulationAdoption> findByRequirementIdAndTargetVersionIdAndScopeKey(Long requirementId,Long targetVersionId,String scopeKey);
    @Query(EVIDENCE_SOURCE + "order by a.createdAt asc")
    List<EvidenceSource> findEvidenceByScopeKey(String scopeKey);

    @Query(EVIDENCE_SOURCE + "and a.requirementId = :requirementId and a.targetVersionId = :targetVersionId")
    List<EvidenceSource> findEvidenceByRequirementVersion(Long requirementId, Long targetVersionId, String scopeKey);
}
