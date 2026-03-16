package com.taxonomy.relations.service;

import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Validates a candidate relation and computes a confidence score.
 *
 * <p>Validation rules:
 * <ol>
 *   <li>Compatibility — source/target roots must be allowed for the relation type.</li>
 *   <li>Duplicate check — relation must not already exist.</li>
 *   <li>Self-relation check — source must differ from target.</li>
 * </ol>
 *
 * <p>Confidence is derived from the candidate's search rank position
 * (higher rank → higher confidence).
 */
@Service
public class RelationValidationService {

    private static final Logger log = LoggerFactory.getLogger(RelationValidationService.class);

    private final RelationCompatibilityMatrix compatibilityMatrix;
    private final TaxonomyRelationRepository relationRepository;
    private final RelationQualityService qualityService;

    public RelationValidationService(RelationCompatibilityMatrix compatibilityMatrix,
                                     TaxonomyRelationRepository relationRepository,
                                     RelationQualityService qualityService) {
        this.compatibilityMatrix = compatibilityMatrix;
        this.relationRepository = relationRepository;
        this.qualityService = qualityService;
    }

    /**
     * Validates a candidate relation.
     *
     * @return a {@link ValidationResult} with pass/fail and confidence
     */
    public ValidationResult validate(TaxonomyNode source,
                                     TaxonomyNodeDto candidateTarget,
                                     RelationType relationType,
                                     int rank,
                                     int totalCandidates) {
        // Self-relation
        if (source.getCode().equals(candidateTarget.getCode())) {
            return ValidationResult.fail("Self-relation not allowed");
        }

        // Compatibility check
        if (!compatibilityMatrix.isCompatible(
                source.getTaxonomyRoot(),
                candidateTarget.getTaxonomyRoot(),
                relationType)) {
            return ValidationResult.fail(
                    "Incompatible roots: " + source.getTaxonomyRoot()
                    + " → " + candidateTarget.getTaxonomyRoot()
                    + " for " + relationType);
        }

        // Duplicate check (already exists as a persisted relation)
        boolean exists = !relationRepository
                .findBySourceNodeCodeAndRelationTypeIn(
                        source.getCode(),
                        java.util.List.of(relationType))
                .stream()
                .filter(r -> r.getTargetNode().getCode().equals(candidateTarget.getCode()))
                .toList()
                .isEmpty();
        if (exists) {
            return ValidationResult.fail("Relation already exists");
        }

        // Confidence: 80% rank-based + 20% acceptance history feedback
        double rankConfidence = computeConfidence(rank, totalCandidates);
        double historyWeight = qualityService.acceptanceHistoryWeight(
                source.getTaxonomyRoot(),
                candidateTarget.getTaxonomyRoot(),
                relationType);
        double confidence = 0.80 * rankConfidence + 0.20 * historyWeight;

        String rationale = String.format(
                "%s [%s] → %s [%s] (%s), rank %d/%d",
                source.getCode(), source.getTaxonomyRoot(),
                candidateTarget.getCode(), candidateTarget.getTaxonomyRoot(),
                relationType, rank + 1, totalCandidates);

        return ValidationResult.pass(confidence, rationale);
    }

    /**
     * Computes confidence from the rank position.
     * Rank 0 → highest confidence (0.95), higher ranks → lower confidence (min 0.3).
     */
    public double computeConfidence(int rank, int totalCandidates) {
        if (totalCandidates <= 1) return 0.9;
        // Linear decay from 0.95 to 0.3
        double ratio = (double) rank / (totalCandidates - 1);
        return 0.95 - (0.65 * ratio);
    }

    // ── Inner result record ───────────────────────────────────────────────────

    public static class ValidationResult {
        private final boolean valid;
        private final double confidence;
        private final String rationale;

        private ValidationResult(boolean valid, double confidence, String rationale) {
            this.valid = valid;
            this.confidence = confidence;
            this.rationale = rationale;
        }

        public static ValidationResult pass(double confidence, String rationale) {
            return new ValidationResult(true, confidence, rationale);
        }

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, 0.0, reason);
        }

        public boolean isValid() { return valid; }
        public double getConfidence() { return confidence; }
        public String getRationale() { return rationale; }
    }
}
