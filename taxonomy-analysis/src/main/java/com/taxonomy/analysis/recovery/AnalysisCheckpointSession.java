package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.service.AnalysisStoppedException;
import com.taxonomy.dto.LlmCallDetail;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Supplier;

/** Request-local adapter around the existing evaluator, not a second traversal implementation. */
public final class AnalysisCheckpointSession implements AutoCloseable {
    public record Question(String key, String inputHash, String provider, List<String> nodes, String prompt) {
        public Question { nodes = List.copyOf(nodes); }
    }
    public record Checkpoint(Question question, String state, LlmCallDetail detail) { }
    public interface Store {
        Checkpoint prepare(Question question);
        void finish(Question question, String state, LlmCallDetail detail);
        void checkActive();
    }
    private static final ThreadLocal<AnalysisCheckpointSession> CURRENT = new ThreadLocal<>();
    private final AnalysisCheckpointSession previous;
    private final Store store;
    private boolean closed;
    public AnalysisCheckpointSession(Store store) {
        this.store = Objects.requireNonNull(store); previous = CURRENT.get(); CURRENT.set(this);
    }
    public static boolean active() { return CURRENT.get() != null; }
    public static void checkpoint() {
        var current = CURRENT.get();
        if (current != null) current.store.checkActive();
    }
    public static LlmCallDetail evaluate(String kind, String provider, List<String> nodes,
                                        String input, Supplier<LlmCallDetail> operation) {
        Objects.requireNonNull(operation, "operation");
        var current = CURRENT.get();
        if (current == null) return operation.get();
        current.store.checkActive();
        List<String> offered = List.copyOf(nodes);
        if (offered.isEmpty() || new HashSet<>(offered).size() != offered.size()
                || offered.stream().anyMatch(node -> node == null || node.isBlank())) {
            throw new IllegalArgumentException("A recovery question requires unique catalogue node identities");
        }
        Question question = new Question(digest(kind, String.join("\n", offered)),
                digest(provider, input), provider, offered, input);
        Checkpoint checkpoint = current.store.prepare(question);
        if ("SUCCESS".equals(checkpoint.state())) return copy(checkpoint.detail());
        if ("SKIPPED".equals(checkpoint.state())) {
            var unknown = new LlmCallDetail();
            unknown.setScores(Map.of()); unknown.setReasons(Map.of());
            unknown.setProvider(provider);
            unknown.setError("UNASSESSED: " + String.join(", ", offered));
            return unknown;
        }
        if (!"ATTEMPT".equals(checkpoint.state())) throw paused();
        LlmCallDetail result;
        try {
            result = Objects.requireNonNull(operation.get(), "assessment result");
        } catch (AnalysisStoppedException stopped) {
            // A response may have completed just before cancellation. Retain it exactly once,
            // but never turn an erroneous response's fallback zeros into completed evidence.
            var completed = stopped.completedCallEvidence();
            if (completed != null && (completed.getError() == null || completed.getError().isBlank())
                    && completed.getScores() != null
                    && completed.getScores().keySet().equals(new HashSet<>(offered))) {
                current.store.finish(question, "SUCCESS", copy(completed));
            }
            throw stopped;
        } catch (RuntimeException failure) {
            result = new LlmCallDetail();
            result.setProvider(provider); result.setPrompt(input); result.setScores(Map.of());
            result.setError(failure.getClass().getSimpleName() + ": "
                    + Objects.toString(failure.getMessage(), "Provider call failed"));
        }
        String problem = result.getError();
        Map<String, Integer> scores = result.getScores();
        if (problem == null || problem.isBlank()) {
            if (scores == null || !scores.keySet().equals(new HashSet<>(offered))
                    || scores.values().stream().anyMatch(value -> value == null || value < 0 || value > 100)) {
                problem = "INCOMPLETE_ASSESSMENT: response must cover the complete question with valid scores";
            }
        }
        if (problem != null && !problem.isBlank()) {
            var evidence = copy(result); evidence.setError(problem);
            // Retain failed raw evidence for inspection, but never return its error zeros to traversal.
            current.store.finish(question, "FAILED", evidence);
            throw paused();
        }
        current.store.finish(question, "SUCCESS", copy(result));
        try { current.store.checkActive(); }
        catch (AnalysisStoppedException stopped) { throw stopped.withPartial(result); }
        return copy(result);
    }
    private static AnalysisStoppedException paused() {
        return new AnalysisStoppedException(AnalysisStoppedException.Reason.AWAITING_DECISION);
    }
    public static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static LlmCallDetail copy(LlmCallDetail value) {
        var copy = new LlmCallDetail();
        copy.setScores(value.getScores() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value.getScores()));
        copy.setReasons(value.getReasons() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value.getReasons()));
        copy.setProvider(value.getProvider()); copy.setPrompt(value.getPrompt()); copy.setRawResponse(value.getRawResponse());
        copy.setError(value.getError()); copy.setDurationMs(value.getDurationMs()); copy.setDiscrepancy(value.getDiscrepancy());
        return copy;
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
