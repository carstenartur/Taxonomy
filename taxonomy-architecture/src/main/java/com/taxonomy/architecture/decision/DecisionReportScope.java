package com.taxonomy.architecture.decision;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.taxonomy.architecture.report.DecisionTreeOverview;
import com.taxonomy.dto.AnalysisCoverage;
import com.taxonomy.dto.AnalysisScope;
import java.util.*;

/** Recorded source scope and an independent, reproducible report selection. */
public record DecisionReportScope(AnalysisScope analysisScope, AnalysisCoverage analysisCoverage,
        List<Root> availableRoots, Set<String> reportRoots,
        @JsonIgnore Set<String> selectedNodeCodes, DecisionTreeOverview decisionTree,
        DecisionReportOptions options, boolean analysisComplete, boolean selectionComplete,
        @JsonIgnore String scoreSemanticsFingerprint, Map<String, String> recordedReasons) {
    public record Root(String code, String title, boolean inAnalysisScope) {}

    public DecisionReportScope {
        availableRoots = List.copyOf(availableRoots);
        reportRoots = Collections.unmodifiableSet(new LinkedHashSet<>(reportRoots));
        selectedNodeCodes = Set.copyOf(selectedNodeCodes);
        recordedReasons = recordedReasons == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(recordedReasons));
        Objects.requireNonNull(decisionTree);
        options = options == null ? DecisionReportOptions.full() : options;
    }

    public DecisionReportScope(AnalysisScope analysisScope, AnalysisCoverage analysisCoverage,
            List<Root> availableRoots, Set<String> reportRoots, Set<String> selectedNodeCodes,
            DecisionTreeOverview decisionTree, DecisionReportOptions options,
            boolean analysisComplete, boolean selectionComplete) {
        this(analysisScope, analysisCoverage, availableRoots, reportRoots, selectedNodeCodes, decisionTree,
                options, analysisComplete, selectionComplete, null, Map.of());
    }

    public DecisionReportScope withScoreSemanticsFingerprint(String fingerprint) {
        return new DecisionReportScope(analysisScope, analysisCoverage, availableRoots, reportRoots,
                selectedNodeCodes, decisionTree, options, analysisComplete, selectionComplete, fingerprint, recordedReasons);
    }

    public DecisionReportScope withRecordedReasons(Map<String, String> reasons) {
        return new DecisionReportScope(analysisScope, analysisCoverage, availableRoots, reportRoots,
                selectedNodeCodes, decisionTree, options, analysisComplete, selectionComplete, scoreSemanticsFingerprint, reasons);
    }

    public static DecisionReportScope legacy(List<DecisionRationaleReport.DecisionChapter> chapters) {
        var tree = DecisionTreeOverview.from(chapters);
        return new DecisionReportScope(null, null, List.of(), Set.of(), Set.of(), tree,
                DecisionReportOptions.full(), false, false);
    }

    public DecisionReportScope withCoverage(AnalysisCoverage coverage) {
        return new DecisionReportScope(analysisScope, coverage, availableRoots, reportRoots, selectedNodeCodes,
                decisionTree, options, analysisComplete && (coverage == null || !coverage.hasOpenEvaluations()),
                selectionComplete, scoreSemanticsFingerprint, recordedReasons);
    }
}
