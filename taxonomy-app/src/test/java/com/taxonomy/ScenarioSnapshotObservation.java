package com.taxonomy;

import com.taxonomy.acceptance.ScenarioPromptEvidenceQuality.Cycle;
import tools.jackson.databind.JsonNode;

/** First-read identity and progress used while the complete snapshot is durably checkpointed. */
record ScenarioSnapshotObservation(String id, String sha256, int assessedSources,
                                   int completedSearches, JsonNode recoveryState) {
    static ScenarioSnapshotObservation fromVerifiedCycle(Cycle cycle, JsonNode value) {
        return new ScenarioSnapshotObservation(cycle.snapshotId(), cycle.snapshotSha256(),
                value.at("/analysis/relationSearchReport/progress/assessedSources").asInt(),
                value.at("/analysis/relationSearchReport/progress/completedSearches").asInt(),
                value.at("/analysis/recovery/state"));
    }
}
