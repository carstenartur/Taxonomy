package com.taxonomy.export;
import com.taxonomy.dto.*;
import java.util.*;
/** Shared executable contract used by JUnit and the dependency-restricted local verifier. */
public final class RecoveryProjectionProbe {
    public static void main(String[] args) { verify(); System.out.println("Recovery diagram metadata contract passed"); }
    public static void verify() {
        var view = new RequirementArchitectureView();
        view.setAnalysisCoverage(new AnalysisCoverage(Map.of("BP", new AnalysisCoverage.NodeAssessment(
                AnalysisCoverage.State.UNKNOWN, null, null, AnalysisCoverage.Descendants.UNASSESSED, "LEFT_OPEN:q")), 0, 1, 1));
        var result = new DiagramProjectionService().projectRaw(view, "Hospital");
        if (!result.title().contains("PARTIAL") || !result.title().contains("BP"))
            throw new AssertionError("A graphical export lost the open assessment warning");
        if (!result.nodes().isEmpty()) throw new AssertionError("A coverage warning invented a catalogue node");
    }
}
