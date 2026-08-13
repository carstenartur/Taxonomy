package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.model.RelationType;
import org.springframework.stereotype.Service;

/**
 * Validates a candidate relation and computes a confidence score.
 *
 * <p>Repository/workspace/branch duplicate detection is deliberately performed
 * by {@link RelationProposalService} against one immutable identity snapshot.
 * This service therefore remains a persistence-free compatibility and scoring
 * component.</p>
 *
 * <p>Validation rules:
 * <ol>
 *   <li>Compatibility — source/target roots must be allowed for the relation type.</li>
 *   <li>Self-relation check — source must differ from target.</li>
 * </ol>
 *
 * <p>Confidence is derived from the candidate's search rank position
 * (higher rank → higher confidence).
 */
@Service
public class RelationValidationService {

    private final RelationCompatibilityMatrix compatibilityMatrix;
    private final RelationQualityService qualityService;

    public RelationValidationService(
            RelationCompatibilityMatrix compatibilityMatrix,
            RelationQualityService qualityService) {
        this.compatibilityMatrix = compatibilityMatrix;
        this.qualityService = qualityService;
    }

    /**
     * Validates a candidate relation.
     *
     * @return a {@link ValidationResult} with pass/fail and confidence
     */
    public ValidationResult validate(
            TaxonomyNode source,
            TaxonomyNodeDto candidateTarget,
            RelationType relationType,
            int rank,
            int totalCandidates) {
        if (source.getCode().equals(candidateTarget.getCode())) {
            return ValidationResult.fail("Self-relation not allowed");
        }

        if (!compatibilityMatrix.isCompatible(
                source.getTaxonomyRoot(),
                candidateTarget.getTaxonomyRoot(),
                relationType)) {
            return ValidationResult.fail(
                    "Incompatible roots: " + source.getTaxonomyRoot()
                    + " → " + candidateTarget.getTaxonomyRoot()
                    + " for " + relationType);
        }

        // Confidence: 80% rank-based + 20% acceptance history feedback.
        // Repository scoping of that feedback is tracked separately by #728.
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
        double ratio = (double) rank / (totalCandidates - 1);
        return 0.95 - (0.65 * ratio);
    }

    public static class ValidationResult {
        private final boolean valid;
        private final double confidence;
        private final String rationale;

        private ValidationResult(
                boolean valid,
                double confidence,
                String rationale) {
            this.valid = valid;
            this.confidence = confidence;
            this.rationale = rationale;
        }

        public static ValidationResult pass(
                double confidence,
                String rationale) {
            return new ValidationResult(true, confidence, rationale);
        }

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, 0.0, reason);
        }

        public boolean isValid() {
            return valid;
        }

        public double getConfidence() {
            return confidence;
        }

        public String getRationale() {
            return rationale;
        }
    }
}
