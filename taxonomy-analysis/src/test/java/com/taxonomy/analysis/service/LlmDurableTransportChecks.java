package com.taxonomy.analysis.service;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

/** The plain entry point and JUnit wrapper exercise the same production boundary. */
public final class LlmDurableTransportChecks {
    private LlmDurableTransportChecks() {}
    interface Callback { void accept(String method, Object[] values); }
    static AutoCloseable journal(Callback callback) throws Exception {
        final Class<?> contract;
        try { contract = Class.forName("com.taxonomy.analysis.service.LlmTransportMeter$Journal"); }
        catch (ClassNotFoundException absent) { throw new AssertionError("No durable pre-transport journal boundary", absent); }
        Object target = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (p, method, args) -> {
            callback.accept(method.getName(), args); return null;
        });
        return (AutoCloseable) LlmTransportMeter.class.getMethod("openJournal", contract).invoke(null, target);
    }
    static void beginCommitsBeforeTransportAndCompletesExactlyOnce() throws Exception {
        var sequence = new ArrayList<String>(); var attempts = new ArrayList<Object>();
        try (var ignored = journal((method, args) -> { sequence.add(method); attempts.add(args[0]); })) {
            var result = LlmTransportMeter.exchange(LlmTransportMeter.newInvocation(), "CUSTOM_OPENAI", 0,
                    new ObjectMapper(), () -> { sequence.add("http"); return ResponseEntity.ok("{}"); });
            check(result.getBody().equals("{}"), "Transport body was changed");
        }
        check(sequence.equals(List.of("started", "http", "completed")), "Journal order: " + sequence);
        check(attempts.size() == 2 && attempts.getFirst().equals(attempts.getLast()), "Completion lost its attempt identity");
    }
    static void failedBeginNeverSendsAndFailedCompletionNeverRetries() throws Exception {
        var calls = new AtomicInteger();
        try (var ignored = journal((method, args) -> { if (method.equals("started")) throw new IllegalStateException("unavailable"); })) {
            try {
                LlmTransportMeter.exchange(LlmTransportMeter.newInvocation(), "CUSTOM_OPENAI", 0,
                        new ObjectMapper(), () -> { calls.incrementAndGet(); return ResponseEntity.ok("{}"); });
                throw new AssertionError("Missing journal must prevent transport");
            } catch (RuntimeException expected) {
                check(expected.getClass().getSimpleName().equals("JournalStartException"), "Wrong admission failure");
            }
        }
        check(calls.get() == 0, "Network ran without durable admission");
        var events = new ArrayList<LlmTransportMeter.Observation>();
        try (var observer = LlmTransportMeter.open(events::add);
             var ignored = journal((method, args) -> { if (method.equals("completed")) throw new IllegalStateException("unavailable"); })) {
            LlmTransportMeter.exchange(LlmTransportMeter.newInvocation(), "CUSTOM_OPENAI", 0,
                    new ObjectMapper(), () -> { calls.incrementAndGet(); return ResponseEntity.ok("{}"); });
        }
        check(calls.get() == 1 && events.size() == 1, "Completion failure changed/repeated HTTP or hid its observation");
    }
    static void journalCaptureAndReplayDoNotLeak() throws Exception {
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var empty = LlmTransportMeter.capture(() -> { LlmTransportMeter.replay(LlmTransportMeter.newInvocation(), "GEMINI"); return 1; });
        try (var pool = Executors.newSingleThreadExecutor();
             var ignored = journal((method, args) -> events.add(method))) {
            var task = LlmTransportMeter.capture(() -> { LlmTransportMeter.replay(LlmTransportMeter.newInvocation(), "GEMINI"); return 1; });
            check(pool.submit(task::get).get() == 1, "Captured replay failed");
            empty.get();
            pool.submit(() -> LlmTransportMeter.replay(LlmTransportMeter.newInvocation(), "GEMINI")).get();
        }
        check(events.equals(List.of("started", "completed")), "Journal leaked into unrelated work: " + events);
    }
    public static void main(String[] args) throws Exception {
        beginCommitsBeforeTransportAndCompletesExactlyOnce();
        failedBeginNeverSendsAndFailedCompletionNeverRetries();
        journalCaptureAndReplayDoNotLeak();
        System.out.println("DURABLE_TRANSPORT_BOUNDARY_OK checks=3");
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
