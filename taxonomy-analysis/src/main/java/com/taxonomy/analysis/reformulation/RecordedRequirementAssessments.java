package com.taxonomy.analysis.reformulation;

import com.taxonomy.dto.RequirementNodeAssessment;
import com.taxonomy.reformulation.ReformulationBaseline;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selected interpretations from the frozen analysis; never a source of newly imposed obligations. */
final class RecordedRequirementAssessments {
    static final String INSTRUCTION = "\nnodeAssessments are recorded interpretations of the exact original requirement, "
            + "not human approval, not proof of architecture edges and not newly imposed requirements. Preserve the "
            + "contribution type, evidence status, original quotes, conditions, necessity, scope and open questions. "
            + "Keep existing-environment context separate from requested delivery; do not promote options or alternatives "
            + "to mandatory scope. Re-evaluate only in a new analysis after explicit adoption of changed requirement text.";

    private RecordedRequirementAssessments() { }

    static Map<String,List<RequirementNodeAssessment>> select(ReformulationBaseline baseline,
            Set<String> selected, ObjectMapper json) {
        var recorded = json.readTree(baseline.snapshotPayload()).path("nodeAssessments");
        if (recorded.isMissingNode() || recorded.isNull()) return Map.of();
        if (!recorded.isObject()) throw new IllegalArgumentException("Invalid frozen requirement assessments");
        var result = new LinkedHashMap<String,List<RequirementNodeAssessment>>();
        selected.stream().sorted().forEach(code -> {
            var claims = recorded.get(code);
            if (claims == null) return;
            var values = List.of(json.treeToValue(claims, RequirementNodeAssessment[].class));
            values.forEach(value -> value.requireOriginalSource(baseline.originalText()));
            if (!values.isEmpty()) result.put(code, values);
        });
        return RequirementNodeAssessment.immutableMap(result);
    }
}
