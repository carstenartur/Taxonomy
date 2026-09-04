package com.taxonomy.architecture.decision;

import com.taxonomy.architecture.decision.DecisionRationaleReport.ChildDecision;
import com.taxonomy.architecture.decision.DecisionRationaleReport.DecisionChapter;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ExecutiveSummary;
import com.taxonomy.architecture.decision.DecisionRationaleReport.LeafCandidate;
import com.taxonomy.architecture.decision.DecisionRationaleReport.PathStep;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ReportMetadata;
import com.taxonomy.dto.AnalysisScoreDetail;
import com.taxonomy.dto.AnalysisScoreSemanticsFingerprint;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Applies explicit product-score semantics to the format-neutral decision report.
 *
 * <p>The hierarchy builder receives comparable effective scores so ranking remains valid. This
 * adapter then restores the exact independent product suitability in the secondary percentage
 * column and in the human-readable evidence while retaining the effective score for ranking.</p>
 */
@Component
public class DecisionRationaleScoreSemanticsAdapter {

    public Map<String, String> enrichReasons(
            Map<String, String> reasons,
            Map<String, AnalysisScoreDetail> scoreDetails,
            Locale locale) {
        Map<String, String> result = new LinkedHashMap<>();
        if (reasons != null) {
            result.putAll(reasons);
        }
        boolean german = locale != null
                && "de".equalsIgnoreCase(locale.getLanguage());
        safeDetails(scoreDetails).forEach((code, detail) -> {
            if (detail == null || !detail.isProductSuitability()) {
                return;
            }
            String evidence = evidence(detail, german);
            String original = result.get(code);
            if (original == null || original.isBlank()) {
                result.put(code, evidence);
            } else if (!original.contains(evidence)) {
                result.put(code, evidence + " " + original.strip());
            }
        });
        return result;
    }

    public DecisionRationaleReport adapt(
            DecisionRationaleReport report,
            Map<String, AnalysisScoreDetail> scoreDetails,
            Locale locale) {
        if (report == null) {
            return null;
        }
        Map<String, AnalysisScoreDetail> details = safeDetails(scoreDetails);
        boolean german = locale != null
                && "de".equalsIgnoreCase(locale.getLanguage());

        ExecutiveSummary summary = report.executiveSummary();
        ExecutiveSummary adaptedSummary = summary == null ? null : new ExecutiveSummary(
                adaptLeaf(summary.leadingLeaf(), details, german),
                summary.path().stream()
                        .map(step -> adaptPathStep(step, details, german))
                        .toList(),
                summary.conciseConclusion(),
                appendMethodology(summary.methodologyNote(), details, german));

        List<DecisionChapter> chapters = report.chapters().stream()
                .map(chapter -> adaptChapter(chapter, details, german))
                .toList();
        List<LeafCandidate> leaves = report.leadingLeaves().stream()
                .map(leaf -> adaptLeaf(leaf, details, german))
                .toList();
        ReportMetadata metadata = report.metadata();
        boolean alreadyAdapted = !details.isEmpty() && details.equals(report.scoreDetails());
        if (metadata != null && !alreadyAdapted && !details.isEmpty()) {
            metadata = metadata.withAnalysisSnapshotFingerprintSha256(
                    AnalysisScoreSemanticsFingerprint.extend(
                            metadata.analysisSnapshotFingerprintSha256(), details));
        }

        return new DecisionRationaleReport(
                report.title(),
                report.languageTag(),
                report.requirement(),
                report.status(),
                metadata,
                adaptedSummary,
                chapters,
                leaves,
                report.warnings(),
                report.productCoverageGaps(),
                report.discrepancies(),
                report.viewContext(),
                details);
    }

    private DecisionChapter adaptChapter(
            DecisionChapter chapter,
            Map<String, AnalysisScoreDetail> details,
            boolean german) {
        List<ChildDecision> children = chapter.children().stream()
                .map(child -> adaptChild(child, details, german))
                .toList();
        return new DecisionChapter(
                chapter.number(),
                chapter.parentCode(),
                chapter.parentTitle(),
                chapter.parentDescription(),
                chapter.parentScore(),
                chapter.hierarchyLevel(),
                chapter.complete(),
                chapter.decisionSummary(),
                chapter.comparativeRationale(),
                children,
                chapter.missingChildCodes());
    }

