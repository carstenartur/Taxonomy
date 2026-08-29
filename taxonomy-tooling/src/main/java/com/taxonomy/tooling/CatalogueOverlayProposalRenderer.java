package com.taxonomy.tooling;

import com.taxonomy.tooling.CatalogueOverlayProposalModel.CandidateScore;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.FanOut;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.OverlayModel;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.Proposal;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.SemanticChange;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.Summary;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/** Deterministic JSON and Markdown rendering for catalogue-overlay proposals. */
final class CatalogueOverlayProposalRenderer {

    private CatalogueOverlayProposalRenderer() {
    }

    static Map<String, Object> proposalDocument(
            Path catalogue,
            Path overlayPath,
            String catalogueSha,
            String overlaySha,
            OverlayModel overlay,
            Summary summary,
            FanOut currentFanOut,
            FanOut proposedFanOut,
            List<SemanticChange> changes,
            List<Proposal> proposals) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", CatalogueOverlayProposalGenerator.OUTPUT_SCHEMA_VERSION);
        root.put("artifactType", "CATALOGUE_OVERLAY_PROPOSAL");
        root.put("algorithmVersion", CatalogueOverlayProposalGenerator.ALGORITHM_VERSION);
        root.put("automaticPromotionAllowed", false);

        LinkedHashMap<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("catalogueFile", catalogue.getFileName().toString());
        inputs.put("catalogueSha256", catalogueSha);
        inputs.put("overlayFile", overlayPath.getFileName().toString());
        inputs.put("overlaySha256", overlaySha);
        inputs.put("overlayMappingVersion", overlay.mappingVersion());
        root.put("inputs", inputs);

        LinkedHashMap<String, Object> scope = new LinkedHashMap<>();
        scope.put("taxonomyRoot", overlay.strictRoot());
        scope.put("sourceState", overlay.strictState());
        root.put("strictScope", scope);
        root.put("summary", summaryMap(summary));

