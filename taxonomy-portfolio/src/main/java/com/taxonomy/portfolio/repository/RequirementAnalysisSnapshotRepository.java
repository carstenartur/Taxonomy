package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
import com.taxonomy.portfolio.model.SnapshotReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RequirementAnalysisSnapshotRepository
        extends JpaRepository<RequirementAnalysisSnapshot, String> {

    @Query("""
            select new com.taxonomy.portfolio.repository.AnalysisSnapshotReadRow(
                snapshot.id, snapshot.projectId, snapshot.requirementId, requirement.requirementKey,
                snapshot.requirementVersionId, analyzedVersion.versionNumber, snapshot.jobId,
                snapshot.status, snapshot.provider, snapshot.modelName, snapshot.taxonomyFingerprint,
                snapshot.promptFingerprint, snapshot.workspaceId, snapshot.branchName, snapshot.commitSha,
                snapshot.createdAt, snapshot.durationMs, snapshot.warningCount, snapshot.errorMessage,
                snapshot.analysisPayload, snapshot.gapAnalysisPayload, snapshot.patternDetectionPayload,
                snapshot.recommendationPayload)
              from RequirementAnalysisSnapshot snapshot
              join snapshot.requirement requirement
              join snapshot.requirementVersion analyzedVersion
             where snapshot.id = :id and snapshot.projectId = :projectId and snapshot.scopeKey = :scopeKey
            """)
    Optional<AnalysisSnapshotReadRow> findReadRowByIdAndProjectIdAndScopeKey(
            @Param("id") String id, @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

    @Query("""
            select new com.taxonomy.portfolio.dto.PortfolioDtos$SnapshotSummary(
                snapshot.id, snapshot.projectId, snapshot.requirementId, requirement.requirementKey,
                snapshot.requirementVersionId, analyzedVersion.versionNumber, snapshot.jobId,
                snapshot.status, snapshot.provider, snapshot.modelName, snapshot.taxonomyFingerprint,
                snapshot.promptFingerprint, snapshot.workspaceId, snapshot.branchName, snapshot.commitSha,
                snapshot.createdAt, snapshot.durationMs, snapshot.warningCount, snapshot.errorMessage)
              from RequirementAnalysisSnapshot snapshot
              join snapshot.requirement requirement
              join snapshot.requirementVersion analyzedVersion
             where snapshot.id = :id and snapshot.projectId = :projectId and snapshot.scopeKey = :scopeKey
            """)
    Optional<SnapshotSummary> findSummaryByIdAndProjectIdAndScopeKey(
            @Param("id") String id, @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

    @Query("""
            select reference from SnapshotReference reference
             where reference.id = :id and reference.projectId = :projectId and reference.scopeKey = :scopeKey
            """)
    Optional<SnapshotReference> findReferenceByIdAndProjectIdAndScopeKey(
            @Param("id") String id, @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

    @Query("""
            select new com.taxonomy.portfolio.repository.AnalysisSnapshotIdentityRow(
                snapshot.id, snapshot.requirementId, snapshot.requirementVersionId)
              from RequirementAnalysisSnapshot snapshot
             where snapshot.id = :id and snapshot.projectId = :projectId and snapshot.scopeKey = :scopeKey
            """)
    Optional<AnalysisSnapshotIdentityRow> findIdentityByIdAndProjectIdAndScopeKey(
            @Param("id") String id, @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

    /** The immutable analysis alone, bound to the complete work identity checked by continuation eligibility. */
    @Query("""
            select snapshot.analysisPayload
              from RequirementAnalysisSnapshot snapshot
             where snapshot.id = :snapshotId and snapshot.jobId = :jobId
               and snapshot.requirementId = :requirementId and snapshot.requirementVersionId = :requirementVersionId
               and snapshot.projectId = :projectId and snapshot.scopeKey = :scopeKey
            """)
    List<String> findAnalysisPayloadForContinuation(
            @Param("snapshotId") String snapshotId, @Param("jobId") String jobId,
            @Param("requirementId") Long requirementId, @Param("requirementVersionId") Long requirementVersionId,
            @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

    /** A history entry needs provenance and status, without loading any of the four result payloads. */
    @Query("""
            select new com.taxonomy.portfolio.dto.PortfolioDtos$SnapshotSummary(
                snapshot.id, snapshot.projectId, snapshot.requirementId, requirement.requirementKey,
                snapshot.requirementVersionId, analyzedVersion.versionNumber, snapshot.jobId,
                snapshot.status, snapshot.provider, snapshot.modelName, snapshot.taxonomyFingerprint,
                snapshot.promptFingerprint, snapshot.workspaceId, snapshot.branchName, snapshot.commitSha,
                snapshot.createdAt, snapshot.durationMs, snapshot.warningCount, snapshot.errorMessage)
              from RequirementAnalysisSnapshot snapshot
              join snapshot.requirement requirement
              join snapshot.requirementVersion analyzedVersion
             where snapshot.requirementId = :requirementId
               and snapshot.projectId = :projectId and snapshot.scopeKey = :scopeKey
             order by snapshot.createdAt desc
            """)
    List<SnapshotSummary> findSummariesByRequirementIdAndProjectIdAndScopeKey(
            @Param("requirementId") Long requirementId, @Param("projectId") Long projectId,
            @Param("scopeKey") String scopeKey);

    Optional<RequirementAnalysisSnapshot> findByIdAndProjectIdAndScopeKey(
            String id, Long projectId, String scopeKey);

    List<RequirementAnalysisSnapshot>
            findByRequirementIdAndProjectIdAndScopeKeyOrderByCreatedAtDesc(
                    Long requirementId, Long projectId, String scopeKey);

    List<RequirementAnalysisSnapshot> findByProjectIdAndScopeKeyOrderByCreatedAtDesc(
            Long projectId, String scopeKey);

    /** Compatibility signatures retained while all callers migrate. */
    @Deprecated(forRemoval = false)
    Optional<RequirementAnalysisSnapshot> findByIdAndProjectId(String id, Long projectId);

    @Deprecated(forRemoval = false)
    List<RequirementAnalysisSnapshot> findByRequirementIdOrderByCreatedAtDesc(Long requirementId);

    @Deprecated(forRemoval = false)
    List<RequirementAnalysisSnapshot> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
