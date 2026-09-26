package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

public final class RecoveryExchangeProbe {
    static void check(boolean b, String m) { if (!b) throw new AssertionError(m); }
    public static void verify() {
        var coverage = new AnalysisCoverage(Map.of(
                "BP", new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.RELEVANT, 70, 70,
                        AnalysisCoverage.Descendants.UNASSESSED, null),
                "IP", new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                        AnalysisCoverage.Descendants.UNASSESSED, "LEFT_OPEN:q")), 1, 1, 1);
        var saved = new SavedAnalysis(); saved.setVersion(3); saved.setRequirement("Hospital");
        saved.setScores(Map.of("BP",70)); saved.setRawScores(Map.of("BP",70));
        saved.setAnalysisCoverage(coverage); saved.setAnalysisStatus("SUCCESS");
        saved.validateCoverageEvidence(); check(saved.getAnalysisStatus().equals("PARTIAL"), "partial metadata promoted to success");
        var mapper = new ObjectMapper();
        var copy = mapper.readValue(mapper.writeValueAsString(saved), SavedAnalysis.class);
        copy.validateCoverageEvidence(); check(copy.getAnalysisCoverage().equals(coverage), "coverage lost in JSON round trip");
        check(!copy.getScores().containsKey("IP"), "unknown became zero");
        copy.setRawScores(Map.of("BP",20));
        try { copy.validateCoverageEvidence(); throw new AssertionError("inconsistent raw evidence accepted"); }
        catch (IllegalArgumentException expected) { }
        copy.setRawScores(null);
        try { copy.validateCoverageEvidence(); throw new AssertionError("missing raw evidence accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) { verify(); System.out.println("Version 3 recovery exchange contracts passed"); }
}
