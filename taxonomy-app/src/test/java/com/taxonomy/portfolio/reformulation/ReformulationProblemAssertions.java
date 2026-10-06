package com.taxonomy.portfolio.reformulation;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/** Exact public problem contract: a request UUID may contain any numeric substring. */
final class ReformulationProblemAssertions {
    private ReformulationProblemAssertions() {
    }

    static void requireIdentityConflict(ObjectMapper mapper, String body, String requestUri) {
        var actual = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(body);
        var expected = mapper.valueToTree(Map.of(
                "detail", "Stored reformulation baseline does not match proposal identity",
                "instance", requestUri,
                "status", 409,
                "title", "Portfolio state conflict",
                "type", "urn:taxonomy:portfolio:conflict"));
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected only the generic baseline identity-conflict problem for " + requestUri);
        }
    }
}
