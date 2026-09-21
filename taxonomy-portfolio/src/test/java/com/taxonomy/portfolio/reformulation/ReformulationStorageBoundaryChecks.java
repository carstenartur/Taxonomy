package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.service.PortfolioException;
import java.time.Instant;

/** Shared executable checks; no database or provider is needed to reject malformed input. */
final class ReformulationStorageBoundaryChecks {
    private static final String FINGERPRINT = "a".repeat(64);
    private ReformulationStorageBoundaryChecks() {}

    static void invalidIdentity() {
        var store = new ReformulationCheckpointStore(null, null);
        for (String kind : new String[] {null, "", "ADOPT", "node", "NODE/../REWORD"}) {
            rejected(() -> store.lookup(null, kind, FINGERPRINT), "Invalid reformulation checkpoint identity");
            rejected(() -> store.complete(null, "run", kind, FINGERPRINT, "{}"), "Invalid reformulation checkpoint identity");
        }
        for (String hash : new String[] {null, "", "a".repeat(63), "a".repeat(65), "g".repeat(64), "A".repeat(64)}) {
            rejected(() -> store.lookup(null, "NODE", hash), "Invalid reformulation checkpoint identity");
            rejected(() -> store.complete(null, "run", "NODE", hash, "{}"), "Invalid reformulation checkpoint identity");
        }
    }

    static void invalidResult() {
        var store = new ReformulationCheckpointStore(null, null);
        for (String payload : new String[] {null, "", " \n\t", "x".repeat(ReformulationCheckpointStore.MAX_RESULT_BYTES + 1),
                "€".repeat(ReformulationCheckpointStore.MAX_RESULT_BYTES / 3 + 1)}) {
            rejected(() -> store.complete(null, "run", "NODE", FINGERPRINT, payload), "CHECKPOINT_RESULT_TOO_LARGE_OR_EMPTY");
        }
    }

    static void firstCancellationSurvivesRepeatedAndDifferentActors() throws Exception {
        var run = new ReformulationRun("run", "proposal", "scope", "{}");
        Instant first = Instant.parse("2026-09-21T12:00:00Z");
        run.recordCancellation("original-actor", first);
        check("original-actor".equals(field(run, "cancelledBy")), "First cancellation actor was not recorded");
        check(first.equals(field(run, "cancelledAt")), "First cancellation instant was not recorded");
        run.recordCancellation("different-actor", first.plusSeconds(20));
        run.recordCancellation("original-actor", first.plusSeconds(40));
        check("original-actor".equals(field(run, "cancelledBy")), "Repeated cancellation rewrote the original actor");
        check(first.equals(field(run, "cancelledAt")), "Repeated cancellation rewrote the original instant");
    }

    private static Object field(ReformulationRun run, String name) throws Exception {
        // These fields are JPA state, not part of the public run DTO. Do not add production getters for a test.
        var field = ReformulationRun.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(run);
    }
    private static void rejected(Runnable action, String message) {
        try { action.run(); }
        catch (PortfolioException expected) {
            check(expected.getMessage().contains(message), "Wrong validation reason: " + expected.getMessage());
            return;
        }
        throw new AssertionError("Malformed checkpoint input was accepted");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        invalidIdentity(); invalidResult(); firstCancellationSurvivesRepeatedAndDifferentActors();
        System.out.println("REFORMULATION_STORAGE_BOUNDARIES_OK");
    }
}
