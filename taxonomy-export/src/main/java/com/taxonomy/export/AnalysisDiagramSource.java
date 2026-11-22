package com.taxonomy.export;

import com.taxonomy.dto.AnalysisResult;

/** Source information for diagrams projected directly from a server-side assessment. */
public record AnalysisDiagramSource(String provider, String status, String branch, String commit) {
    public static AnalysisDiagramSource from(AnalysisResult result) {
        var model = result.getModelBinding();
        var view = result.getViewContext();
        String branch = model != null ? model.branch() : view != null ? view.basedOnBranch() : null;
        String commit = model != null ? model.commit() : view != null ? view.basedOnCommit() : null;
        String status = result.getAnalysisCoverage() != null && result.getAnalysisCoverage().hasOpenEvaluations()
                ? "PARTIAL" : result.getStatus();
        return new AnalysisDiagramSource(result.getProvider(), status, branch, commit);
    }
}