    private ChildDecision adaptChild(
            ChildDecision child,
            Map<String, AnalysisScoreDetail> details,
            boolean german) {
        AnalysisScoreDetail detail = details.get(child.code());
        if (detail == null || !detail.isProductSuitability()) {
            return new ChildDecision(
                    child.code(), child.title(), child.description(), child.absoluteScore(),
                    bounded(child.localSharePercent()), child.rank(), child.leadingSibling(),
                    child.disposition(), child.reason(), child.reasonSource(), child.leaf());
        }
        return new ChildDecision(
                child.code(), child.title(), child.description(), detail.effectiveRelevance(),
                (double) detail.rawScore(), child.rank(), child.leadingSibling(),
                child.disposition(), enrichedReason(child.reason(), detail, german),
                child.reasonSource(), child.leaf());
    }

    private PathStep adaptPathStep(
            PathStep step,
            Map<String, AnalysisScoreDetail> details,
            boolean german) {
        AnalysisScoreDetail detail = details.get(step.code());
        if (detail == null || !detail.isProductSuitability()) {
            return new PathStep(
                    step.position(), step.code(), step.title(), step.absoluteScore(),
                    bounded(step.localSharePercent()), step.reason(), step.reasonSource());
        }
        return new PathStep(
                step.position(), step.code(), step.title(), detail.effectiveRelevance(),
                (double) detail.rawScore(), enrichedReason(step.reason(), detail, german),
                step.reasonSource());
    }

    private LeafCandidate adaptLeaf(
            LeafCandidate leaf,
            Map<String, AnalysisScoreDetail> details,
            boolean german) {
        if (leaf == null) {
            return null;
        }
        AnalysisScoreDetail detail = details.get(leaf.code());
        if (detail == null || !detail.isProductSuitability()) {
            return leaf;
        }
        return new LeafCandidate(
                leaf.code(), leaf.title(), detail.effectiveRelevance(), leaf.taxonomyRoot(),
                leaf.depth(), leaf.hierarchyPath(), enrichedReason(leaf.reason(), detail, german),
                leaf.reasonSource());
    }

    private String appendMethodology(
            String existing,
            Map<String, AnalysisScoreDetail> details,
            boolean german) {
        boolean hasProducts = details.values().stream()
                .anyMatch(detail -> detail != null && detail.isProductSuitability());
        if (!hasProducts) {
            return existing;
        }
        String addition = german
                ? "Konkrete Produkte werden mit einer unabhängigen Eignung von 0 bis 100 bewertet; für Rangfolge und Architektur wird die mit der Familienrelevanz gewichtete effektive Relevanz verwendet."
                : "Concrete products receive an independent 0–100 suitability score; ranking and architecture use the effective relevance weighted by the product-family relevance.";
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        return existing.contains(addition) ? existing : existing.strip() + " " + addition;
    }

    private String enrichedReason(
            String original,
            AnalysisScoreDetail detail,
            boolean german) {
        String evidence = evidence(detail, german);
        if (original == null || original.isBlank()) {
            return evidence;
        }
        return original.contains(evidence) ? original : evidence + " " + original.strip();
    }

    private String evidence(AnalysisScoreDetail detail, boolean german) {
        return german
                ? "Produkteignung: " + detail.rawScore()
                        + " %; effektive Relevanz: " + detail.effectiveRelevance() + "/100."
                : "Product suitability: " + detail.rawScore()
                        + "%; effective relevance: " + detail.effectiveRelevance() + "/100.";
    }

    private Double bounded(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return value == null ? null : 0.0;
        }
        return Math.max(0.0, Math.min(100.0, value));
    }

    private Map<String, AnalysisScoreDetail> safeDetails(
            Map<String, AnalysisScoreDetail> scoreDetails) {
        if (scoreDetails == null || scoreDetails.isEmpty()) {
            return Map.of();
        }
        Map<String, AnalysisScoreDetail> result = new LinkedHashMap<>();
        scoreDetails.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String code = entry.getKey().strip();
                    if (result.containsKey(code)) {
                        throw new IllegalArgumentException(
                                "Score details contain multiple entries for canonical node code "
                                        + code);
                    }
                    result.put(code, entry.getValue());
                });
        return Map.copyOf(result);
    }
}
