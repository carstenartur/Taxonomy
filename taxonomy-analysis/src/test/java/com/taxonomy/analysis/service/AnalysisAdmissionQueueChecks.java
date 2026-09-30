package com.taxonomy.analysis.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Dependency-free checks also executed as individual Maven/JUnit dynamic tests. */
public final class AnalysisAdmissionQueueChecks {
    private AnalysisAdmissionQueueChecks() { }

    @SuppressWarnings("try") // Lifecycle checks intentionally close reservations before scope exit.
    public static Map<String, Runnable> checks() {
        Map<String, Runnable> checks = new LinkedHashMap<>();
        checks.put("reservation is queued until a worker starts", () -> {
            var queue = queue(2, 4, 1, 3);
            try (var ticket = queue.reserve("alice")) {
                equal(AnalysisAdmissionQueue.State.QUEUED, ticket.state());
                equal(0, queue.running());
                equal(1, queue.queued());
                check(ticket.tryStart(), "first worker must start");
                equal(AnalysisAdmissionQueue.State.RUNNING, ticket.state());
                equal(1, queue.running());
                equal(0, queue.queued());
            }
            equal(0, queue.running());
        });
        checks.put("running and waiting capacity are independent", () -> {
            var queue = queue(1, 2, 1, 2);
            try (var active = queue.reserve("alice")) {
                check(active.tryStart(), "active worker");
                try (var first = queue.reserve("bob"); var second = queue.reserve("carol")) {
                    check(!first.tryStart() && !second.tryStart(), "no running capacity");
                    equal(1, queue.running());
                    equal(2, queue.queued());
                    capacity(AnalysisAdmissionQueue.Rejection.GLOBAL_QUEUE_FULL,
                            () -> queue.reserve("dora"));
                }
            }
        });
        checks.put("queued cancellation immediately frees a waiting slot", () -> {
            var queue = queue(1, 1, 1, 1);
            var cancelled = queue.reserve("alice");
            cancelled.close();
            cancelled.close();
            equal(AnalysisAdmissionQueue.State.CLOSED, cancelled.state());
            check(!cancelled.tryStart(), "closed work must not run");
            try (var replacement = queue.reserve("bob")) {
                check(replacement.tryStart(), "replacement must start");
            }
        });
        checks.put("one owner cannot take every active slot", () -> {
            var queue = queue(2, 4, 1, 3);
            try (var first = queue.reserve("alice"); var second = queue.reserve("alice");
                 var other = queue.reserve("bob")) {
                check(first.tryStart(), "first owner starts");
                check(!second.tryStart(), "per-owner active limit");
                check(other.tryStart(), "different owner must use free capacity");
                equal(2, queue.running());
            }
        });
        checks.put("per-owner backlog does not fill the global queue", () -> {
            var queue = queue(2, 5, 1, 1);
            try (var alice = queue.reserve("alice")) {
                capacity(AnalysisAdmissionQueue.Rejection.OWNER_QUEUE_FULL,
                        () -> queue.reserve("alice"));
                try (var bob = queue.reserve("bob")) {
                    equal(2, queue.queued());
                }
            }
        });
        checks.put("ready owners get round-robin turns", () -> {
            var queue = queue(1, 8, 1, 4);
            try (var active = queue.reserve("alice"); var a1 = queue.reserve("alice");
                 var a2 = queue.reserve("alice"); var b1 = queue.reserve("bob");
                 var c1 = queue.reserve("carol")) {
                check(active.tryStart(), "initial owner");
                check(!a1.tryStart() && !a2.tryStart() && !b1.tryStart() && !c1.tryStart(), "queued");
                active.close();
                check(!a1.tryStart(), "alice must yield to another ready owner");
                check(b1.tryStart(), "bob's turn");
                b1.close();
                check(!a1.tryStart(), "carol must not starve behind alice's backlog");
                check(c1.tryStart(), "carol's turn");
                c1.close();
                check(a1.tryStart(), "alice resumes");
                a1.close();
                check(a2.tryStart(), "FIFO within owner");
            }
        });
        checks.put("an unstarted executor reservation does not block a ready worker", () -> {
            var queue = queue(1, 3, 1, 2);
            try (var unready = queue.reserve("alice"); var ready = queue.reserve("bob")) {
                check(ready.tryStart(), "ready worker must not wait for another executor");
                equal(AnalysisAdmissionQueue.State.QUEUED, unready.state());
            }
        });
        checks.put("cleanup removes owner state without retaining identities", () -> {
            var queue = queue(2, 4, 1, 2);
            for (int i = 0; i < 1000; i++) {
                try (var ticket = queue.reserve("owner-" + i)) {
                    check(ticket.tryStart(), "worker starts");
                }
            }
            equal(0, queue.running());
            equal(0, queue.queued());
            equal(0, queue.ownerCount());
        });
        checks.put("active cleanup is idempotent", () -> {
            var queue = queue(1, 2, 1, 2);
            var ticket = queue.reserve("alice");
            check(ticket.tryStart(), "start");
            ticket.close();
            ticket.close();
            equal(0, queue.running());
            try (var next = queue.reserve("bob")) {
                check(next.tryStart(), "no leaked permit");
            }
        });
        checks.put("invalid policies fail before accepting work", () -> {
            invalid(() -> queue(0, 1, 1, 1));
            invalid(() -> queue(1, 0, 1, 1));
            invalid(() -> queue(1, 1, 2, 1));
            invalid(() -> queue(1, 1, 1, 2));
            invalid(() -> queue(65, 1, 1, 1));
            invalid(() -> queue(1, 10001, 1, 1));
            invalid(() -> queue(1, 1, 1, 1).reserve(" "));
        });
        return checks;
    }

    private static AnalysisAdmissionQueue queue(int running, int queued, int ownerRunning, int ownerQueued) {
        return new AnalysisAdmissionQueue(new AnalysisAdmissionQueue.Limits(running, queued, ownerRunning, ownerQueued));
    }
    private static void capacity(AnalysisAdmissionQueue.Rejection expected, Runnable operation) {
        try { operation.run(); }
        catch (AnalysisAdmissionQueue.CapacityException failure) { equal(expected, failure.reason()); return; }
        throw new AssertionError("Expected capacity rejection: " + expected);
    }
    private static void invalid(Runnable operation) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid policy/owner");
    }
    private static void equal(Object expected, Object actual) {
        check(java.util.Objects.equals(expected, actual), "expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        checks().forEach((name, test) -> { test.run(); System.out.println("PASS " + name); });
    }
}
