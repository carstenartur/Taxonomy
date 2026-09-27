package com.taxonomy.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** JDK-only executable checks; the CI JUnit wrapper invokes the same checks. */
public final class ProcessTestDiagnosticsChecks {
    private ProcessTestDiagnosticsChecks() { }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("process-diagnostics-checks-");
        checkLargeFailure(dir);
        checkMissingMarker(dir);
        checkMarkerAnywhere(dir);
        checkMissingLog(dir);
        checkLongUnicodeLine(dir);
        checkSmallLog(dir);
        System.out.println("ProcessTestDiagnostics: 6 checks passed");
    }

    static void checkLargeFailure(Path dir) throws Exception {
        String content = "EARLY_LOG_SHOULD_NOT_BE_DUPLICATED\n" + "ordinary startup output\n".repeat(15000)
                + "java.lang.IllegalStateException: FINAL_FAILURE\n";
        Path log = Files.writeString(dir.resolve("large.log"), content);
        AssertionError failure = expectFailure(() -> ProcessTestDiagnostics.assertCompleted(7, log, "DONE"));
        require(failure.getMessage().length() < 10000, "failure diagnostics must be bounded, got " + failure.getMessage().length());
        require(failure.getMessage().contains("exit code 7"), "missing exit code");
        require(failure.getMessage().contains(log.toAbsolutePath().toString()), "missing original log path");
        require(failure.getMessage().contains("FINAL_FAILURE"), "missing final failure");
        require(!failure.getMessage().contains("EARLY_LOG_SHOULD_NOT_BE_DUPLICATED"), "duplicated entire log");
        require(Files.readString(log).equals(content), "diagnostics changed the original log");
    }

    static void checkMissingMarker(Path dir) throws Exception {
        Path log = Files.writeString(dir.resolve("missing-marker.log"), "startup\n".repeat(20000) + "finished without sentinel\n");
        AssertionError failure = expectFailure(() -> ProcessTestDiagnostics.assertCompleted(0, log, "DONE"));
        require(failure.getMessage().length() < 10000, "missing-marker output must also be bounded");
        require(failure.getMessage().contains("DONE"), "missing expected completion marker");
        require(failure.getMessage().contains("finished without sentinel"), "missing log tail");
    }

    static void checkMarkerAnywhere(Path dir) throws Exception {
        Path log = Files.writeString(dir.resolve("marker.log"), "x".repeat(4094) + "DONE" + "y".repeat(20000));
        ProcessTestDiagnostics.assertCompleted(0, log, "DONE");
        AssertionError failure = expectFailure(() -> ProcessTestDiagnostics.assertCompleted(3, log, "DONE"));
        require(failure.getMessage().contains("exit code 3"), "completion marker must not override nonzero exit");
    }

    static void checkMissingLog(Path dir) throws Exception {
        Path missing = dir.resolve("absent.log");
        AssertionError exitFailure = expectFailure(() -> ProcessTestDiagnostics.assertCompleted(9, missing, "DONE"));
        require(exitFailure.getMessage().contains("exit code 9"), "unreadable log hid the exit code");
        require(exitFailure.getMessage().contains("unavailable"), "unreadable log was not identified");
        AssertionError markerFailure = expectFailure(() -> ProcessTestDiagnostics.assertCompleted(0, missing, "DONE"));
        require(markerFailure.getMessage().contains("DONE"), "missing log must not be mistaken for completed process");
    }

    static void checkLongUnicodeLine(Path dir) throws Exception {
        Path log = Files.writeString(dir.resolve("unicode.log"), "äöß😀".repeat(10000) + " FINAL_UNICODE_FAILURE", StandardCharsets.UTF_8);
        AssertionError failure = expectFailure(() -> ProcessTestDiagnostics.assertCompleted(1, log, "DONE"));
        require(failure.getMessage().length() < 10000, "a single long line bypassed truncation");
        require(failure.getMessage().contains("FINAL_UNICODE_FAILURE"), "lost end of Unicode log");
        require(!failure.getMessage().contains("\uFFFD"), "truncation split a UTF-8 character");
    }

    static void checkSmallLog(Path dir) throws Exception {
        Path log = Files.writeString(dir.resolve("small.log"), "Exception: short diagnostic\n");
        AssertionError failure = expectFailure(() -> ProcessTestDiagnostics.assertCompleted(2, log, "DONE"));
        require(failure.getMessage().contains("Exception: short diagnostic"), "lost small-log diagnostic");
        require(!failure.getMessage().contains("earlier log omitted"), "small log marked truncated");
    }

    private static AssertionError expectFailure(CheckedAction action) throws Exception {
        try { action.run(); }
        catch (AssertionError expected) { return expected; }
        throw new AssertionError("Expected the failed application to remain a test failure");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }
}
