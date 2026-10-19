package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.dto.LlmCallDetail;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Analysis-owned diagnostics. Every admission commits independently before transport is allowed to run. */
public final class ClusterAnalysisDiagnostics {
    // One operation can spend up to 10,000 relation evaluations plus its node
    // assessments. Diagnostics must accommodate that funded execution while
    // retaining independent record, per-call retry and text bounds.
    public static final int MAX_CALLS = 16_384, MAX_ATTEMPTS = 65_536, MAX_ATTEMPTS_PER_CALL = 32, MAX_TEXT = 8192;
    private static final LlmTransportMeter.Usage UNKNOWN = new LlmTransportMeter.Usage(null, null, null, null, null, false);
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ClusterAnalysisStore store;

    ClusterAnalysisDiagnostics(EntityManager em, PlatformTransactionManager transactions, ObjectMapper json, ClusterAnalysisStore store) {
        this.em = Objects.requireNonNull(em); this.json = Objects.requireNonNull(json); this.store = Objects.requireNonNull(store);
        this.tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Session session(AnalysisTaskMessage task) {
        return tx.execute(status -> { store.diagnosticAuthority(task, null); int delivery = store.diagnosticDelivery(task);
            store.diagnosticAuthority(task, delivery); return new Session(task, delivery); });
    }
    public Session session(AnalysisTaskMessage task, int deliveryAttempt) {
        return tx.execute(status -> { store.diagnosticAuthority(task, deliveryAttempt); return new Session(task, deliveryAttempt); });
    }

    public record Summaries(List<AnalysisProgressRegistry.CallView> calls, long omitted) {
        public Summaries { calls = List.copyOf(calls); }
    }
    public Summaries summaries(AnalysisOperationContext context) { return summaries(context, Long.MAX_VALUE); }
    /** Scalar projection avoids loading prompt/response columns into ordinary progress or SSE. */
    public Summaries summaries(AnalysisOperationContext context, long revision) {
        return tx.execute(status -> {
            store.command(context);
            var rows = em.createQuery("select c.callNumber,c.provider,c.node,c.state,c.startedAt,c.durationMillis from ClusterAnalysisCall c where c.operationId=:run and c.eventRevision<=:revision order by c.callNumber desc", Object[].class)
                    .setParameter("run", context.operationId()).setParameter("revision", revision).setMaxResults(MAX_CALLS).getResultList();
            var calls = rows.stream().limit(32).map(r -> new AnalysisProgressRegistry.CallView((Long) r[0], (String) r[1], (String) r[2], (String) r[3], (Long) r[4], (Long) r[5])).toList().reversed();
            return new Summaries(calls, Math.max(0, rows.size() - calls.size()));
        });
    }
    /** Caller must first authorize owner/workspace through ClusterAnalysisObservation. */
    public AnalysisProgressRegistry.CallDetail detail(AnalysisOperationContext context, long callNumber) {
        return tx.execute(status -> {
            store.command(context);
            var call = em.find(ClusterAnalysisCall.class, callKey(context.operationId(), callNumber));
            if (call == null || !call.operationId.equals(context.operationId()) || call.callNumber != callNumber
                    || !store.diagnosticTask(context, call.taskId)) throw notFound();
            var attempts = em.createQuery("select a from ClusterAnalysisAttempt a where a.operationId=:run and a.callId=:call order by a.startedAt,a.retryIndex,a.id", ClusterAnalysisAttempt.class)
                    .setParameter("run", context.operationId()).setParameter("call", call.id).setMaxResults(MAX_ATTEMPTS_PER_CALL).getResultList()
                    .stream().map(this::view).toList();
            return new AnalysisProgressRegistry.CallDetail(Objects.toString(call.prompt, ""), Objects.toString(call.response, ""),
                    call.promptLength > length(call.prompt) || call.responseLength > length(call.response), Objects.toString(call.error, ""),
                    call.promptLength, call.responseLength, attempts, call.taskId, call.executionId, call.deliveryAttempt);
        });
    }
    private AnalysisProgressRegistry.TransportAttempt view(ClusterAnalysisAttempt attempt) {
        var result = attempt.observationJson == null ? null : json.readValue(attempt.observationJson, LlmTransportMeter.Observation.class);
        return new AnalysisProgressRegistry.TransportAttempt(attempt.id, attempt.invocationId, attempt.provider, attempt.source, attempt.retryIndex,
                result == null ? "STARTED" : "COMPLETED", attempt.startedAt, result == null ? 0 : result.durationMillis(),
                result == null ? null : result.statusCode(), result == null ? null : result.outcome().name(), result == null ? UNKNOWN : result.usage());
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis call not found"); }

    /** Thread-confined worker binding; no prompts or active state are shared with another request. */
    public final class Session implements AnalysisRunControl.Observer, LlmTransportMeter.Journal {
        private final AnalysisTaskMessage task;
        private final int delivery;
        private final String execution = UUID.randomUUID().toString();
        private final Deque<Long> active = new ArrayDeque<>();
        private int ordinal;
        private final Set<String> catalogue;
        private Session(AnalysisTaskMessage task, int delivery) {
            this.task = task; this.delivery = delivery;
            var work = store.diagnosticWork(task);
            var previous = ClusterAnalysisProgress.read(work.progressJson, json);
            if (previous != null && previous.delivery() >= delivery)
                throw new IllegalStateException("Worker progress is already owned by this delivery");
            var root = store.progressRoot(task);
            catalogue = root == null ? Set.of() : root.nodes().stream().map(com.taxonomy.catalog.snapshot.RootCatalogueSnapshot.Node::code)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            var scores = previous == null ? Map.<String, Double>of() : previous.scores();
            work.progressJson = json.writeValueAsString(new ClusterAnalysisProgress.Worker(execution, delivery,
                    store.diagnosticAuthority(task, delivery).revision, null, null, scores, null));
        }
        @Override public String operationId() { return task.envelope().operationId(); }
        @Override public void phase(String phase, String node) {
            progress(current -> current.phase(Objects.requireNonNull(phase), node));
        }
        @Override public void assessment(LlmCallDetail detail) { assessment(detail, true); }
        private void assessment(LlmCallDetail detail, boolean requireCurrent) {
            Objects.requireNonNull(detail);
            if (task.taskType() != AnalysisTaskType.SUBTAXONOMY_ANALYSIS) return;
            progress(current -> {
                if (detail.getError() != null && !detail.getError().isBlank() || detail.getScores() == null) return current;
                var scores = new LinkedHashMap<>(current.scores());
                detail.getScores().forEach((code, value) -> {
                    if (catalogue.contains(code) && value != null && Double.isFinite(value) && value >= 0 && value <= 100)
                        scores.put(code, value);
                });
                return current.scores(scores);
            }, requireCurrent);
        }
        @Override public void relations(com.taxonomy.dto.RelationSearchProgress value) {
            if (task.taskType() != AnalysisTaskType.RELATION_ANALYSIS)
                throw new IllegalStateException("Relation progress requires a relation task");
            progress(current -> {
                ClusterAnalysisProgress.validateRelations(value);
                var work = store.diagnosticWork(task);
                if (work.inputJson != null) {
                    var grant = json.readValue(work.inputJson, com.taxonomy.analysis.relations.RelationSearchDistribution.Work.class);
                    if (value.maxCalls() != grant.maxCalls() || value.calls() > grant.maxCalls()
                            || value.taxonomies().stream().anyMatch(root -> !root.root().equals(grant.targetRoot().code())))
                        throw new IllegalArgumentException("Relation progress differs from its admitted grant");
                    var plan = store.relationPlan(ClusterAnalysisStore.context(task.envelope()));
                    var item = plan.items().get(grant.ordinal());
                    var source = plan.sources().get(item.sourceIndex()).node().id();
                    if (value.current() != null && (!source.equals(value.current().sourceId())
                            || !grant.targetRoot().code().equals(value.current().targetRoot())
                            || !item.type().equals(value.current().type()) || item.direction() != value.current().direction()))
                        throw new IllegalArgumentException("Foreign current relation search");
                }
                return current.relations(value);
            });
        }
        private void progress(java.util.function.UnaryOperator<ClusterAnalysisProgress.Worker> update) { progress(update, true); }
        private void progress(java.util.function.UnaryOperator<ClusterAnalysisProgress.Worker> update, boolean requireCurrent) {
            tx.executeWithoutResult(status -> {
                var run = store.diagnosticAuthority(task, requireCurrent ? delivery : null); var work = store.diagnosticWork(task);
                var previous = ClusterAnalysisProgress.read(work.progressJson, json);
                if (run.state.terminal() || !"RUNNING".equals(work.state) || work.attempts != delivery
                        || work.resultJson != null || work.failureReason != null || previous == null
                        || !execution.equals(previous.execution()) || delivery != previous.delivery()) {
                    if (!requireCurrent) return; // Late diagnostic responses remain historical evidence only.
                    throw new IllegalStateException("Worker progress delivery was superseded");
                }
                var next = Objects.requireNonNull(update.apply(previous));
                if (previous.equals(next)) return;
                String encoded = json.writeValueAsString(next.at(run.revision + 1));
                if (encoded.length() > ClusterAnalysisProgress.MAX_JSON) throw new IllegalArgumentException("Worker progress exceeds operation bound");
                work.progressJson = encoded; store.diagnosticChanged(run); em.flush();
            });
        }
        @Override public long started(String provider, String node) {
            int logicalOrdinal = ++ordinal;
            long number = tx.execute(status -> {
                var run = store.diagnosticAuthority(task, delivery);
                long previous = em.createQuery("select coalesce(max(c.callNumber),0) from ClusterAnalysisCall c where c.operationId=:run", Long.class).setParameter("run", operationId()).getSingleResult();
                if (previous >= MAX_CALLS) throw new IllegalStateException("Analysis diagnostics call limit reached");
                var call = new ClusterAnalysisCall(); call.callNumber = previous + 1; call.id = callKey(operationId(), call.callNumber);
                call.operationId = operationId(); call.taskId = task.taskId().value(); call.executionId = execution;
                call.deliveryAttempt = delivery; call.logicalOrdinal = logicalOrdinal;
                call.provider = clip(Objects.requireNonNull(provider), 128); call.node = clip(node, 512); call.state = "STARTED"; call.startedAt = System.currentTimeMillis();
                store.diagnosticChanged(run); call.eventRevision = run.revision; em.persist(call); em.flush(); return call.callNumber;
            });
            active.push(number); return number;
        }
        @Override public void prepared(long callId, String prompt) {
            tx.executeWithoutResult(status -> {
                var run = store.diagnosticAuthority(task, null); var call = ownCall(callId);
                if (!"STARTED".equals(call.state)) return;
                call.prompt = clip(prompt, MAX_TEXT); call.promptLength = length(prompt);
                store.diagnosticChanged(run); call.eventRevision = run.revision; em.flush();
            });
        }
        @Override public void completed(long callId, LlmCallDetail detail, long durationMillis) {
            finish(callId, detail, durationMillis, detail.getError() == null || detail.getError().isBlank() ? "COMPLETED" : "FAILED", detail.getError());
            // Ordinary scoring reports parsed evidence here; checkpoint replay also
            // invokes assessment(). Frozen identities deduplicate both paths.
            assessment(detail, false);
        }
        @Override public void failed(long callId, String failure, long durationMillis) { finish(callId, null, durationMillis, "FAILED", failure); }
        @Override public void stopped(AnalysisStoppedException.Reason reason) {
            for (long call : List.copyOf(active)) finish(call, null, 0, "STOPPED", reason.name());
        }
        @Override public void stoppedAfterResponse(long callId, LlmCallDetail detail, long durationMillis, AnalysisStoppedException.Reason reason) {
            finish(callId, detail, durationMillis, "STOPPED", reason.name());
        }
        private void finish(long callId, LlmCallDetail detail, long duration, String state, String error) {
            if (duration < 0) throw new IllegalArgumentException("Invalid diagnostic duration");
            tx.executeWithoutResult(status -> {
                var run = store.diagnosticAuthority(task, null); var call = ownCall(callId);
                String prompt = detail == null || detail.getPrompt() == null ? call.prompt : clip(detail.getPrompt(), MAX_TEXT);
                String response = detail == null ? call.response : clip(detail.getRawResponse(), MAX_TEXT);
                int promptLength = detail == null || detail.getPrompt() == null ? call.promptLength : length(detail.getPrompt());
                int responseLength = detail == null ? call.responseLength : length(detail.getRawResponse());
                String failure = clip(error, 1024);
                if (!"STARTED".equals(call.state) && !("STOPPED".equals(call.state) && detail != null && call.response == null)) {
                    if (!call.state.equals(state) || !Objects.equals(call.prompt, prompt) || !Objects.equals(call.response, response)
                            || !Objects.equals(call.error, failure) || call.durationMillis != duration) throw new IllegalStateException("Conflicting logical call completion");
                    return;
                }
                call.state = "STOPPED".equals(call.state) ? "STOPPED" : state; call.prompt = prompt; call.response = response;
                call.promptLength = promptLength; call.responseLength = responseLength; call.error = failure; call.durationMillis = duration;
                store.diagnosticChanged(run); call.eventRevision = run.revision; em.flush();
            });
            active.remove(callId);
        }
        private ClusterAnalysisCall ownCall(long number) {
            var call = em.find(ClusterAnalysisCall.class, callKey(operationId(), number));
            if (call == null || !call.operationId.equals(operationId()) || !call.taskId.equals(task.taskId().value())
                    || !call.executionId.equals(execution) || call.deliveryAttempt != delivery) throw new IllegalStateException("Unexpected logical call identity");
            return call;
        }
        @Override public void started(LlmTransportMeter.Attempt attempt) {
            validate(attempt);
            tx.executeWithoutResult(status -> {
                var run = store.diagnosticAuthority(task, null);
                var existing = em.find(ClusterAnalysisAttempt.class, attempt.id());
                if (existing != null) {
                    var retained = em.find(ClusterAnalysisCall.class, existing.callId);
                    if (retained == null) throw new IllegalStateException("Missing logical call");
                    var call = ownCall(retained.callNumber); requireAttempt(existing, call, attempt); return;
                }
                if (active.isEmpty()) throw new IllegalStateException("Transport attempt has no logical call");
                store.diagnosticAuthority(task, delivery); var call = ownCall(active.peek());
                long total = em.createQuery("select count(a) from ClusterAnalysisAttempt a where a.operationId=:run", Long.class).setParameter("run", operationId()).getSingleResult();
                long count = em.createQuery("select count(a) from ClusterAnalysisAttempt a where a.callId=:call", Long.class).setParameter("call", call.id).getSingleResult();
                if (total >= MAX_ATTEMPTS || count >= MAX_ATTEMPTS_PER_CALL) throw new IllegalStateException("Analysis transport diagnostics limit reached");
                var row = new ClusterAnalysisAttempt(); row.id = attempt.id(); row.operationId = operationId(); row.callId = call.id;
                row.invocationId = attempt.invocationId(); row.provider = attempt.provider(); row.source = attempt.source().name(); row.retryIndex = attempt.retryIndex(); row.startedAt = System.currentTimeMillis();
                em.persist(row); store.diagnosticChanged(run); em.flush();
            });
        }
        @Override public void completed(LlmTransportMeter.Attempt attempt, LlmTransportMeter.Observation result) {
            validate(attempt); validateResult(attempt, result);
            tx.executeWithoutResult(status -> {
                var run = store.diagnosticAuthority(task, null); var row = em.find(ClusterAnalysisAttempt.class, attempt.id());
                if (row == null) throw new IllegalStateException("Missing durable transport admission");
                var call = em.find(ClusterAnalysisCall.class, row.callId);
                if (call == null) throw new IllegalStateException("Missing logical call");
                ownCall(call.callNumber); requireAttempt(row, call, attempt);
                String observation = json.writeValueAsString(result);
                if (row.observationJson != null) {
                    if (!json.readValue(row.observationJson, LlmTransportMeter.Observation.class).equals(result)) throw new IllegalStateException("Conflicting transport completion");
                    return;
                }
                row.observationJson = observation; store.diagnosticChanged(run); em.flush();
            });
        }
        private void requireAttempt(ClusterAnalysisAttempt row, ClusterAnalysisCall call, LlmTransportMeter.Attempt attempt) {
            if (!row.operationId.equals(operationId()) || !row.callId.equals(call.id) || !row.invocationId.equals(attempt.invocationId())
                    || !row.provider.equals(attempt.provider()) || !row.source.equals(attempt.source().name()) || row.retryIndex != attempt.retryIndex())
                throw new IllegalStateException("Unexpected transport attempt identity");
        }
    }

    static void validate(LlmTransportMeter.Attempt attempt) {
        Objects.requireNonNull(attempt); uuid(attempt.id()); uuid(attempt.invocationId());
        if (attempt.provider() == null || attempt.provider().isBlank() || attempt.provider().length() > 128 || attempt.source() == null
                || attempt.retryIndex() < 0 || attempt.retryIndex() >= MAX_ATTEMPTS_PER_CALL
                || attempt.source() == LlmTransportMeter.Source.RECORDING_REPLAY && attempt.retryIndex() != 0) throw new IllegalArgumentException("Invalid transport admission");
    }
    static void validateResult(LlmTransportMeter.Attempt attempt, LlmTransportMeter.Observation result) {
        Objects.requireNonNull(result); var usage = Objects.requireNonNull(result.usage());
        if (!attempt.invocationId().equals(result.invocationId()) || !attempt.provider().equals(result.provider()) || attempt.source() != result.source()
                || attempt.retryIndex() != result.retryIndex() || result.outcome() == null || result.durationMillis() < 0
                || result.statusCode() != null && (result.statusCode() < 100 || result.statusCode() > 599)) throw new IllegalArgumentException("Invalid transport completion");
        for (Long count : Arrays.asList(usage.inputTokens(), usage.outputTokens(), usage.totalTokens(), usage.cachedInputTokens(), usage.reasoningTokens()))
            if (count != null && count < 0) throw new IllegalArgumentException("Invalid reported usage");
        if (result.source() == LlmTransportMeter.Source.RECORDING_REPLAY && (result.statusCode() != null || result.durationMillis() != 0
                || result.outcome() != LlmTransportMeter.Outcome.RESPONSE || !usage.equals(UNKNOWN))) throw new IllegalArgumentException("Replay cannot claim new usage");
    }
    static String callKey(String operationId, long callNumber) { return key(operationId + ":call:" + callNumber); }
    static String key(String value) { return java.util.HexFormat.of().formatHex(sha(value)); }
    private static byte[] sha(String value) { try { return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    static void uuid(String value) { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Invalid diagnostic identity"); }
    static int length(String value) { return value == null ? 0 : value.length(); }
    static String clip(String value, int max) { return value == null ? null : value.substring(0, Math.min(max, value.length())); }
}
