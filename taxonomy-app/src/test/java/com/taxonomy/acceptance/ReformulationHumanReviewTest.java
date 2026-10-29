package com.taxonomy.acceptance;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;

/** Existing opt-in Maven comparison profile, without Spring or a provider invocation. */
@Tag("real-llm")
@EnabledIfSystemProperty(named = "reformulation.review.run", matches = ".+")
class ReformulationHumanReviewTest {
    @Test void validateAndRetainTheHumanReviewOfExactlyOneRecordedRun() throws Exception {
        var json = JsonMapper.builder().build();
        try (var stream = getClass().getResourceAsStream("/scenarios/reformulation-model-comparison.json")) {
            ReformulationComparisonEvidence.validateReviews(Path.of(System.getProperty("reformulation.review.run")).toAbsolutePath(),
                    json, json.readTree(stream));
        }
    }
}
