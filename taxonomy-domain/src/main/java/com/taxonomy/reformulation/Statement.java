package com.taxonomy.reformulation;
import java.util.List;
/** Provenance is independent of expert review and human editing. */
public record Statement(String id, String wording, List<SourceSpan> sourceSpans, Provenance provenance,
        List<String> architectureLinks, List<String> questionDependencies, String conditionalValidity,
        EditingOrigin editingOrigin, String reviewState) {
    public enum Provenance { ORIGINAL, CATALOGUE_INSPIRATION, ARCHITECTURE_HYPOTHESIS, MODEL_ADDITION, HUMAN_DECISION }
    public enum EditingOrigin { SOURCE, MODEL, HUMAN }
    public Statement { ReformulationBaseline.requireText(id,"statement id"); ReformulationBaseline.requireText(wording,"wording");
        sourceSpans=List.copyOf(sourceSpans); architectureLinks=List.copyOf(architectureLinks); questionDependencies=List.copyOf(questionDependencies);
        java.util.Objects.requireNonNull(provenance); java.util.Objects.requireNonNull(editingOrigin); }
    /** UTF-16 offsets into the full baseline original, end exclusive. */
    public record SourceSpan(int start, int end, String exactText) {
        public SourceSpan { if(start<0 || end<=start || exactText==null || exactText.length()!=end-start) throw new IllegalArgumentException("Invalid source span"); }
        public boolean matches(String original) { return end<=original.length() && original.substring(start,end).equals(exactText); }
    }
}
