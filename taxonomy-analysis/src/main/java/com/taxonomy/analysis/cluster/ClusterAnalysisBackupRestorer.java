package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.backup.SourceRecordId;
import com.taxonomy.dto.*;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

import static com.taxonomy.analysis.cluster.ClusterAnalysisBackupRecords.*;

/**
 * Restores one already authorized/verified archive aggregate under caller-remapped identities.
 * This is an internal persistence API, not an archive upload or authorization endpoint.
 * It has no transport, dispatcher or provider dependency and never resumes external work.
 */
public final class ClusterAnalysisBackupRestorer {
    private static final String INTERRUPTED = "RESTORE_INTERRUPTED";
    private final EntityManager em;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final AnalysisMessageCodec codec = new AnalysisMessageCodec();

    public ClusterAnalysisBackupRestorer(EntityManager em, PlatformTransactionManager transactions, ObjectMapper json) {
        this.em = Objects.requireNonNull(em); this.transactions = new TransactionTemplate(transactions); this.json = Objects.requireNonNull(json);
    }

    public void restore(Archive archive, AnalysisOperationContext target, AnalyzeRequirementCommand command) {
        validate(archive, target, command);
        boolean interrupted = !ClusterAnalysisState.valueOf(archive.run().sourceState()).terminal();
        // Validate/aggregate committed evidence before writing anything. Missing work is explicit,
        // never a fabricated zero score or an exhausted relation search.
        String resultJson = interrupted ? json.writeValueAsString(interruptedResult(archive)) : archive.run().resultJson();
        transactions.executeWithoutResult(status -> {
            if (em.find(ClusterAnalysisRun.class, target.operationId()) != null) throw new IllegalStateException("Restore target operation already exists");
            long now = System.currentTimeMillis(); var source = archive.run();
            var run = new ClusterAnalysisRun(); run.id = target.operationId(); run.username = command.username();
            run.scopeKey = ClusterAnalysisStore.scopeKey(command.workspaceContext());
            run.projectId = target.requirement().projectId(); run.requirementId = target.requirement().requirementId();
            run.contextJson = json.writeValueAsString(target); run.commandJson = json.writeValueAsString(command);
            run.viewJson = source.viewJson(); run.resultJson = resultJson;
            run.relationPlanJson = source.relationPlanJson() == null ? null : json.writeValueAsString(remapPlan(read(source.relationPlanJson(), RelationSearchDistribution.Plan.class), target.operationId()));
            run.state = interrupted ? ClusterAnalysisState.CANCELLED : ClusterAnalysisState.valueOf(source.sourceState());
            run.totalRoots = source.totalRoots(); run.completedRoots = source.completedRoots();
            run.revision = source.revision(); run.createdAt = source.createdAt(); run.updatedAt = now; em.persist(run);
            for (var input : archive.inputs()) {
                var restored = new ClusterAnalysisInput(); restored.id = key(run.id + ":input:" + input.root());
                restored.operationId = run.id; restored.root = input.root(); restored.inputJson = input.inputJson(); em.persist(restored);
            }
            for (var work : archive.work()) {
                var task = (AnalysisTaskMessage) decode(work.messageJson()); var remapped = remapTask(task, source.context(), target);
                var restored = new ClusterAnalysisWork(); restored.id = key(remapped.taskId().value());
                restored.operationId = run.id; restored.taskId = remapped.taskId().value(); restored.taskType = work.taskType();
                restored.root = work.root(); restored.ordinal = work.ordinal(); restored.messageJson = encode(remapped);
                restored.inputJson = work.inputJson(); restored.resultJson = work.resultJson();
                if (task.taskType() == AnalysisTaskType.RELATION_ANALYSIS) {
                    if (work.inputJson() != null) {
                        restored.inputJson = json.writeValueAsString(remapGrant(read(work.inputJson(), RelationSearchDistribution.Work.class), run.id));
                        if (work.resultJson() != null) {
                            var effect = read(work.resultJson(), RelationSearchDistribution.WorkResult.class);
                            restored.resultJson = json.writeValueAsString(new RelationSearchDistribution.WorkResult(remapGrant(effect.work(), run.id), effect.result(), effect.calls(), effect.stopReason()));
                        }
                    } else if (work.resultJson() != null && source.relationPlanJson() != null) restored.resultJson = run.relationPlanJson;
                }
                boolean unfinished = Set.of("QUEUED", "RUNNING").contains(work.sourceState());
                restored.state = unfinished ? "STOPPED" : work.sourceState(); restored.settled = work.settled();
                restored.failureReason = interrupted && work.failureReason() == null && work.resultJson() == null
                        ? INTERRUPTED : work.failureReason();
                // Historical attempts remain in the archive DTO, never become a new live attempt.
                restored.attempts = 0; restored.startedAt = null; restored.finishedAt = unfinished ? Long.valueOf(now) : work.finishedAt(); em.persist(restored);
            }
            for (var event : archive.events()) {
                var original = (AnalysisProgressEvent) decode(event.eventJson());
                persistEvent(new AnalysisProgressEvent(remapEnvelope(original.envelope(), source.context(), target),
                        original.sequence(), original.phase(), original.completedTasks(), original.totalTasks()));
            }
            if (interrupted) {
                run.revision++;
                persistEvent(new AnalysisMessageFactory(target, Clock.systemUTC()).progress(run.revision,
                        AnalysisProgressPhase.OPERATION_STOPPED, null, run.completedRoots, run.totalRoots));
            }
            em.flush();
        });
    }

