package com.taxonomy.portfolio.workbench;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;

import java.time.Instant;
import java.util.List;

/** Versioned JSON evidence contract for one saved requirement analysis. */
public record SourceCoverageManifest(
        int schemaVersion, Scope scope, RequirementVersion requirementVersion, Snapshot snapshot,
        Checkpoint checkpoint, List<Passage> sourcePassages, List<Decision> decisions, List<Gap> gaps) {

    public SourceCoverageManifest {
        sourcePassages = List.copyOf(sourcePassages);
        decisions = List.copyOf(decisions);
        gaps = List.copyOf(gaps);
    }

    public record Scope(String repositoryId, String workspaceId, String branch, Long projectId, String projectKey) {}

    public record RequirementVersion(Long requirementId, String requirementKey, Long versionId, int versionNumber,
                                     String contentSha256, String semanticRequirementId) {}

    public record Snapshot(String id, AnalysisStatus status, String provider, String analysisCommitSha,
                           String taxonomyFingerprint, List<String> candidateArchitectureIds,
                           List<String> candidateRelationKeys) {
        public Snapshot {
            candidateArchitectureIds = List.copyOf(candidateArchitectureIds);
            candidateRelationKeys = List.copyOf(candidateRelationKeys);
        }
    }

    public record Checkpoint(CheckpointSelection selection, String commitSha, String documentSha256) {}

    public enum CheckpointSelection { ANALYSIS_RECORD, EXPLICIT }

    public record Passage(PassageEvidence evidence, Long artifactId, Long sourceVersionId, Long fragmentId,
                          String sectionReference, Integer pageNumber, String originalTextSha256) {}

    public enum PassageEvidence { RECORDED_FRAGMENT, RECORDED_ORIGINAL }

    public record Decision(String mappingKey, String architectureId, ReviewStatus reviewStatus,
                           String actionStatus, String actor, Instant decidedAt) {}

    public record Gap(GapState state, GapCode code, String reference, String observedValue) {}

    public enum GapState { MISSING, STALE }

    public enum GapCode {
        REQUIREMENT_VERSION_UNAVAILABLE,
        SOURCE_PASSAGE_NOT_RECORDED,
        SOURCE_VERSION_NOT_RECORDED,
        SOURCE_PASSAGE_LOCATOR_NOT_RECORDED,
        CHECKPOINT_NOT_RECORDED,
        CHECKPOINT_INVALID_ID,
        CHECKPOINT_SCOPE_NOT_RECORDED,
        CHECKPOINT_UNAVAILABLE,
        CHECKPOINT_DOCUMENT_MISSING,
        CHECKPOINT_DOCUMENT_AMBIGUOUS,
        REQUIREMENT_VERSION_NOT_CHECKPOINTED,
        REQUIREMENT_VERSION_MISMATCH,
        SOURCE_PASSAGE_MISMATCH,
        SEMANTIC_REQUIREMENT_NOT_CHECKPOINTED,
        SEMANTIC_REQUIREMENT_AMBIGUOUS,
        SEMANTIC_REQUIREMENT_VERSION_MISMATCH,
        SNAPSHOT_ARCHITECTURE_NOT_RECORDED,
        DECISION_NOT_CHECKPOINTED,
        DECISION_SNAPSHOT_MISMATCH,
        DECISION_EVIDENCE_INCOMPLETE,
        SEMANTIC_ELEMENT_NOT_CHECKPOINTED,
        RELATION_DECISION_NOT_CHECKPOINTED
    }
}
