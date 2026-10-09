package com.taxonomy.reporting.render.decision;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;

import java.util.ArrayList;
import java.util.List;

/** Small mandatory source notice, independent of optional presentation sections. */
public final class DecisionReportPresentation {
    private DecisionReportPresentation() {}
    /** One representative outcome per root, using the complete tree rather than the top-20 ranking. */
    public static List<String> compactReasons(DecisionRationaleReport report) {
        var labels = new DecisionReportLabels(report.languageTag());
        var groups = new ArrayList<List<com.taxonomy.reporting.api.document.DecisionTreeOverview.DecisionTreeRow>>();
        for (var row : report.scope().decisionTree().rows()) {
            if (row.depth() == 0 || groups.isEmpty()) groups.add(new ArrayList<>());
            groups.getLast().add(row);
        }
        var lines = new ArrayList<String>();
        for (var group : groups) {
            var root = group.getFirst();
            var outcome = group.stream().filter(row -> row.disposition() == DecisionRationaleReport.Disposition.LEAF_CANDIDATE)
                    .sorted(java.util.Comparator.comparingInt((com.taxonomy.reporting.api.document.DecisionTreeOverview.DecisionTreeRow row) -> row.score() == null ? -1 : row.score()).reversed()
                            .thenComparing(row -> row.code())).findFirst().orElse(root);
            // Legacy callers may only carry ranked-leaf evidence. Prefer its saved reason when available.
            var legacyLeaf = report.leadingLeaves().stream().filter(leaf -> root.code().equals(leaf.taxonomyRoot())).findFirst().orElse(null);
            String code = outcome.code(), title = outcome.title(), reason = report.scope().recordedReasons().get(code);
            if (reason == null && legacyLeaf != null) { code = legacyLeaf.code(); title = legacyLeaf.title(); reason = legacyLeaf.reason(); }
            lines.add(root.code() + " · " + code + " · " + title);
            String rootReason = report.scope().recordedReasons().get(root.code());
            if (!root.code().equals(code) && rootReason != null && !rootReason.equals(reason))
                lines.add(labels.aiReason() + " (" + root.code() + "): " + rootReason);
            String provenance = legacyLeaf != null && report.scope().recordedReasons().get(code) == null
                    ? switch (legacyLeaf.reasonSource()) {
                        case AI_SCORING -> labels.aiReason();
                        case DETERMINISTIC_TRACE -> labels.deterministicReason();
                        case MISSING -> labels.missingReason();
                    } : labels.aiReason();
            lines.add(reason == null || reason.isBlank() ? labels.missingReason() : provenance + ": " + reason);
        }
        return List.copyOf(lines);
    }

    public static List<String> sourceNotice(DecisionRationaleReport report) {
        boolean de = new DecisionReportLabels(report.languageTag()).german();
        var scope = report.scope();
        var lines = new ArrayList<String>();
        var labels = new DecisionReportLabels(report.languageTag());
        String status = switch (report.status()) {
            case FINAL -> labels.finalStatus(); case FINAL_WITH_WARNINGS -> labels.finalWarnings();
            case DRAFT_INCOMPLETE -> labels.draft(); case NO_RESULT -> labels.noResult();
        };
        lines.add("Status: " + status);
        lines.add((de ? "Berichtsauswahl: " : "Report selection: ") + String.join(", ", scope.reportRoots()));
        lines.add((de ? "Analyseumfang: " : "Analysis scope: ") + (scope.analysisScope() == null
                ? (de ? "historisch nicht aufgezeichnet" : "not recorded in this source")
                : (scope.analysisScope().taxonomyRoots().isEmpty() ? (de ? "alle Taxonomien" : "all taxonomies")
                    : String.join(", ", scope.analysisScope().taxonomyRoots())) + " · " + scope.analysisScope().mode()));
        lines.add("Snapshot: " + report.metadata().analysisSnapshotId() + " · "
                + (de ? "Anforderungsversion: " : "Requirement version: ") + report.metadata().requirementVersionNumber());
        lines.add((de ? "Quellfingerabdruck: " : "Source fingerprint: ") + report.metadata().analysisSnapshotFingerprintSha256());
        if (scope.options().includes(DecisionReportOptions.Section.ARCHITECTURE) && report.architecture() == null)
            lines.add(de ? "Keine gespeicherte Architektur für diese Berichtsauswahl." : "No saved architecture for this report selection.");
        lines.addAll(report.warnings());
        return List.copyOf(lines);
    }
}
