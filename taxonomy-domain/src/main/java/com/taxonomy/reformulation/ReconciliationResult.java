package com.taxonomy.reformulation;
import java.util.*;
public record ReconciliationResult(List<String> affectedSectionIds,Map<String,DecisionQuestion.SourceResolution> sourceResolutions,List<ValidationReport.Finding> findings) {
    public ReconciliationResult {affectedSectionIds=List.copyOf(affectedSectionIds);sourceResolutions=Map.copyOf(sourceResolutions);findings=List.copyOf(findings);}
}
