package com.taxonomy.extension.api.inventory;

import com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind;
import java.time.*;
import java.util.*;

/** Immutable product-neutral comparison evidence; never an implementation or procurement decision. */
public final class InventoryComparisonContracts {
    private InventoryComparisonContracts() { }
    public enum Outcome { SUPPORTED, CONTRADICTED, UNKNOWN, CONFLICT, UNEXAMINED }
    public enum Criterion { CAPABILITY, AVAILABILITY, SUPPORT, ORGANIZATION, ENVIRONMENT, RELATIONSHIPS }
    public enum TimeMode { EXACT_DATE, START_WINDOW, CONTINUOUS_PERIOD }
    public enum Precision { DATE, YEAR }
    public record Boundary(String value, Precision precision) {
        public Boundary {
            Objects.requireNonNull(precision); text(value, 10);
            if (precision == Precision.YEAR) Year.parse(value); else LocalDate.parse(value);
        }
        public LocalDate earliest() { return precision == Precision.YEAR ? Year.parse(value).atDay(1) : LocalDate.parse(value); }
        public LocalDate latest() { return precision == Precision.YEAR ? Year.parse(value).atMonth(12).atEndOfMonth() : LocalDate.parse(value); }
    }
    public record Question(TimeMode mode, LocalDate start, LocalDate endExclusive, String organization, String environment) {
        public Question {
            Objects.requireNonNull(mode); Objects.requireNonNull(start); text(organization, 400);
            if (environment != null) text(environment, 200);
            if (mode == TimeMode.EXACT_DATE && endExclusive != null) throw new IllegalArgumentException("Exact-date question has no end");
            if (mode != TimeMode.EXACT_DATE && (endExclusive == null || !endExclusive.isAfter(start))) throw new IllegalArgumentException("Invalid half-open question interval");
        }
        public LocalDate end() { return mode == TimeMode.EXACT_DATE ? start.plusDays(1) : endExclusive; }
    }
    public record Assertion(String id, String candidateId, Criterion criterion, String nodeCode, boolean positive,
                            Boundary start, Boundary endExclusive, String organization, String environment,
                            AssertionKind kind, String citation, Instant recordedAt) {
        public Assertion {
            text(id, 200); text(candidateId, 3000); Objects.requireNonNull(criterion); Objects.requireNonNull(kind);
            text(citation, 4000); Objects.requireNonNull(recordedAt);
            if (nodeCode != null) text(nodeCode, 80);
            if (organization != null) text(organization, 400); if (environment != null) text(environment, 200);
            if (start != null && endExclusive != null && !endExclusive.latest().isAfter(start.earliest())) throw new IllegalArgumentException("Invalid assertion interval");
        }
    }
    public record Finding(String candidateId, Criterion criterion, String nodeCode, Outcome outcome,
                          List<String> evidenceIds, Set<AssertionKind> assertionKinds, Set<String> limitations,
                          String nextClarification, boolean hardExclusion) {
        public Finding { evidenceIds=List.copyOf(evidenceIds); assertionKinds=Set.copyOf(assertionKinds); limitations=Set.copyOf(limitations); }
    }
    public record Demand(String repositoryId, String scopeKey, String branch, Long projectId, Long requirementId,
                         Long requirementVersionId, String snapshotId, String taxonomyFingerprint,
                         String snapshotFingerprint, List<String> selectedNodes, Question question,
                         String requirementText,Map<String,String> frozenNodes,Long observedRequirementVersionId) {
        public Demand(String repositoryId,String scopeKey,String branch,Long projectId,Long requirementId,Long requirementVersionId,String snapshotId,String taxonomyFingerprint,String snapshotFingerprint,List<String> selectedNodes,Question question) {
            this(repositoryId,scopeKey,branch,projectId,requirementId,requirementVersionId,snapshotId,taxonomyFingerprint,snapshotFingerprint,selectedNodes,question,null,Map.of(),requirementVersionId);
        }
        public Demand { frozenNodes=Map.copyOf(frozenNodes);selectedNodes=List.copyOf(selectedNodes); Objects.requireNonNull(question); if(selectedNodes.isEmpty() || selectedNodes.size()>100) throw new IllegalArgumentException("Select 1–100 recorded nodes"); }
    }
    public record Candidate(String id, Long productId, String name, String kind, String fingerprint,
                            Map<String,String> metadata, List<Assertion> assertions) {
        public Candidate { text(id,3000); metadata=Map.copyOf(metadata); assertions=List.copyOf(assertions); }
    }
    public record SourceEvidence(String profileId, String revision, String observationId, Instant observedAt,
                                 String selectorFingerprint, String status, Set<String> limitations,String profileFingerprint,String extensionArtifact) {
        public SourceEvidence(String profileId,String revision,String observationId,Instant observedAt,String selectorFingerprint,String status,Set<String> limitations) {
            this(profileId,revision,observationId,observedAt,selectorFingerprint,status,limitations,null,null);
        }
        public SourceEvidence { limitations=Set.copyOf(limitations); }
    }
    public record Comparison(UUID id, Demand demand, String evaluatorVersion, Instant createdAt,
                             List<Candidate> candidates, List<Finding> findings, List<SourceEvidence> sources, String fingerprint) {
        public Comparison { candidates=List.copyOf(candidates); findings=List.copyOf(findings); sources=List.copyOf(sources); }
    }
    public record Review(UUID id, String comparisonFingerprint, String candidateId, String rationale,
                         Long projectSolutionId, boolean selectProduct,Integer coveragePercent) {
        public Review(UUID id,String comparisonFingerprint,String candidateId,String rationale,Long projectSolutionId,boolean selectProduct) {
            this(id,comparisonFingerprint,candidateId,rationale,projectSolutionId,selectProduct,null);
        }
        public Review { if(coveragePercent!=null && (coveragePercent<0 || coveragePercent>100))throw new IllegalArgumentException("Reviewed coverage must be 0–100"); Objects.requireNonNull(id); text(comparisonFingerprint,128); text(candidateId,3000); text(rationale,1800); if(selectProduct && projectSolutionId==null) throw new IllegalArgumentException("Selection requires an existing project solution"); }
    }
    public record Mapping(UUID id,String candidateId,Long productId,String productFingerprint,
                          String sourceProfile,String sourceRevision,String sourceIdentityFingerprint,
                          String rationale,String actor,Instant mappedAt) {
        public Mapping { Objects.requireNonNull(id); Objects.requireNonNull(productId); text(candidateId,3000); text(productFingerprint,128); text(rationale,1800); text(actor,160); }
    }
    public record Reviewed(Review review, Instant reviewedAt, String actor) { }
    private static void text(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid comparison metadata");
    }
}