        LinkedHashMap<String, Object> fanOut = new LinkedHashMap<>();
        fanOut.put("reviewedOverlay", fanOutEntries(currentFanOut));
        fanOut.put("proposal", fanOutEntries(proposedFanOut));
        root.put("fanOut", fanOut);
        root.put("semanticDiff", changes.stream()
                .map(CatalogueOverlayProposalRenderer::changeMap)
                .toList());
        root.put("proposals", proposals.stream()
                .map(CatalogueOverlayProposalRenderer::proposalMap)
                .toList());
        return root;
    }

    private static Map<String, Object> summaryMap(Summary summary) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("strictNodeCount", summary.strictNodeCount());
        result.put("reviewedLockedCount", summary.reviewedLockedCount());
        result.put("reviewRequiredCount", summary.reviewRequiredCount());
        result.put("newMappingCount", summary.newMappingCount());
        result.put("unresolvedCount", summary.unresolvedCount());
        result.put("semanticChangeCount", summary.semanticChangeCount());
        result.put("candidateFamilyCount", summary.candidateFamilyCount());
        result.put("currentMaxFanOut", summary.currentMaxFanOut());
        result.put("proposedMaxFanOut", summary.proposedMaxFanOut());
        return result;
    }

    private static List<Map<String, Object>> fanOutEntries(FanOut fanOut) {
        return fanOut.counts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    Map<String, Object> mapped = new LinkedHashMap<>();
                    mapped.put("parentCode", entry.getKey());
                    mapped.put("directChildren", entry.getValue());
                    return mapped;
                })
                .toList();
    }

    private static Map<String, Object> changeMap(SemanticChange change) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("code", change.code());
        result.put("changeType", change.changeType());
        result.put("previousParentCode", change.previousParentCode());
        result.put("proposedParentCode", change.proposedParentCode());
        result.put("previousSecondaryClassificationCodes",
                change.previousSecondaryClassificationCodes());
        result.put("proposedSecondaryClassificationCodes",
                change.proposedSecondaryClassificationCodes());
        result.put("confidence", change.confidence());
        result.put("reason", change.reason());
        return result;
    }

    private static Map<String, Object> proposalMap(Proposal proposal) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("code", proposal.code());
        result.put("title", proposal.title());
        result.put("sourceState", proposal.sourceState());
        result.put("sourceLevel", proposal.sourceLevel());
        result.put("sourceParentCode", proposal.sourceParentCode());
        result.put("currentOverlayParentCode", proposal.currentOverlayParentCode());
        result.put("analysisRole", proposal.analysisRole());
        result.put("currentSecondaryClassificationCodes",
                proposal.currentSecondaryClassificationCodes());
        result.put("proposedParentCode", proposal.proposedParentCode());
        result.put("proposedSecondaryClassificationCodes",
                proposal.proposedSecondaryClassificationCodes());
        result.put("confidence", proposal.confidence());
        result.put("status", proposal.status());
        result.put("decisionAuthority", proposal.decisionAuthority());
        result.put("reviewRequired", !"REVIEWED_LOCKED".equals(proposal.status()));
        result.put("unresolved", proposal.unresolved());
        result.put("reason", proposal.reason());
        result.put("existingJustification", proposal.existingJustification());
        result.put("rankedCandidates", proposal.rankedCandidates().stream()
                .map(CatalogueOverlayProposalRenderer::candidateMap)
                .toList());
        return result;
    }

    private static Map<String, Object> candidateMap(CandidateScore candidate) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("code", candidate.code());
        result.put("title", candidate.title());
        result.put("score", candidate.score());
        result.put("confidence", candidate.confidence());
        result.put("matchedTokens", candidate.matchedTokens());
        return result;
    }

    static String reviewReport(
            Path catalogue,
            Path overlayPath,
            String catalogueSha,
            String overlaySha,
            OverlayModel overlay,
            Summary summary,
            FanOut currentFanOut,
            FanOut proposedFanOut,
            List<SemanticChange> changes,
            List<Proposal> proposals) {
        StringBuilder report = new StringBuilder();
        report.append("# Catalogue overlay proposal review\n\n")
                .append("> **Review-only artifact.** This report never updates the reviewed ")
                .append("`nato-taxonomy.json` overlay automatically. Every accepted change must ")
                .append("be promoted through a reviewed Git commit and the runtime validators.\n\n")
                .append("## Reproducible inputs\n\n")
                .append("- Algorithm: `").append(CatalogueOverlayProposalGenerator.ALGORITHM_VERSION).append("`\n")
                .append("- Catalogue: `").append(catalogue.getFileName()).append("` (`")
                .append(catalogueSha).append("`)\n")
                .append("- Reviewed overlay: `").append(overlayPath.getFileName()).append("` (`")
                .append(overlaySha).append("`)\n")
                .append("- Mapping version: `").append(escape(overlay.mappingVersion())).append("`\n")
                .append("- Strict scope: `").append(escape(overlay.strictRoot())).append("` / `")
                .append(escape(overlay.strictState())).append("`\n\n")
                .append("## Summary\n\n")
                .append("| Measure | Value |\n|---|---:|\n")
                .append("| Strict-scope nodes | ").append(summary.strictNodeCount()).append(" |\n")
                .append("| Reviewed and locked | ").append(summary.reviewedLockedCount()).append(" |\n")
                .append("| Requiring review | ").append(summary.reviewRequiredCount()).append(" |\n")
                .append("| New mappings | ").append(summary.newMappingCount()).append(" |\n")
                .append("| Unresolved proposals | ").append(summary.unresolvedCount()).append(" |\n")
                .append("| Semantic changes | ").append(summary.semanticChangeCount()).append(" |\n")
                .append("| Candidate product families | ").append(summary.candidateFamilyCount()).append(" |\n")
                .append("| Maximum current fan-out | ").append(summary.currentMaxFanOut()).append(" |\n")
                .append("| Maximum proposed fan-out | ").append(summary.proposedMaxFanOut()).append(" |\n\n")
                .append("## Semantic diff\n\n");

        if (changes.isEmpty()) {
            report.append("No automated semantic changes are proposed.\n\n");
        } else {
            report.append("| Code | Change | Current parent | Proposed parent | Confidence | Reason |\n")
                    .append("|---|---|---|---|---:|---|\n");
            for (SemanticChange change : changes) {
                report.append('|').append(cell(change.code()))
                        .append('|').append(cell(change.changeType()))
                        .append('|').append(cell(change.previousParentCode()))
                        .append('|').append(cell(change.proposedParentCode()))
                        .append('|').append(change.confidence())
                        .append('|').append(cell(change.reason())).append("|\n");
            }
            report.append('\n');
        }

        report.append("## Review queue\n\n")
                .append("| Code | Title | Status | Source parent | Current overlay parent | Proposed parent | Confidence |\n")
                .append("|---|---|---|---|---|---|---:|\n");
        for (Proposal proposal : proposals) {
            if ("REVIEWED_LOCKED".equals(proposal.status())) {
                continue;
            }
            report.append('|').append(cell(proposal.code()))
                    .append('|').append(cell(proposal.title()))
                    .append('|').append(cell(proposal.status()))
                    .append('|').append(cell(proposal.sourceParentCode()))
                    .append('|').append(cell(proposal.currentOverlayParentCode()))
                    .append('|').append(cell(proposal.proposedParentCode()))
                    .append('|').append(proposal.confidence()).append("|\n");
        }
        report.append("\n## Fan-out by parent\n\n")
                .append("| Parent | Current direct children | Proposed direct children |\n")
                .append("|---|---:|---:|\n");
        Set<String> parents = new java.util.TreeSet<>();
        parents.addAll(currentFanOut.counts().keySet());
        parents.addAll(proposedFanOut.counts().keySet());
        for (String parent : parents) {
            report.append('|').append(cell(parent))
                    .append('|').append(currentFanOut.counts().getOrDefault(parent, 0))
                    .append('|').append(proposedFanOut.counts().getOrDefault(parent, 0))
                    .append("|\n");
        }
        report.append("\n## Promotion contract\n\n")
                .append("1. Review every `REVIEW_REQUIRED_*`, `NEW_*`, and unresolved row.\n")
                .append("2. Apply accepted decisions to a new overlay mapping version on a branch.\n")
                .append("3. Run the runtime unknown-parent, source-drift, cross-root, cycle, strict-coverage, and PRODUCT-leaf validators.\n")
                .append("4. Merge only through normal review and CI.\n");
        return report.toString();
    }

    private static String escape(String value) {
        return nullToEmpty(value).replace("`", "\\`");
    }

    private static String cell(String value) {
        if (value == null) {
            return "—";
        }
        return value.replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
