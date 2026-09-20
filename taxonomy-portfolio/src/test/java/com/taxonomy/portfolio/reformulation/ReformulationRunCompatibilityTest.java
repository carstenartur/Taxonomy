package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.reformulation.ReformulationDtos.Run;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Old persisted runs remain readable; new runs retain an immutable prompt snapshot. */
class ReformulationRunCompatibilityTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void readsLegacyPersistedRunWithoutReconciliationContext() {
        ObjectNode legacy = (ObjectNode) json.valueToTree(run(Map.of()));
        legacy.remove("reconcileContext");

        Run restored = json.readValue(legacy.toString(), Run.class);

        assertThat(restored).isEqualTo(run(Map.of()));
        assertThat(restored.reconcileContext()).isEmpty();
    }

    @Test
    void readsExplicitNullContextInPersistedRun() {
        ObjectNode legacy = (ObjectNode) json.valueToTree(run(Map.of()));
        legacy.putNull("reconcileContext");

        assertThat(json.readValue(legacy.toString(), Run.class)).isEqualTo(run(Map.of()));
    }

    @Test
    void normalizesNullConstructorContextToEmptySnapshot() {
        assertThat(run(null).reconcileContext()).isEmpty();
    }

    @Test
    void laterCallerMutationsCannotRewriteFrozenContext() {
        var supplied = new HashMap<>(Map.of("reconcilePrompt", "Frozen wording",
                "schema", "reconcile-response-v1"));
        Run frozen = run(supplied);

        supplied.put("reconcilePrompt", "Changed after enqueue");
        supplied.clear();

        assertThat(frozen.reconcileContext()).containsExactlyInAnyOrderEntriesOf(
                Map.of("reconcilePrompt", "Frozen wording", "schema", "reconcile-response-v1"));
    }

    @Test
    void exposedContextCannotBeMutated() {
        Run frozen = run(Map.of("reconcilePrompt", "Frozen wording"));

        assertThatThrownBy(() -> frozen.reconcileContext().put("reconcilePrompt", "Changed"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(frozen.reconcileContext()).containsEntry("reconcilePrompt", "Frozen wording");
    }

    @Test
    void persistenceRoundTripRetainsFrozenContextAndRunMetadata() {
        Run frozen = run(Map.of("reconcilePrompt", "Frozen wording",
                "schema", "reconcile-response-v1"));

        Run restored = json.readValue(json.writeValueAsString(frozen), Run.class);

        assertThat(restored).isEqualTo(frozen);
    }

    private static Run run(Map<String, String> context) {
        return new Run("run-1", "proposal-1", 3, "QUEUED", "TEST", "playback",
                "node-v1", "schema-v1", "Frozen node prompt", null, null, null,
                "architect", Instant.parse("2026-09-20T12:00:00Z"), context);
    }
}
