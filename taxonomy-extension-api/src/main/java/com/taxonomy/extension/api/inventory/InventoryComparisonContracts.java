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
    public enum RelationshipKind { INTERFACE, DEPENDENCY, ALTERNATIVE, HARD_EXCLUSION }
    public enum VariantAction { SATISFIED_AS_IS, REUSE, CHANGE, CREATE, PROCURE, ORGANIZATIONAL, RETIRE_OR_REPLACE, UNDECIDED }
    public enum AssessmentStatus { OPEN, SATISFIED, BLOCKING }
    public enum VariantOutcome { EVALUATED, SELECTED, REJECTED }
    public record Relationship(String targetCandidateId, RelationshipKind kind, String qualifier) {
        public Relationship { text(targetCandidateId,3000); Objects.requireNonNull(kind); if(qualifier!=null)text(qualifier,200); }
    }
    public record RelationshipRequirement(String id,String sourceCandidateId,String targetCandidateId,RelationshipKind kind,String qualifier,boolean required) {
        public RelationshipRequirement { text(id,200);text(sourceCandidateId,3000);text(targetCandidateId,3000);Objects.requireNonNull(kind);if(qualifier!=null)text(qualifier,200); }
    }
    public record RelationshipFinding(String requirementId,String sourceCandidateId,String targetCandidateId,RelationshipKind kind,String qualifier,boolean required,Finding finding) { }
    public record AssessmentNote(String text, AssessmentStatus status, String citation) {
        public AssessmentNote { InventoryComparisonContracts.text(text,1800);Objects.requireNonNull(status);InventoryComparisonContracts.text(citation,1800); }
    }
    /** Exact decimal wire value: JavaScript numbers cannot preserve this contract. */
    public record CostAssumption(String amount,String currency,String basis,String citation) {
        public CostAssumption {
            text(amount,24);text(currency,3);text(basis,400);text(citation,1800);
            if(!amount.matches("[0-9]+(\\.[0-9]{1,4})?") || !currency.matches("[A-Z]{3}"))throw new IllegalArgumentException("Invalid cost assumption");
            var decimal=new java.math.BigDecimal(amount);
            if(decimal.precision()>18 || decimal.scale()>4)throw new IllegalArgumentException("Cost precision exceeds 18 digits / 4 decimal places");
            amount=decimal.toPlainString();
        }
    }
    public record VariantInput(Long projectSolutionId,VariantAction action,List<String> candidateIds,List<AssessmentNote> prerequisites,List<AssessmentNote> risks,List<CostAssumption> costs) {
        public VariantInput { Objects.requireNonNull(projectSolutionId);Objects.requireNonNull(action);candidateIds=bounded(candidateIds,100);prerequisites=bounded(prerequisites,20);risks=bounded(risks,20);costs=bounded(costs,20);if(new HashSet<>(candidateIds).size()!=candidateIds.size())throw new IllegalArgumentException("Duplicate evidence candidate"); }
    }
    public record VariantAssessment(Long projectSolutionId,Long solutionId,String solutionKey,String title,String fingerprint,String frozenSolutionJson,VariantInput input,List<Finding> findings,List<String> hardExclusions,List<String> openQuestions) {
        public VariantAssessment { findings=List.copyOf(findings);hardExclusions=List.copyOf(hardExclusions);openQuestions=List.copyOf(openQuestions); }
    }
    public record VariantDecision(Long projectSolutionId,VariantOutcome outcome,boolean acknowledgeUnknowns) {
        public VariantDecision { Objects.requireNonNull(projectSolutionId);Objects.requireNonNull(outcome); }
    }
    private static <T> List<T> bounded(List<T> values,int max) {var result=values==null?List.<T>of():List.copyOf(values);if(result.size()>max)throw new IllegalArgumentException("Too many assessment inputs");return result;}
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
                            AssertionKind kind, String citation, Instant recordedAt, Relationship relationship) {
        public Assertion(String id,String candidateId,Criterion criterion,String nodeCode,boolean positive,Boundary start,Boundary endExclusive,String organization,String environment,AssertionKind kind,String citation,Instant recordedAt) {
            this(id,candidateId,criterion,nodeCode,positive,start,endExclusive,organization,environment,kind,citation,recordedAt,null);
        }
        public Assertion {
            if(relationship!=null && criterion!=Criterion.RELATIONSHIPS)throw new IllegalArgumentException("Directed evidence requires RELATIONSHIPS criterion");
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
                             List<Candidate> candidates, List<Finding> findings, List<SourceEvidence> sources, String fingerprint,
                             List<RelationshipRequirement> relationshipRequirements,List<RelationshipFinding> relationshipFindings,List<VariantAssessment> variants) {
        public Comparison(UUID id,Demand demand,String evaluatorVersion,Instant createdAt,List<Candidate> candidates,List<Finding> findings,List<SourceEvidence> sources,String fingerprint) {this(id,demand,evaluatorVersion,createdAt,candidates,findings,sources,fingerprint,List.of(),List.of(),List.of());}
        public Comparison { candidates=List.copyOf(candidates); findings=List.copyOf(findings); sources=List.copyOf(sources);relationshipRequirements=bounded(relationshipRequirements,100);relationshipFindings=bounded(relationshipFindings,100);variants=bounded(variants,20); }
    }
    public record Review(UUID id, String comparisonFingerprint, String candidateId, String rationale,
                         Long projectSolutionId, boolean selectProduct,Integer coveragePercent,VariantDecision variantDecision) {
        public Review(UUID id,String comparisonFingerprint,String candidateId,String rationale,Long projectSolutionId,boolean selectProduct,Integer coveragePercent) {this(id,comparisonFingerprint,candidateId,rationale,projectSolutionId,selectProduct,coveragePercent,null);}
        public Review(UUID id,String comparisonFingerprint,String candidateId,String rationale,Long projectSolutionId,boolean selectProduct) {
            this(id,comparisonFingerprint,candidateId,rationale,projectSolutionId,selectProduct,null);
        }
        public Review { if(coveragePercent!=null && (coveragePercent<0 || coveragePercent>100))throw new IllegalArgumentException("Reviewed coverage must be 0–100"); Objects.requireNonNull(id); text(comparisonFingerprint,128); if(variantDecision==null)text(candidateId,3000);else if(candidateId!=null || selectProduct || (projectSolutionId!=null && !projectSolutionId.equals(variantDecision.projectSolutionId())))throw new IllegalArgumentException("Product and variant decisions are exclusive"); text(rationale,1800); if(selectProduct && projectSolutionId==null) throw new IllegalArgumentException("Selection requires an existing project solution"); }
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
