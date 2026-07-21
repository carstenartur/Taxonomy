package com.taxonomy.analysis.usecase;

import java.util.Map;

/** Input for generating a traceable explanation of one leaf-node match. */
public record JustifyLeafCommand(
        String nodeCode,
        String businessText,
        Map<String, Integer> scores,
        Map<String, String> reasons) {

    public JustifyLeafCommand {
        if (nodeCode == null || nodeCode.isBlank()) {
            throw new IllegalArgumentException("nodeCode must not be blank");
        }
        if (businessText == null || businessText.isBlank()) {
            throw new IllegalArgumentException("businessText must not be blank");
        }
        scores = scores == null ? Map.of() : Map.copyOf(scores);
        reasons = reasons == null ? Map.of() : Map.copyOf(reasons);
    }
}