    private void validate(Archive archive, AnalysisOperationContext target, AnalyzeRequirementCommand command) {
        Objects.requireNonNull(archive); Objects.requireNonNull(target); Objects.requireNonNull(command);
        var run = Objects.requireNonNull(archive.run()); var source = Objects.requireNonNull(run.context());
        require(run.sourceId().equals(new SourceRecordId("analysis.cluster-run", source.operationId())), "Invalid archived operation reference");
        require(!source.operationId().equals(target.operationId()), "Restore must allocate a new operation identity");
        require(run.totalRoots() > 0 && run.totalRoots() <= 8 && run.completedRoots() >= 0 && run.completedRoots() <= run.totalRoots()
                && run.revision() > 0 && run.revision() < Long.MAX_VALUE && archive.work().size() <= 521 && archive.inputs().size() <= 8
                && archive.events().size() <= 100_000, "Invalid archived aggregate bounds");
        var sourceCommand = read(run.commandJson(), AnalyzeRequirementCommand.class);
        try {
            ClusterAnalysisBackupExport.validateCommand(source, run.principalScope(), sourceCommand);
            ClusterAnalysisBackupExport.validateCommand(target, command.username(), command);
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid restore authority"); }
        require(source.requirement().textSha256().equals(target.requirement().textSha256())
                && sourceCommand.businessText().equals(command.businessText()) && sourceCommand.analysisScope().equals(command.analysisScope())
                && sourceCommand.includeArchitectureView() == command.includeArchitectureView()
                && sourceCommand.maxArchitectureNodes() == command.maxArchitectureNodes() && Objects.equals(sourceCommand.provider(), command.provider()),
                "Restore cannot change the captured analysis request");
        var state = ClusterAnalysisState.valueOf(run.sourceState());
        if (state.terminal()) { require(run.resultJson() != null, "Terminal archived result is missing"); read(run.resultJson(), AnalysisResult.class); }
        var taskIds = new HashSet<String>(); var ordinals = new HashSet<Integer>(); var roots = new HashSet<String>();
        var inputRoots = new HashSet<String>(); var sourceIds = new HashSet<SourceRecordId>(); int completed = 0;
        long characters = bounded(run.commandJson()) + bounded(run.viewJson()) + bounded(run.resultJson()) + bounded(run.relationPlanJson());
        for (var input : archive.inputs()) {
            requireReference(input.sourceId(), "analysis.cluster-input", input.run(), run, sourceIds);
            require(TaxonomyShardRoot.DEFAULT_ROOTS.stream().map(TaxonomyShardRoot::code).toList().contains(input.root()) && inputRoots.add(input.root()), "Invalid frozen root reference");
            require(input.inputJson() != null && !input.inputJson().isBlank(), "Missing frozen root input"); characters += bounded(input.inputJson());
        }
        for (var work : archive.work()) {
            requireReference(work.sourceId(), "analysis.cluster-work", work.run(), run, sourceIds);
            require(taskIds.add(work.taskId()) && ordinals.add(work.ordinal()) && work.ordinal() >= 0 && work.attempts() >= 0, "Invalid archived task identity");
            require(Set.of("QUEUED", "RUNNING", "COMPLETED", "PARTIAL", "FAILED", "STOPPED").contains(work.sourceState()), "Invalid archived task state");
            var message = decode(work.messageJson());
            require(message instanceof AnalysisTaskMessage, "Expected archived task message"); var task = (AnalysisTaskMessage) message;
            require(ClusterAnalysisBackupExport.sameContext(task.envelope(), source) && task.taskId().value().equals(work.taskId())
                    && task.taskType().name().equals(work.taskType()) && Objects.equals(task.routingRoot() == null ? null : task.routingRoot().code(), work.root()), "Archived task authority mismatch");
            require(work.failureReason() == null || work.failureReason().matches("[A-Z_]{1,64}"), "Invalid archived failure code");
            require(work.failureReason() == null || work.resultJson() == null, "Conflicting archived task effect");
            if (task.taskType() == AnalysisTaskType.SUBTAXONOMY_ANALYSIS) {
                require(roots.add(work.root()), "Duplicate archived root work");
                if (work.settled()) completed++;
                if (work.resultJson() != null) read(work.resultJson(), AnalysisResult.class);
            }
            characters += bounded(work.messageJson()) + bounded(work.inputJson()) + bounded(work.resultJson());
        }
        require(roots.size() == run.totalRoots() && completed == run.completedRoots() && inputRoots.containsAll(roots), "Incomplete archived root closure");
        for (var work : archive.work()) if (decode(work.messageJson()) instanceof RelationAnalysisTask relation)
            require(taskIds.containsAll(relation.prerequisiteTasks().stream().map(AnalysisTaskId::value).toList()), "Missing archived relation prerequisite");
        if (run.relationPlanJson() != null) {
            var plan = read(run.relationPlanJson(), RelationSearchDistribution.Plan.class);
            require(plan.preparationId().equals(source.operationId() + ":relations") && plan.originalSha256().equals(source.requirement().textSha256())
                    && taskIds.containsAll(plan.sourceResultIds()), "Invalid archived relation preparation");
            for (var work : archive.work()) if (work.taskType().equals(AnalysisTaskType.RELATION_ANALYSIS.name())
                    && work.inputJson() == null && work.resultJson() != null)
                require(read(work.resultJson(), RelationSearchDistribution.Plan.class).equals(plan), "Archived preparation differs from relation plan");
            var effects = relationEffects(archive);
            RelationSearchDistribution.combine(plan, effects);
        } else scoreOnlyRelations(archive);
        long revision = 0;
        for (var event : archive.events()) {
            requireReference(event.sourceId(), "analysis.cluster-event", event.run(), run, sourceIds);
            var message = decode(event.eventJson());
            require(message instanceof AnalysisProgressEvent, "Expected archived progress event"); var progress = (AnalysisProgressEvent) message;
            require(event.revision() == ++revision && progress.sequence() == revision && ClusterAnalysisBackupExport.sameContext(progress.envelope(), source), "Invalid archived progress sequence");
            characters += bounded(event.eventJson());
        }
        require(revision == run.revision() && characters <= 64_000_000L, "Incomplete or oversized archived aggregate");
    }

    private AnalysisResult interruptedResult(Archive archive) {
        var scores = new LinkedHashMap<String, Integer>(); var reasons = new LinkedHashMap<String, String>();
        var contexts = new LinkedHashMap<String, AnalysisScoreSemantics.NodeContext>(); var tree = new ArrayList<TaxonomyNodeDto>();
        var warnings = new ArrayList<String>(); var discrepancies = new ArrayList<TaxonomyDiscrepancy>(); var gaps = new ArrayList<ProductCoverageGap>();
        String provider = null;
        for (var work : archive.work()) if (work.taskType().equals(AnalysisTaskType.SUBTAXONOMY_ANALYSIS.name())) {
            if (work.resultJson() == null) { warnings.add(work.root() + ": " + Objects.toString(work.failureReason(), INTERRUPTED) + "; no negative finding"); continue; }
            var result = read(work.resultJson(), AnalysisResult.class);
            for (var score : result.getRawScores().entrySet()) require(scores.putIfAbsent(score.getKey(), score.getValue()) == null, "Conflicting archived root evidence");
            reasons.putAll(result.getReasons()); contexts.putAll(result.getScoreSemanticsContext());
            if (result.getTree() != null) tree.addAll(result.getTree());
            warnings.addAll(result.getWarnings()); discrepancies.addAll(result.getDiscrepancies()); gaps.addAll(result.getProductCoverageGaps()); provider = result.getProvider();
            if (!"SUCCESS".equals(result.getStatus())) warnings.add(work.root() + ": " + Objects.toString(result.getErrorMessage(), "partial assessment"));
        }
        var result = new AnalysisResult(scores, tree); result.setReasons(reasons); result.setScoreSemanticsContext(contexts);
        result.setDiscrepancies(discrepancies); result.setProductCoverageGaps(gaps); result.setProvider(provider);
        if (archive.run().relationPlanJson() != null) result.setRelationSearchReport(RelationSearchDistribution.combine(
                read(archive.run().relationPlanJson(), RelationSearchDistribution.Plan.class), relationEffects(archive)));
        else result.setProvisionalRelations(scoreOnlyRelations(archive));
        warnings.add(INTERRUPTED + ": restored committed evidence; unfinished work requires a new explicit analysis request.");
        result.setWarnings(warnings); result.setStatus("PARTIAL"); result.setErrorMessage(INTERRUPTED);
        result.setAnalysisScope(read(archive.run().commandJson(), AnalyzeRequirementCommand.class).analysisScope());
        if (archive.run().viewJson() != null) result.setViewContext(read(archive.run().viewJson(), ViewContext.class));
        result.setAnalysisDurationMillis(Math.max(0, archive.run().updatedAt() - archive.run().createdAt())); return result;
    }

    private List<RelationHypothesisDto> scoreOnlyRelations(Archive archive) {
        var hypotheses = new ArrayList<RelationHypothesisDto>(); boolean preparationSeen = false;
        for (var work : archive.work()) if (work.taskType().equals(AnalysisTaskType.RELATION_ANALYSIS.name())) {
            require(work.inputJson() == null && new AnalysisTaskId(work.taskId()).relationWorkOrdinal().isEmpty(), "Missing archived relation preparation");
            require(!preparationSeen, "Duplicate archived relation preparation"); preparationSeen = true;
            if (work.resultJson() != null) {
                var evidence = read(work.resultJson(), ClusterRelationComputation.ScoreOnly.class);
                require(evidence.hypotheses().stream().allMatch(value -> value.getHypothesisId() == null), "Score-only evidence cannot reference persisted review records");
                hypotheses.addAll(evidence.hypotheses());
            }
        }
        return List.copyOf(hypotheses);
    }

    private List<RelationSearchDistribution.WorkResult> relationEffects(Archive archive) {
        var results = new ArrayList<RelationSearchDistribution.WorkResult>();
        for (var work : archive.work()) if (work.taskType().equals(AnalysisTaskType.RELATION_ANALYSIS.name()) && work.inputJson() != null) {
            var grant = read(work.inputJson(), RelationSearchDistribution.Work.class);
            require(grant.preparationId().equals(archive.run().context().operationId() + ":relations"), "Foreign archived relation grant");
            if (work.resultJson() != null) {
                var effect = read(work.resultJson(), RelationSearchDistribution.WorkResult.class);
                require(grant.equals(effect.work()), "Archived relation effect mismatches grant"); results.add(effect);
            }
        }
        return results;
    }
    private RelationSearchDistribution.Plan remapPlan(RelationSearchDistribution.Plan plan, String operation) {
        return new RelationSearchDistribution.Plan(plan.schemaVersion(), operation + ":relations", plan.originalSha256(),
                plan.sourceResultIds().stream().map(id -> remapTaskId(new AnalysisTaskId(id), operation).value()).toList(), plan.options(),
                plan.sourceNodes(), plan.targetRoots(), plan.sources(), plan.items(), plan.sourceCalls(), plan.durationMillis(), plan.warnings(), plan.stopReason(), plan.included());
    }
    private static RelationSearchDistribution.Work remapGrant(RelationSearchDistribution.Work grant, String operation) {
        return new RelationSearchDistribution.Work(operation + ":relations", grant.ordinal(), grant.targetRoot(), grant.maxCalls(), grant.maxWorkItems());
    }
    private AnalysisTaskMessage remapTask(AnalysisTaskMessage task, AnalysisOperationContext source, AnalysisOperationContext target) {
        var envelope = remapEnvelope(task.envelope(), source, target);
        if (task instanceof SubtaxonomyAnalysisTask root) return new SubtaxonomyAnalysisTask(envelope, root.root());
        var relation = (RelationAnalysisTask) task;
        return new RelationAnalysisTask(envelope, relation.targetRoots(), relation.prerequisiteTasks().stream().map(id -> remapTaskId(id, target.operationId())).toList());
    }
    private AnalysisEnvelope remapEnvelope(AnalysisEnvelope old, AnalysisOperationContext source, AnalysisOperationContext target) {
        String causation = old.causationId();
        if (Objects.equals(causation, source.correlationId())) causation = target.correlationId();
        else if (causation != null && causation.startsWith(source.operationId() + ":")) causation = target.operationId() + causation.substring(source.operationId().length());
        return new AnalysisEnvelope(old.schemaVersion(), old.messageType(), target.operationId(), old.taskId() == null ? null : remapTaskId(old.taskId(), target.operationId()),
                old.taskType(), target.authority(), target.requirement(), old.roots(), 1, causation, target.correlationId(), old.createdAt(), old.deadline());
    }
    private static AnalysisTaskId remapTaskId(AnalysisTaskId old, String operation) { return new AnalysisTaskId(operation + old.value().substring(old.operationId().length())); }
    private void persistEvent(AnalysisProgressEvent event) {
        var restored = new ClusterAnalysisEvent(); restored.id = key(event.envelope().operationId() + ":event:" + event.sequence());
        restored.operationId = event.envelope().operationId(); restored.revision = event.sequence(); restored.eventJson = encode(event); em.persist(restored);
    }
    private static void requireReference(SourceRecordId sourceId, String kind, SourceRecordId parent, Run run, Set<SourceRecordId> seen) {
        require(sourceId != null && kind.equals(sourceId.kind()) && seen.add(sourceId) && run.sourceId().equals(parent), "Invalid archived child reference");
    }
    private static int bounded(String text) { require(text == null || text.length() <= 8 * 1024 * 1024, "Oversized archived value"); return text == null ? 0 : text.length(); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
    private static String key(String identity) { return RequirementReference.sha256(identity); }
    private <T> T read(String source, Class<T> type) {
        bounded(source);
        try { return json.readValue(source, type); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid archived analysis document"); }
    }
    private AnalysisMessage decode(String source) {
        bounded(source);
        try { return codec.decode(source.getBytes(StandardCharsets.UTF_8)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid archived analysis message"); }
    }
    private String encode(AnalysisMessage message) { return new String(codec.encode(message), StandardCharsets.UTF_8); }
}
