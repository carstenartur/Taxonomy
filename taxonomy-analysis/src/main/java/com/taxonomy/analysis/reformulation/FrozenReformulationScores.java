package com.taxonomy.analysis.reformulation;

import com.taxonomy.dto.AnalysisPercentages;
import com.taxonomy.reformulation.ReformulationBaseline;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads frozen assessment evidence without rounding small contributions out of the walk-up. */
final class FrozenReformulationScores {
    private FrozenReformulationScores() { }

    static Map<String, BigDecimal> read(ReformulationBaseline baseline, ObjectMapper json) {
        var payload = tree(json, baseline.snapshotPayload());
        if (!payload.isObject()) throw invalid("Expected a frozen analysis object");
        var raw = payload.get("rawScores");
        // Legacy/manual snapshots may serialize the optional rawScores field as null.
        // An explicitly supplied empty raw-score map remains authoritative.
        if (raw == null || raw.isNull()) raw = payload.get("scores");
        var scores = new LinkedHashMap<String, BigDecimal>();
        if (raw != null && !raw.isNull()) {
            if (!raw.isObject()) throw invalid("Expected a frozen score map");
            for (var entry : raw.properties()) {
                if (entry.getKey().isBlank()) throw invalid("Missing frozen score node code");
                // Null is unknown, not an assessment of zero and not a positive contribution.
                if (!entry.getValue().isNull()) scores.put(entry.getKey(), percentage(entry.getValue()));
            }
        }
        var mappings = tree(json, baseline.frozenContext().getOrDefault("elementMappings", "[]"));
        if (!mappings.isArray()) throw invalid("Expected frozen element mappings");
        for (var mapping : mappings) {
            if (!mapping.isObject()) throw invalid("Expected a frozen element mapping object");
            var direct = mapping.get("directScore");
            if (direct == null || direct.isNull()) continue;
            var code = mapping.get("nodeCode");
            if (code == null || !code.isString() || code.asText().isBlank())
                throw invalid("Missing frozen mapping node code");
            scores.put(code.asText(), percentage(direct));
        }
        return scores;
    }

    private static BigDecimal percentage(JsonNode value) {
        if (!value.isNumber()) throw invalid("Frozen assessment must be numeric");
        return canonical(value.decimalValue());
    }

    static BigDecimal canonical(BigDecimal value) {
        // Reuse the shared bounds/precision policy. Integral prompt metadata keeps
        // its historical spelling (40, not 40.0 or 4E+1); decimals remain decimals.
        return new BigDecimal(AnalysisPercentages.canonical(AnalysisPercentages.require(value)));
    }

    private static JsonNode tree(ObjectMapper json, String payload) {
        try {
            var value = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(payload);
            if (value == null) throw invalid("Missing frozen analysis evidence");
            return value;
        } catch (JacksonException invalidJson) {
            throw new IllegalArgumentException("Invalid frozen analysis evidence", invalidJson);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
