package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.service.AnalysisStoppedException;
import com.taxonomy.analysis.service.RootAssessmentEvidence;
import com.taxonomy.dto.LlmCallDetail;
import java.util.*;
import java.util.function.Supplier;

/** Request-local adapter around the existing evaluator, not a second traversal implementation. */
public final class AnalysisCheckpointSession implements AutoCloseable {
    public record Question(String key, String inputHash, String provider, List<String> nodes, String prompt) {
        public Question { nodes = List.copyOf(nodes); }
    }
    public record Checkpoint(Question question, String state, LlmCallDetail detail, boolean chargeReplay) {
        /** Existing journals retain their original admission behavior for cached answers. */
        public Checkpoint(Question question, String state, LlmCallDetail detail) {
            this(question, state, detail, true);
        }
    }
    public interface Store {
        Checkpoint prepare(Question question);
        void finish(Question question, String state, LlmCallDetail detail);
        void checkActive();
        default void supersedeEvidence(String kind, String provider, java.util.function.Predicate<Question> covered) { }
        /** Older journal adapters still apply their original full-input predicate. */
        default void supersedeEvidence(String kind, String provider, Set<String> singletonCodes,
                java.util.function.Predicate<Question> covered) {
            supersedeEvidence(kind, provider, covered);
        }
        default com.taxonomy.dto.RelationSearchReport previousRelations() { return null; }
        default int sourceCohortSize() { return 0; }
        default com.taxonomy.analysis.relations.RequirementRelationSearch.BudgetPolicy relationBudget() { return null; }
        /** Cumulative admitted relation calls; accepted answer replay does not spend again. */
        default int relationCallsSpent() { return 0; }
        /** Called only inside a new relation completion, after its local/grant allowance succeeds. */
        default void beforeRelationCall(int maximum) { }
    }
    private static final ThreadLocal<AnalysisCheckpointSession> CURRENT = new ThreadLocal<>();
    private final AnalysisCheckpointSession previous;
    private final Store store;
    private boolean closed;
    public AnalysisCheckpointSession(Store store) {
        this.store = Objects.requireNonNull(store); previous = CURRENT.get(); CURRENT.set(this);
    }
    public static boolean active() { return CURRENT.get() != null; }
    public static com.taxonomy.dto.RelationSearchReport previousRelations() {
        var current = CURRENT.get();
        return current == null ? null : current.store.previousRelations();
    }
    public static int relationCallsSpent() {
        var current = CURRENT.get();
        return current == null ? 0 : current.store.relationCallsSpent();
    }
    public static int sourceCohortSize() {
        var current = CURRENT.get();
        return current == null ? 0 : current.store.sourceCohortSize();
    }
    public static com.taxonomy.analysis.relations.RequirementRelationSearch.BudgetPolicy relationBudget() {
        var current = CURRENT.get();
        return current == null ? null : current.store.relationBudget();
    }
    public static void beforeRelationCall(int maximum) {
        var current = CURRENT.get();
        if (current != null) current.store.beforeRelationCall(maximum);
    }

