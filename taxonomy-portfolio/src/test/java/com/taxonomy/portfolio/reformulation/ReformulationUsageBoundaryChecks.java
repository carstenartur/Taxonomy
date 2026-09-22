package com.taxonomy.portfolio.reformulation;

import java.time.Instant;
import java.util.UUID;

/** Executable validation contracts shared by JUnit and the local runtime verification. */
public final class ReformulationUsageBoundaryChecks {
    private static final String ID = "11111111-aaaa-4bbb-8ccc-dddddddddddd";
    private ReformulationUsageBoundaryChecks() {}
    static void startsRejectMalformedIdentitiesAndSources() {
        for (String invalid : new String[]{null, "", "../run", ID.toUpperCase()}) {
            bad(() -> new ReformulationUsageService.Start(invalid, ID, "GEMINI", "HTTP", 0));
            bad(() -> new ReformulationUsageService.Start(ID, invalid, "GEMINI", "HTTP", 0));
        }
        for (String provider : new String[]{null, "", "http://private", "lower", "X".repeat(33)})
            bad(() -> new ReformulationUsageService.Start(ID, ID, provider, "HTTP", 0));
        for (String source : new String[]{null, "", "NOT_HTTP"})
            bad(() -> new ReformulationUsageService.Start(ID, ID, "GEMINI", source, 0));
        bad(() -> new ReformulationUsageService.Start(ID, ID, "GEMINI", "HTTP", -1));
        bad(() -> new ReformulationUsageService.Start(ID, ID, "GEMINI", "RECORDING_REPLAY", 1));
        new ReformulationUsageService.Start(ID, ID, "CUSTOM_OPENAI", "HTTP", 0);
        new ReformulationUsageService.Start(ID, ID, "GEMINI", "RECORDING_REPLAY", 0);
    }
    static void completionsKeepUnknownAndValidateStatusAndEveryTokenField() {
        bad(() -> result(200, null, 0, null)); bad(() -> result(200, "OTHER", 0, null));
        bad(() -> result(99, "RESPONSE", 0, null)); bad(() -> result(600, "RESPONSE", 0, null));
        bad(() -> result(200, "RESPONSE", -1, null));
        bad(() -> result(null, "HTTP_ERROR", 0, null)); bad(() -> result(200, "HTTP_ERROR", 0, null));
        bad(() -> result(200, "TRANSPORT_ERROR", 0, null)); bad(() -> result(429, "RESPONSE", 0, null));
        for (int index = 0; index < 5; index++) {
            Long[] tokens = new Long[5]; tokens[index] = -1L;
            bad(() -> new ReformulationUsageService.Completion(200, "RESPONSE", 0, tokens[0], tokens[1], tokens[2], tokens[3], tokens[4], false));
        }
        var zero = result(200, "RESPONSE", 0, 0L);
        check(zero.inputTokens().equals(0L) && zero.outputTokens() == null, "Unknown was converted to zero");
        check(result(200, "RESPONSE", 0, Long.MAX_VALUE).inputTokens() == Long.MAX_VALUE, "Large usage lost");
        result(null, "TRANSPORT_ERROR", 0, null); result(503, "HTTP_ERROR", 0, null);
    }
    static void storedAttemptRetainsOwnerAndExactResult() {
        String run = UUID.randomUUID().toString(), owner = UUID.randomUUID().toString();
        var session = new ReformulationUsageSession(run, "proposal", "scope", Instant.EPOCH, true);
        check(session.matches("proposal", "scope") && !session.matches("other", "scope")
                && !session.matches("proposal", "other"), "Usage session scope changed");
        check(session.fromFirstAttempt() && session.recordedSince().equals(Instant.EPOCH), "Recording origin lost");
        var start = new ReformulationUsageService.Start(ID, ID, "GEMINI", "HTTP", 0);
        var attempt = new ReformulationUsageAttempt(run, owner, 2, start, Instant.EPOCH);
        check(attempt.ownedBy(run, owner, 2) && !attempt.ownedBy("other", owner, 2)
                && !attempt.ownedBy(run, "other", 2) && !attempt.ownedBy(run, owner, 3), "Attempt ownership changed");
        check(attempt.matches(start) && attempt.result() == null, "New attempt is not pending");
        var completed = result(200, "RESPONSE", 12, 0L);
        attempt.complete(completed, Instant.EPOCH.plusSeconds(1));
        check(attempt.result().equals(completed), "Result did not round trip");
        check(!attempt.matches(new ReformulationUsageService.Start(UUID.randomUUID().toString(), ID, "GEMINI", "HTTP", 0)), "Changed ID accepted");
        check(!attempt.matches(new ReformulationUsageService.Start(ID, UUID.randomUUID().toString(), "GEMINI", "HTTP", 0)), "Changed invocation accepted");
        check(!attempt.matches(new ReformulationUsageService.Start(ID, ID, "CUSTOM_OPENAI", "HTTP", 0)), "Changed provider accepted");
        check(!attempt.matches(new ReformulationUsageService.Start(ID, ID, "GEMINI", "RECORDING_REPLAY", 0)), "Changed source accepted");
        check(!attempt.matches(new ReformulationUsageService.Start(ID, ID, "GEMINI", "HTTP", 1)), "Changed retry accepted");
    }
    private static ReformulationUsageService.Completion result(Integer code, String outcome, long duration, Long input) {
        return new ReformulationUsageService.Completion(code, outcome, duration, input, null, null, null, null, false);
    }
    private static void bad(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid usage accepted");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        startsRejectMalformedIdentitiesAndSources(); completionsKeepUnknownAndValidateStatusAndEveryTokenField(); storedAttemptRetainsOwnerAndExactResult();
        System.out.println("REFORMULATION_USAGE_BOUNDARIES_OK groups=3");
    }
}
