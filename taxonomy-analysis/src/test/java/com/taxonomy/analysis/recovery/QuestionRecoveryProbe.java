package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.analysis.service.AnalysisStoppedException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable contract shared by the JUnit wrapper and the offline JVM verification. */
public final class QuestionRecoveryProbe {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static LlmCallDetail answer(String code, Integer score, String error) {
        LlmCallDetail detail = new LlmCallDetail();
        detail.setScores(score == null ? Map.of() : Map.of(code, score));
        detail.setReasons(Map.of()); detail.setError(error); return detail;
    }
    static final class MemoryStore implements AnalysisCheckpointSession.Store {
        final Map<String, AnalysisCheckpointSession.Checkpoint> entries = new LinkedHashMap<>();
        boolean cancelled;
        public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) {
            var old = entries.get(q.key());
            if (old != null) {
                check(old.question().inputHash().equals(q.inputHash()), "changed input must not reuse an answer");
                return old;
            }
            var value = new AnalysisCheckpointSession.Checkpoint(q, "ATTEMPT", null);
            entries.put(q.key(), value); return value;
        }
        public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) {
            entries.put(q.key(), new AnalysisCheckpointSession.Checkpoint(q, state, detail));
        }
        public void checkActive() {
            if (cancelled) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
        }
    }
    public static void verify() {
        var store = new MemoryStore();
        var calls = new AtomicInteger();
        try (var session = new AnalysisCheckpointSession(store)) {
            var value = AnalysisCheckpointSession.evaluate("CATEGORY", "test", List.of("BP"), "same input", () -> {
                calls.incrementAndGet(); return answer("BP", 80, null);
            });
            check(value.getScores().get("BP") == 80, "valid evidence lost");
            // Returned maps are not allowed to mutate the persisted question checkpoint.
            value.getScores().put("BP", 1);
            var replay = AnalysisCheckpointSession.evaluate("CATEGORY", "test", List.of("BP"), "same input", () -> {
                throw new AssertionError("successful question contacted provider again");
            });
            check(replay.getScores().get("BP") == 80, "checkpoint aliased mutable result");
            try {
                AnalysisCheckpointSession.evaluate("CATEGORY", "test", List.of("IP"), "large input", () -> {
                    calls.incrementAndGet(); return answer("IP", 0, "prompt too large");
                });
                throw new AssertionError("failed question did not pause");
            } catch (AnalysisStoppedException paused) {
                check(paused.reason() == AnalysisStoppedException.Reason.AWAITING_DECISION, "wrong pause reason");
                check(paused.partialScores().isEmpty(), "error zero escaped as assessed evidence");
            }
            var failed = store.entries.values().stream().filter(e -> e.state().equals("FAILED")).findFirst().orElseThrow();
            store.entries.put(failed.question().key(), new AnalysisCheckpointSession.Checkpoint(failed.question(), "SKIPPED", failed.detail()));
            var unknown = AnalysisCheckpointSession.evaluate("CATEGORY", "test", List.of("IP"), "large input", () -> {
                throw new AssertionError("left-open question contacted provider");
            });
            check(unknown.getScores().isEmpty(), "unknown is not zero");
            check(unknown.getError() != null, "unknown must prevent false coverage-gap conclusions");
            store.entries.remove(failed.question().key());
            var retried = AnalysisCheckpointSession.evaluate("CATEGORY", "test", List.of("IP"), "large input", () -> {
                calls.incrementAndGet(); return answer("IP", 60, null);
            });
            check(retried.getScores().get("IP") == 60 && calls.get() == 3, "retry repeated successful work");
            try {
                AnalysisCheckpointSession.evaluate("CATEGORY", "test", List.of("CO", "CR"), "incomplete siblings", () -> answer("CO", 10, null));
                throw new AssertionError("incomplete sibling question accepted");
            } catch (AnalysisStoppedException expected) {
                check(expected.reason() == AnalysisStoppedException.Reason.AWAITING_DECISION, "missing keys must pause");
            }
            store.cancelled = true;
            try {
                AnalysisCheckpointSession.evaluate("CATEGORY", "test", List.of("UA"), "input", () -> {
                    throw new AssertionError("cancelled run contacted provider");
                });
                throw new AssertionError("cancellation not enforced");
            } catch (AnalysisStoppedException expected) {
                check(expected.reason() == AnalysisStoppedException.Reason.CANCELLED, "wrong cancel reason");
            }
        }
        check(!AnalysisCheckpointSession.active(), "thread-local recovery leaked");
    }
    public static void main(String[] args) { verify(); System.out.println("Question recovery contracts passed"); }
}