    /** Advances a slice's retained report while every journal and ownership callback stays authoritative. */
    public static AnalysisCheckpointSession withPreviousRelations(
            Supplier<com.taxonomy.dto.RelationSearchReport> previousRelations) {
        Objects.requireNonNull(previousRelations, "previousRelations");
        var owner = CURRENT.get();
        if (owner == null) throw new IllegalStateException("A retained relation scope requires an active checkpoint session");
        Store delegate = owner.store;
        return new AnalysisCheckpointSession(new Store() {
            public Checkpoint prepare(Question question) { return delegate.prepare(question); }
            public void finish(Question question, String state, LlmCallDetail detail) { delegate.finish(question, state, detail); }
            public void checkActive() { delegate.checkActive(); }
            public void supersedeEvidence(String kind, String provider, java.util.function.Predicate<Question> covered) {
                delegate.supersedeEvidence(kind, provider, covered);
            }
            public void supersedeEvidence(String kind, String provider, Set<String> singletonCodes,
                    java.util.function.Predicate<Question> covered) {
                delegate.supersedeEvidence(kind, provider, singletonCodes, covered);
            }
            public com.taxonomy.dto.RelationSearchReport previousRelations() { return previousRelations.get(); }
            public int sourceCohortSize() { return delegate.sourceCohortSize(); }
            public com.taxonomy.analysis.relations.RequirementRelationSearch.BudgetPolicy relationBudget() { return delegate.relationBudget(); }
            public int relationCallsSpent() { return delegate.relationCallsSpent(); }
            public void beforeRelationCall(int maximum) { delegate.beforeRelationCall(maximum); }
        });
    }
    /** No provider request was made; the existing question remains ready for explicit continuation. */
    public static final class DeferredException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public DeferredException(String reason) { super(reason); }
    }
    public static boolean isRelationQuestion(String key) { return key != null && key.startsWith("R"); }

    public static void supersedeEvidence(String kind, String provider, java.util.function.Predicate<Question> covered) {
        var current = CURRENT.get();
        if (current != null) current.store.supersedeEvidence(kind, provider, covered);
    }

    public static void supersedeEvidence(String kind, String provider, Set<String> singletonCodes,
            java.util.function.Predicate<Question> covered) {
        var current = CURRENT.get();
        if (current != null) current.store.supersedeEvidence(kind, provider, Set.copyOf(singletonCodes), covered);
    }

    static Question evidenceQuestion(String kind, String provider, List<String> nodes, String input) {
        // 252-bit namespace-separated identity fits the existing 64-character schema.
        String key = "R" + digest(kind, provider, String.join("\n", nodes), input).substring(1);
        return new Question(key, digest(provider, input), provider, nodes, input);
    }

    /** Relation evidence uses the same scoped journal without fabricating node scores. */
    public static LlmCallDetail evaluateEvidence(String kind, String provider, List<String> nodes,
                                                String input, Supplier<LlmCallDetail> operation) {
        return evaluateEvidence(kind, provider, nodes, input, operation, () -> { });
    }

    /** Prior-operation evidence is free; same-delivery replay retains its original grant debit. */
    public static LlmCallDetail evaluateEvidence(String kind, String provider, List<String> nodes,
                                                String input, Supplier<LlmCallDetail> operation, Runnable admission) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(admission, "admission");
        var current = CURRENT.get();
        if (current == null) { admission.run(); return operation.get(); }
        current.store.checkActive();
        Question question = evidenceQuestion(kind, provider, nodes, input);
        Checkpoint saved = current.store.prepare(question);
        question = saved.question();
        if (saved.chargeReplay() && Set.of("SUCCESS", "CLARIFICATION", "DECOMPOSED", "UNASSESSED", "SKIPPED").contains(saved.state()))
            admission.run();
        if (Set.of("SUCCESS", "CLARIFICATION", "DECOMPOSED").contains(saved.state())) return copy(saved.detail());
        if ("UNASSESSED".equals(saved.state())) return unassessed(saved.detail());
        if ("SKIPPED".equals(saved.state())) {
            var skipped = new LlmCallDetail(); skipped.setScores(Map.of()); skipped.setProvider(provider);
            skipped.setError("RELATION_LEFT_OPEN"); return skipped;
        }
        if (!"ATTEMPT".equals(saved.state())) throw paused();
        LlmCallDetail result;
        try { admission.run(); result = Objects.requireNonNull(operation.get()); }
        catch (DeferredException deferred) {
            var ready = new LlmCallDetail(); ready.setProvider(provider); ready.setScores(Map.of());
            ready.setError(deferred.getMessage()); current.store.finish(question, "READY", ready);
            throw deferred;
        } catch (AnalysisStoppedException stopped) { throw stopped; }
        catch (RuntimeException failed) {
            com.taxonomy.analysis.service.AnalysisExecutionFailures.rethrow(failed);
            result = new LlmCallDetail(); result.setProvider(provider); result.setPrompt(input);
            result.setScores(Map.of()); result.setError("RELATION_FAILURE: " + failed.getClass().getSimpleName());
        }
        if (result.getError() != null && !result.getError().isBlank()
                || result.getRawResponse() == null || result.getRawResponse().isBlank()) {
            // A valid answer with a question may contain useful sibling decisions.
            // Preserve it for strict protocol replay, without marking it a success.
            boolean clarification = result.getError() != null && result.getError().startsWith("RELATION_UNRESOLVED:")
                    && result.getRawResponse() != null && !result.getRawResponse().isBlank();
            // A decomposed response is retained verbatim. Independently keyed corrective
            // questions own the failed entries; replay reconstructs the same validated siblings.
            boolean decomposed = result.getError() != null && result.getError().startsWith("RELATION_DECOMPOSED:");
            current.store.finish(question, decomposed ? "DECOMPOSED" : clarification ? "CLARIFICATION" : "UNASSESSED", copy(result));
            current.store.checkActive(); // Storage/ownership failures remain blocking.
            return clarification ? copy(result) : unassessed(result);
        }
        current.store.finish(question, "SUCCESS", copy(result));
        current.store.checkActive();
        return copy(result);
    }
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
        Question question = new Question(digest(com.taxonomy.dto.AnalysisPercentages.POLICY, kind, String.join("\n", offered)),
                digest(com.taxonomy.dto.AnalysisPercentages.POLICY, provider, input), provider, offered, input);
        Checkpoint checkpoint = current.store.prepare(question);
        question = checkpoint.question();
        if ("SUCCESS".equals(checkpoint.state())) return copy(checkpoint.detail());
        if ("CLARIFICATION".equals(checkpoint.state())) {
            return rootClarification(kind, offered, checkpoint.detail())
                    ? copy(checkpoint.detail()) : unassessed(checkpoint.detail());
        }
        if ("UNASSESSED".equals(checkpoint.state())) return unassessed(checkpoint.detail());
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
            if (rootClarification(kind, offered, completed)) {
                current.store.finish(question, "CLARIFICATION", copy(completed));
            } else if (completed != null && (completed.getError() == null || completed.getError().isBlank())
                    && completed.getScores() != null
                    && completed.getScores().keySet().equals(new HashSet<>(offered))) {
                current.store.finish(question, "SUCCESS", copy(completed));
            }
            throw stopped;
        } catch (RuntimeException failure) {
            com.taxonomy.analysis.service.AnalysisExecutionFailures.rethrow(failure);
            result = new LlmCallDetail();
            result.setProvider(provider); result.setPrompt(input); result.setScores(Map.of());
            result.setError(failure.getClass().getSimpleName() + ": "
                    + Objects.toString(failure.getMessage(), "Provider call failed"));
        }
        if (rootClarification(kind, offered, result)) {
            current.store.finish(question, "CLARIFICATION", copy(result));
            try { current.store.checkActive(); }
            catch (AnalysisStoppedException stopped) { throw stopped.withCompletedCall(result); }
            return copy(result);
        }
        String problem = result.getError();
        Map<String, Double> scores = result.getScores();
        if (problem == null || problem.isBlank()) {
            if (scores == null || !scores.keySet().equals(new HashSet<>(offered))
                    || scores.values().stream().anyMatch(value -> !com.taxonomy.dto.AnalysisPercentages.valid(value))) {
                problem = "INCOMPLETE_ASSESSMENT: response must cover the complete question with valid scores";
            }
        }
        if (problem != null && !problem.isBlank()) {
            var evidence = copy(result); evidence.setError(problem);
            // Retain failed raw evidence for inspection, but never return its error zeros to traversal.
            current.store.finish(question, "UNASSESSED", evidence);
            current.store.checkActive();
            return unassessed(evidence);
        }
        current.store.finish(question, "SUCCESS", copy(result));
        try { current.store.checkActive(); }
        catch (AnalysisStoppedException stopped) { throw stopped.withPartial(result); }
        return copy(result);
    }

    private static boolean rootClarification(String kind, List<String> offered, LlmCallDetail detail) {
        return "ROOT_RELEVANCE".equals(kind) && RootAssessmentEvidence.isClarification(detail)
                && detail.getRootAssessments().keySet().equals(new HashSet<>(offered))
                && detail.getRawResponse() != null && !detail.getRawResponse().isBlank();
    }

    /** Keep raw diagnostics, but never expose error fallback scores to traversal. */
    private static LlmCallDetail unassessed(LlmCallDetail detail) {
        var result = detail == null ? new LlmCallDetail() : copy(detail);
        result.setScores(Map.of()); result.setReasons(Map.of()); result.setDiscrepancy(null);
        result.setRootAssessments(Map.of());
        if (result.getError() == null || result.getError().isBlank()) result.setError("UNASSESSED: no accepted assessment");
        return result;
    }

    private static AnalysisStoppedException paused() {
        return new AnalysisStoppedException(AnalysisStoppedException.Reason.AWAITING_DECISION);
    }
    public static String digest(String... values) {
        return AnalysisEvidenceFingerprint.digest(values);
    }
    public static LlmCallDetail copy(LlmCallDetail value) {
        var copy = new LlmCallDetail();
        copy.setScores(value.getScores() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value.getScores()));
        copy.setReasons(value.getReasons() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value.getReasons()));
        copy.setRootAssessments(value.getRootAssessments());
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
