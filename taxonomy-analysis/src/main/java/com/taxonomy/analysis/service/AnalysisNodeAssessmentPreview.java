package com.taxonomy.analysis.service;

import com.taxonomy.dto.RequirementNodeAssessment;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A bounded live view. Keep every claim for a displayed node so mixed evidence remains interpretable. */
public final class AnalysisNodeAssessmentPreview {
    private static final int MAX_JSON_BYTES = 128 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private int bytes = 2; // Outer object braces.
    private boolean truncated;

    private record Entry(List<RequirementNodeAssessment> claims, int bytes) { }

    public void retain(Map<String, List<RequirementNodeAssessment>> assessments) {
        assessments.forEach(this::retainNode);
    }

    private void retainNode(String code, List<RequirementNodeAssessment> claims) {
        var previous = entries.get(code);
        if (previous != null && previous.claims().equals(claims)) return;

        // Subtract the singleton's two braces and reserve one comma for each entry.
        int entryBytes = JSON.writeValueAsBytes(Map.of(code, claims)).length - 1;
        int otherBytes = bytes - (previous == null ? 0 : previous.bytes());
        if (entryBytes > MAX_JSON_BYTES - otherBytes) {
            entries.remove(code);
            bytes = otherBytes;
            truncated = true;
            return;
        }
        entries.put(code, new Entry(List.copyOf(claims), entryBytes));
        bytes = otherBytes + entryBytes;
    }

    public Map<String, List<RequirementNodeAssessment>> assessments() {
        var result = new LinkedHashMap<String, List<RequirementNodeAssessment>>();
        entries.forEach((code, entry) -> result.put(code, entry.claims()));
        return java.util.Collections.unmodifiableMap(result);
    }

    public boolean truncated() { return truncated; }
}
