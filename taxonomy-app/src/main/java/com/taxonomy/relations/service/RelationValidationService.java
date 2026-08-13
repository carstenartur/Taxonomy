package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;

/**
 * Validates a candidate relation and computes a confidence score.
 *
 * <p>Repository/workspace/branch duplicate detection is performed by
 * {@link RelationProposalService} against one immutable identity snapshot.
 * Review-history feedback is delegated with the same explicit context.</p>
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

    /** Validates and scores a candidate in the proposal-generation context. */
    public ValidationResult validate(
            TaxonomyNode source,
            TaxonomyNodeDto candidateTarget,
            RelationType relationType,
            int rank,
            int totalCandidates,
            RepositoryContext context) {
        if (source.getCode().equals(candidateTarget.getCode())) {
            return ValidationResult.fail("Self-relation not allowed");
        }

        if (!compatibilityMatrix.isCompatible(
                source.getTaxonomyRoot(),
                candidateTarget.getTaxonomyRoot(),
                relationType)) {
            return ValidationResult.fail(
                    "Incompatible roots: " + source.getTaxonomyRoot()
                    + " to " + candidateTarget.getTaxonomyRoot()
                    + " for " + relationType);
        }

        double rankConfidence = computeConfidence(rank, totalCandidates);
        double historyWeight = qualityService.acceptanceHistoryWeight(
                source.getTaxonomyRoot(),
                candidateTarget.getTaxonomyRoot(),
                relationType,
                context);
        double confidence = 0.80 * rankConfidence + 0.20 * historyWeight;

        String rationale = String.format(
                "%s [%s] to %s [%s] (%s), rank %d/%d",
                source.getCode(), source.getTaxonomyRoot(),
                candidateTarget.getCode(), candidateTarget.getTaxonomyRoot(),
                relationType, rank + 1, totalCandidates);

        return ValidationResult.pass(confidence, rationale);
    }

    /**
     * Computes confidence from the rank position.
     * Rank 0 has the highest confidence, later ranks decline to 0.3.
     */
    public double computeConfidence(int rank, int totalCandidates) {
        if (totalCandidates <= 1) {
            return 0.9;
        }
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
