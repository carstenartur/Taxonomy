package com.taxonomy.tooling;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Package-local immutable data exchanged by proposal generation stages. */
final class CatalogueOverlayProposalModel {

    private CatalogueOverlayProposalModel() {
    }

    record OverlayModel(
            int schemaVersion,
            String mode,
            String baseCatalogue,
            String mappingVersion,
            String strictRoot,
            String strictState,
            Map<String, Patch> patches) {
    }

    record Patch(
            String code,
            String expectedTitle,
            String expectedState,
            String parentCode,
            String analysisRole,
            List<String> secondaryClassificationCodes,
            BigDecimal confidence,
            boolean reviewRequired,
            String justification) {
    }

    record SourceCatalogue(String rootCode, Map<String, SourceNode> nodes) {
    }

    record SourceNode(
            String code,
            String uuid,
            String title,
            String description,
            String sourceParentCode,
            String state,
            int sourceLevel) {

        SourceNode withParent(String parent) {
            return new SourceNode(
                    code, uuid, title, description, parent, state, sourceLevel);
        }
    }

    record CandidateScore(
            String code,
            String title,
            int score,
            BigDecimal confidence,
            List<String> matchedTokens) {
    }

    record Proposal(
            String code,
            String title,
            String sourceState,
            int sourceLevel,
            String sourceParentCode,
            String currentOverlayParentCode,
            String analysisRole,
            List<String> currentSecondaryClassificationCodes,
            String proposedParentCode,
            List<String> proposedSecondaryClassificationCodes,
            BigDecimal confidence,
            String status,
            String decisionAuthority,
            boolean unresolved,
            String reason,
            String existingJustification,
            List<CandidateScore> rankedCandidates) {
    }

    record SemanticChange(
            String code,
            String changeType,
            String previousParentCode,
            String proposedParentCode,
            List<String> previousSecondaryClassificationCodes,
            List<String> proposedSecondaryClassificationCodes,
            BigDecimal confidence,
            String reason) {
    }

    record FanOut(Map<String, Integer> counts, int maximum) {
    }

    record Summary(
            long strictNodeCount,
            long reviewedLockedCount,
            long reviewRequiredCount,
            long newMappingCount,
            long unresolvedCount,
            long semanticChangeCount,
            long candidateFamilyCount,
            long currentMaxFanOut,
            long proposedMaxFanOut) {
    }
}
