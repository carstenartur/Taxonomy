package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.dispatch.AnalysisDispatchService;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;

/**
 * Short, per-operation transactions for clustered execution. Provider and broker
 * I/O never run under these locks. Result callbacks join the completion ledger's
 * transaction, preserving cancellation and one committed effect on redelivery.
 */
public final class ClusterAnalysisStore implements ClusterRelationService.Store {
    private static final int MAX_JSON_CHARACTERS = 32_000_000;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final AnalysisDispatchService dispatch;
    private final AnalysisMessageCodec codec = new AnalysisMessageCodec();
    private volatile Consumer<AnalysisProgressEvent> events = event -> { };

    public record TaskView(String taskId, String type, String root, String state, int attempts,
                           Long startedAt, Long finishedAt) { }
    public record Snapshot(String operationId, ClusterAnalysisState state, long revision,
                           int completedRoots, int totalRoots, int runningTasks, int queuedTasks,
                           long createdAt, long updatedAt, List<TaskView> tasks, AnalysisResult result) { }
    public record Input(AnalysisOperationContext context, AnalyzeRequirementCommand command,
                        boolean executable, String taskInput) { }

    public ClusterAnalysisStore(EntityManager em, PlatformTransactionManager transactions,
                                ObjectMapper mapper, AnalysisDispatchService dispatch) {
        this.em = Objects.requireNonNull(em); this.tx = new TransactionTemplate(transactions);
        this.mapper = Objects.requireNonNull(mapper); this.dispatch = Objects.requireNonNull(dispatch);
    }

    public void eventPublisher(Consumer<AnalysisProgressEvent> publisher) { events = Objects.requireNonNull(publisher); }

    /** Admit frozen inputs and all root dispatch intents atomically. */
    public void admit(AnalysisOperationContext context, AnalyzeRequirementCommand command, ViewContext view,
                       Map<TaxonomyShardRoot, String> shards) {
        admit(context, command, view, shards, Map.of());
    }

    public void admit(AnalysisOperationContext context, AnalyzeRequirementCommand command, ViewContext view,
                       Map<TaxonomyShardRoot, String> shards, Map<TaxonomyShardRoot, String> relationTargets) {
        validateInput(context, command, shards);
        boolean needsTargets = command.analysisScope().includesRelations() || command.includeArchitectureView();
        if (needsTargets
                && !relationTargets.keySet().equals(new HashSet<>(TaxonomyShardRoot.DEFAULT_ROOTS)))
            throw new IllegalArgumentException("Relation work requires every frozen target shard");
        if (!needsTargets && !relationTargets.isEmpty())
            throw new IllegalArgumentException("Unrequested relation input");
        Map<TaxonomyShardRoot, String> inputs = new LinkedHashMap<>(shards);
        relationTargets.forEach((root, value) -> {
            if (value == null || value.isBlank() || !root.defaultCatalogueRoot()) throw new IllegalArgumentException("Missing frozen target");
            String previous = inputs.putIfAbsent(root, value);
            if (previous != null && !previous.equals(value)) throw new IllegalArgumentException("Conflicting frozen catalogue shard");
        });
        if (inputs.values().stream().mapToLong(String::length).sum() > MAX_JSON_CHARACTERS)
            throw new IllegalArgumentException("Frozen catalogue input exceeds operation bound");
        tx.executeWithoutResult(status -> {
            var existing = em.find(ClusterAnalysisRun.class, context.operationId(), LockModeType.PESSIMISTIC_WRITE);
            if (existing != null) {
                requireAuthority(existing, context);
                if (!existing.commandJson.equals(json(command))) throw new IllegalStateException("Operation input changed");
                return;
            }
            var run = new ClusterAnalysisRun();
            run.id = context.operationId(); run.username = command.username(); run.contextJson = json(context);
            run.scopeKey = scopeKey(command.workspaceContext());
            run.projectId = context.requirement().projectId(); run.requirementId = context.requirement().requirementId();
            run.commandJson = json(command); run.viewJson = view == null ? null : json(view);
            run.state = ClusterAnalysisState.QUEUED; run.totalRoots = shards.size();
            run.createdAt = run.updatedAt = System.currentTimeMillis();
            em.persist(run);
            List<TaxonomyShardRoot> roots = shards.keySet().stream().sorted().toList();
            var graph = AnalysisTaskGraph.plan(run.id, roots, false);
            var messages = factory(context);
            List<AnalysisTaskMessage> tasks = new ArrayList<>();
            for (int i = 0; i < graph.tasks().size(); i++) {
                var task = messages.task(graph.tasks().get(i));
                addWork(task, i, null); tasks.add(task);
            }
            inputs.forEach((root, value) -> {
                var input = new ClusterAnalysisInput(); input.id = key(run.id + ":input:" + root);
                input.operationId = run.id; input.root = root.code(); input.inputJson = value; em.persist(input);
            });
            appendEvent(run, AnalysisProgressPhase.PLANNED);
            dispatch.dispatch(tasks);
        });
    }

    /** A worker obtains only durable command metadata. Shard payloads are read separately. */
    public Input start(AnalysisTaskMessage task) {
        return tx.execute(status -> {
            var run = requireRun(task.envelope().operationId(), true);
            requireAuthority(run, context(task.envelope()));
            var work = requireWork(task);
            boolean executable = !run.state.terminal() && work.resultJson == null && work.failureReason == null;
            if (executable) {
                work.attempts = Math.max(work.attempts + 1, task.envelope().attempt());
                if (work.startedAt == null) work.startedAt = System.currentTimeMillis();
                work.state = "RUNNING";
                if (run.state == ClusterAnalysisState.QUEUED) run.state = ClusterAnalysisState.RUNNING;
                appendEvent(run, AnalysisProgressPhase.TASK_DISPATCHED);
            }
            return new Input(read(run.contextJson, AnalysisOperationContext.class),
                    read(run.commandJson, AnalyzeRequirementCommand.class), executable, work.inputJson);
        });
    }

    public String shard(AnalysisOperationContext context, TaxonomyShardRoot root) {
        return tx.execute(status -> {
            requireAuthority(requireRun(context.operationId(), false), context);
            var input = em.find(ClusterAnalysisInput.class, key(context.operationId() + ":input:" + root));
            if (input == null || !root.code().equals(input.root) || !context.operationId().equals(input.operationId))
                throw new IllegalStateException("Required frozen catalogue shard is absent");
            return input.inputJson;
        });
    }

    public AnalysisResult rootResults(AnalysisOperationContext context) {
        return tx.execute(status -> {
            var run = requireRun(context.operationId(), false); requireAuthority(run, context);
            if (run.completedRoots != run.totalRoots) throw new IllegalStateException("Source cohort is not committed");
            return aggregate(run);
        });
    }

    public AnalyzeRequirementCommand command(AnalysisOperationContext context) {
        return tx.execute(status -> {
            var run = requireRun(context.operationId(), false); requireAuthority(run, context);
            return read(run.commandJson, AnalyzeRequirementCommand.class);
        });
    }

    /** Lightweight reconciliation for already executing workers after a live-event gap. */
    public boolean terminal(AnalysisOperationContext context) {
        return tx.execute(status -> {
            var rows = em.createQuery("select r.state,r.contextJson from ClusterAnalysisRun r where r.id=:id", Object[].class)
                    .setParameter("id", context.operationId()).getResultList();
            if (rows.size() != 1 || !context.equals(read((String) rows.getFirst()[1], AnalysisOperationContext.class)))
                throw new IllegalStateException("Analysis operation is unavailable in this scope");
            return ((ClusterAnalysisState) rows.getFirst()[0]).terminal();
        });
    }

    public RelationSearchDistribution.Plan relationPlan(AnalysisOperationContext context) {
        return tx.execute(status -> {
            var run = requireRun(context.operationId(), false); requireAuthority(run, context);
            if (run.relationPlanJson == null) throw new IllegalStateException("Relation preparation is absent");
            return read(run.relationPlanJson, RelationSearchDistribution.Plan.class);
        });
    }

    public void persistRelationPlan(RelationAnalysisTask task, RelationSearchDistribution.Plan plan) {
        var run = resultRun(task);
        if (run.state.terminal()) return;
        var work = requireWork(task);
        if (task.taskId().relationWorkOrdinal().isPresent() || run.relationPlanJson != null
                || !plan.preparationId().equals(run.id + ":relations")
                || !plan.originalSha256().equals(task.envelope().requirement().textSha256())
                || !plan.sourceResultIds().equals(task.prerequisiteTasks().stream().map(AnalysisTaskId::value).toList())
                || plan.options().limits().maxWorkItems() > 512)
            throw new IllegalArgumentException("Relation preparation does not match admitted source cohort");
        run.relationPlanJson = json(plan); work.resultJson = run.relationPlanJson;
        work.state = "COMPLETED"; work.finishedAt = System.currentTimeMillis();
    }

    /** Disabled hierarchical search retains provisional evidence without a target-work plan. */
    public void persistScoreOnlyRelations(RelationAnalysisTask task, List<RelationHypothesisDto> hypotheses) {
        var run = resultRun(task);
        if (run.state.terminal()) return;
        var work = requireWork(task);
        if (task.taskId().relationWorkOrdinal().isPresent() || run.relationPlanJson != null
                || work.inputJson != null || work.resultJson != null || work.failureReason != null
                || run.completedRoots != run.totalRoots)
            throw new IllegalArgumentException("Score-only relations require an unsettled source preparation");
        work.resultJson = json(new ClusterRelationComputation.ScoreOnly(hypotheses));
        work.state = "COMPLETED"; work.finishedAt = System.currentTimeMillis();
    }

    public void persistRelationResult(RelationAnalysisTask task, RelationSearchDistribution.WorkResult result) {
        var run = resultRun(task);
        if (run.state.terminal()) return;
        var work = requireWork(task);
        if (work.resultJson != null || work.failureReason != null || run.relationPlanJson == null
                || !read(work.inputJson, RelationSearchDistribution.Work.class).equals(result.work())
                || task.taskId().relationWorkOrdinal().orElse(-1) != result.work().ordinal())
            throw new IllegalArgumentException("Relation result does not match admitted grant");
        var results = new ArrayList<>(relationResults(run.id)); results.add(result);
        RelationSearchDistribution.combine(read(run.relationPlanJson, RelationSearchDistribution.Plan.class), results);
        work.resultJson = json(result);
        work.state = result.stopReason().isEmpty() && result.result().searchExhausted() ? "COMPLETED" : "PARTIAL";
        work.finishedAt = System.currentTimeMillis();
    }

    public void persistRelationFailure(RelationAnalysisTask task, String reason) { persistFailure(task, reason); }

    /** Terminal execution failure, including broker expiry/dead-letter. No negative finding is invented. */
    public void persistFailure(AnalysisTaskMessage task, String reason) {
        if (reason == null || !reason.matches("[A-Z_]{1,64}")) throw new IllegalArgumentException("Invalid failure code");
        var run = resultRun(task);
        if (run.state.terminal()) return;
        var work = requireWork(task);
        if (work.resultJson != null || work.failureReason != null) throw new IllegalStateException("Task already has a durable effect");
        work.failureReason = reason; work.state = "FAILED"; work.finishedAt = System.currentTimeMillis();
    }

    private ClusterAnalysisRun resultRun(AnalysisTaskMessage task) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("A result must join its completion transaction");
        var run = requireRun(task.envelope().operationId(), true);
        requireAuthority(run, context(task.envelope())); requireWork(task); return run;
    }

    /** Joins the caller's ledger transaction; never starts an independent result transaction. */
    public void persistResult(AnalysisTaskMessage task, AnalysisResult result) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("A result must join its completion transaction");
        var run = requireRun(task.envelope().operationId(), true);
        requireAuthority(run, context(task.envelope()));
        var work = requireWork(task);
        if (run.state.terminal()) return; // Cancellation won. A late computation cannot publish an effect.
        if (work.resultJson != null) throw new IllegalStateException("Task already has a committed result");
        work.resultJson = json(Objects.requireNonNull(result));
        work.state = "SUCCESS".equals(result.getStatus()) ? "COMPLETED" : "PARTIAL";
        work.finishedAt = System.currentTimeMillis();
    }

    /** Idempotent coordinator effect, called before acknowledging the completion queue. */
    public boolean accept(AnalysisCompletionMessage completion) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var run = requireRun(completion.envelope().operationId(), true);
            requireAuthority(run, context(completion.envelope()));
            var work = em.find(ClusterAnalysisWork.class, key(completion.taskId().value()));
            if (work == null) throw new IllegalStateException("Completion has no expected task");
            var task = (AnalysisTaskMessage) decode(work.messageJson);
            AnalysisTaskIdentity.requireSameSource(task.envelope(), completion.envelope());
            if (run.state.terminal() || work.settled) return false;
            if (work.resultJson == null && work.failureReason == null) throw new IllegalStateException("Completion has no durable result");
            work.settled = true;
            if (task.taskType() == AnalysisTaskType.SUBTAXONOMY_ANALYSIS) run.completedRoots++;
            if (completion.stopsOperation()) {
                run.state = ClusterAnalysisState.PARTIAL;
                var result = aggregate(run);
                String reason = completion instanceof SubtaxonomyAnalysisCompleted root ? root.stopReason()
                        : ((RelationAnalysisCompleted) completion).stopReason();
                result.setStatus("PARTIAL"); result.setErrorMessage(reason + ": remaining analysis work was stopped");
                result.getWarnings().add(result.getErrorMessage()); run.resultJson = json(result);
                for (var pending : works(run.id)) if (!pending.settled) pending.state = "STOPPED";
                appendEvent(run, AnalysisProgressPhase.OPERATION_STOPPED); return true;
            }
            var command = read(run.commandJson, AnalyzeRequirementCommand.class);
            if (run.completedRoots == run.totalRoots) {
                if (command.analysisScope().includesRelations()) advanceRelations(run);
                else finish(run);
            }
            appendEvent(run, run.state.terminal() ? AnalysisProgressPhase.OPERATION_COMPLETED : AnalysisProgressPhase.TASK_COMPLETED);
            return true;
        }));
    }

    public boolean cancel(AnalysisOperationContext context) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var run = requireRun(context.operationId(), true); requireAuthority(run, context);
            if (run.state.terminal()) return false;
            run.state = ClusterAnalysisState.CANCELLED;
            var result = aggregate(run);
            result.setStatus("PARTIAL"); result.setErrorMessage("CANCELLED: Analysis stopped cooperatively");
            result.getWarnings().add(result.getErrorMessage()); run.resultJson = json(result);
            for (var work : works(run.id)) if (!work.settled) work.state = "STOPPED";
            appendEvent(run, AnalysisProgressPhase.OPERATION_STOPPED);
            return true;
        }));
    }

    public Snapshot snapshot(AnalysisOperationContext context) {
        return tx.execute(status -> {
            var run = requireRun(context.operationId(), false); requireAuthority(run, context);
            var tasks = works(run.id).stream().map(w -> new TaskView(w.taskId, w.taskType, w.root, w.state,
                    w.attempts, w.startedAt, w.finishedAt)).toList();
            return new Snapshot(run.id, run.state, run.revision, run.completedRoots, run.totalRoots,
                    (int) tasks.stream().filter(t -> t.state().equals("RUNNING")).count(),
                    (int) tasks.stream().filter(t -> t.state().equals("QUEUED")).count(), run.createdAt, run.updatedAt,
                    tasks, run.resultJson == null ? null : read(run.resultJson, AnalysisResult.class));
        });
    }

    public AnalysisOperationContext authorize(String id, String owner, WorkspaceContext workspace) {
        return findAuthorized(id, owner, workspace).orElseThrow(() -> new IllegalStateException("Analysis operation is unavailable in this scope"));
    }

    public Optional<AnalysisOperationContext> findAuthorized(String id, String owner, WorkspaceContext workspace) {
        return tx.execute(status -> {
            var run = em.find(ClusterAnalysisRun.class, id);
            if (run == null) return Optional.empty();
            var command = read(run.commandJson, AnalyzeRequirementCommand.class);
            if (!Objects.equals(run.username, owner) || !Objects.equals(command.workspaceContext(), workspace))
                throw new SecurityException("Analysis operation is unavailable in this scope");
            return Optional.of(read(run.contextJson, AnalysisOperationContext.class));
        });
    }

    public List<AnalysisOperationContext> recent(String owner, WorkspaceContext workspace, Long projectId, Long requirementId) {
        return tx.execute(status -> {
            var query = em.createQuery("select r.contextJson from ClusterAnalysisRun r where r.username=:owner and r.scopeKey=:scope"
                    + (projectId == null ? "" : " and r.projectId=:project")
                    + (requirementId == null ? "" : " and r.requirementId=:requirement")
                    + " order by r.createdAt desc,r.id", String.class)
                    .setParameter("owner", owner).setParameter("scope", scopeKey(workspace)).setMaxResults(50);
            if (projectId != null) query.setParameter("project", projectId);
            if (requirementId != null) query.setParameter("requirement", requirementId);
            return query.getResultList().stream().map(value -> read(value, AnalysisOperationContext.class)).toList();
        });
    }

    /** Add presentation-only enrichment without replacing the committed assessment. */
    public void enrich(AnalysisOperationContext context, AnalysisResult enriched) {
        finalizeResult(context, enriched);
    }

    /** Complete presentation preparation before exposing a terminal, retrievable result. */
    public void finalizeResult(AnalysisOperationContext context, AnalysisResult enriched) {
        tx.executeWithoutResult(status -> {
            var run = requireRun(context.operationId(), true); requireAuthority(run, context);
            if (run.state != ClusterAnalysisState.FINALIZING) return;
            var result = read(run.resultJson, AnalysisResult.class);
            if (!result.getRawScores().equals(enriched.getRawScores())
                    || !Objects.equals(result.getRelationSearchReport(), enriched.getRelationSearchReport()))
                throw new IllegalArgumentException("Enrichment cannot replace committed assessment evidence");
            result.setArchitectureView(enriched.getArchitectureView());
            result.setWarnings(enriched.getWarnings()); result.setErrorMessage(enriched.getErrorMessage());
            result.setStatus(enriched.getStatus()); run.resultJson = json(result);
            result.setAnalysisDurationMillis(Math.max(0, System.currentTimeMillis() - run.createdAt));
            run.resultJson = json(result);
            run.state = "SUCCESS".equals(result.getStatus()) ? ClusterAnalysisState.COMPLETED : ClusterAnalysisState.PARTIAL;
            appendEvent(run, AnalysisProgressPhase.OPERATION_COMPLETED);
        });
    }

    public List<AnalysisProgressEvent> events(AnalysisOperationContext context, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid event replay window");
        return tx.execute(status -> {
            requireAuthority(requireRun(context.operationId(), false), context);
            return em.createQuery("select e from ClusterAnalysisEvent e where e.operationId=:id and e.revision>:after order by e.revision", ClusterAnalysisEvent.class)
                    .setParameter("id", context.operationId()).setParameter("after", after).setMaxResults(limit).getResultList().stream()
                    .map(e -> (AnalysisProgressEvent) decode(e.eventJson)).toList();
        });
    }

    private void finish(ClusterAnalysisRun run) {
        var result = aggregate(run);
        run.state = "SUCCESS".equals(result.getStatus()) ? ClusterAnalysisState.COMPLETED : ClusterAnalysisState.PARTIAL;
        if (read(run.commandJson, AnalyzeRequirementCommand.class).includeArchitectureView()) run.state = ClusterAnalysisState.FINALIZING;
        run.resultJson = json(result);
    }

    private void advanceRelations(ClusterAnalysisRun run) {
        run.state = ClusterAnalysisState.RELATIONS;
        var context = read(run.contextJson, AnalysisOperationContext.class);
        var tasks = works(run.id);
        var prerequisites = tasks.stream().filter(w -> w.taskType.equals(AnalysisTaskType.SUBTAXONOMY_ANALYSIS.name()))
                .map(w -> new AnalysisTaskId(w.taskId)).toList();
        var relationTasks = tasks.stream().filter(w -> w.taskType.equals(AnalysisTaskType.RELATION_ANALYSIS.name())).toList();
        if (relationTasks.isEmpty()) {
            var roots = TaxonomyShardRoot.DEFAULT_ROOTS.stream().sorted().toList();
            var node = new AnalysisTaskGraph.Node(AnalysisTaskId.relation(run.id, roots), AnalysisTaskType.RELATION_ANALYSIS, roots, prerequisites);
            var preparation = factory(context).task(node);
            addWork(preparation, run.totalRoots, null); dispatch.dispatch(List.of(preparation)); return;
        }
        if (run.relationPlanJson == null) {
            if (relationTasks.stream().allMatch(w -> w.settled)) finish(run);
            return;
        }
        var plan = read(run.relationPlanJson, RelationSearchDistribution.Plan.class);
        var results = relationTasks.stream().filter(w -> w.settled && w.inputJson != null && w.resultJson != null)
                .map(w -> read(w.resultJson, RelationSearchDistribution.WorkResult.class)).toList();
        List<AnalysisTaskMessage> admitted = new ArrayList<>();
        boolean failed = relationTasks.stream().anyMatch(w -> w.failureReason != null);
        if (!failed) for (var grant : RelationSearchDistribution.ready(plan, results)) {
            var id = AnalysisTaskId.relationWork(run.id, grant.targetRoot(), grant.ordinal());
            if (tasks.stream().anyMatch(w -> w.taskId.equals(id.value()))) continue;
            var header = new AnalysisEnvelope(2, AnalysisMessageType.RELATION_ANALYSIS_TASK, run.id, id,
                    AnalysisTaskType.RELATION_ANALYSIS, context.authority(), context.requirement(), List.of(grant.targetRoot()),
                    1, AnalysisTaskId.relation(run.id, TaxonomyShardRoot.DEFAULT_ROOTS).value(), context.correlationId(),
                    java.time.Instant.now(), null);
            var task = new RelationAnalysisTask(header, List.of(grant.targetRoot()), prerequisites);
            addWork(task, run.totalRoots + 1 + grant.ordinal(), json(grant)); admitted.add(task);
        }
        if (!admitted.isEmpty()) dispatch.dispatch(admitted);
        else if (relationTasks.stream().allMatch(w -> w.settled)) finish(run);
    }

    private List<RelationSearchDistribution.WorkResult> relationResults(String id) {
        return works(id).stream().filter(w -> w.taskType.equals(AnalysisTaskType.RELATION_ANALYSIS.name())
                        && w.inputJson != null && w.resultJson != null)
                .map(w -> read(w.resultJson, RelationSearchDistribution.WorkResult.class)).toList();
    }

    private AnalysisResult aggregate(ClusterAnalysisRun run) {
        var scores = new LinkedHashMap<String, Integer>(); var reasons = new LinkedHashMap<String, String>();
        var contexts = new LinkedHashMap<String, AnalysisScoreSemantics.NodeContext>();
        List<TaxonomyNodeDto> tree = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        List<TaxonomyDiscrepancy> discrepancies = new ArrayList<>(); List<ProductCoverageGap> gaps = new ArrayList<>();
        String provider = null;
        for (var work : works(run.id)) {
            if (!work.taskType.equals(AnalysisTaskType.SUBTAXONOMY_ANALYSIS.name())) continue;
            if (work.resultJson == null) { warnings.add(work.root + ": " + Objects.toString(work.failureReason, "unexecuted root") + "; no negative finding"); continue; }
            var result = read(work.resultJson, AnalysisResult.class);
            for (var score : result.getRawScores().entrySet()) {
                if (scores.putIfAbsent(score.getKey(), score.getValue()) != null)
                    throw new IllegalStateException("Conflicting catalogue identities across root results");
            }
            reasons.putAll(result.getReasons()); contexts.putAll(result.getScoreSemanticsContext());
            if (result.getTree() != null) tree.addAll(result.getTree());
            warnings.addAll(result.getWarnings()); discrepancies.addAll(result.getDiscrepancies());
            gaps.addAll(result.getProductCoverageGaps()); provider = result.getProvider();
            if (!"SUCCESS".equals(result.getStatus())) warnings.add(work.root + ": "
                    + Objects.toString(result.getErrorMessage(), "partial assessment"));
        }
        var result = new AnalysisResult(scores, tree); result.setReasons(reasons); result.setScoreSemanticsContext(contexts);
        for (var work : works(run.id)) if (work.taskType.equals(AnalysisTaskType.RELATION_ANALYSIS.name()) && work.failureReason != null)
            warnings.add(work.failureReason + ": relation work incomplete; no negative finding");
        if (run.relationPlanJson != null) {
            var report = RelationSearchDistribution.combine(read(run.relationPlanJson, RelationSearchDistribution.Plan.class), relationResults(run.id));
            result.setRelationSearchReport(report); result.setProvisionalRelations(List.of());
            if (!report.isSearchExhausted()) {
                String summary = "RELATION_SEARCH_PARTIAL: " + report.totalCalls() + "/" + report.maxCalls()
                        + " evaluation attempts; " + report.result().unfinished().size() + " unfinished search batches."
                        + (report.stopReason().isEmpty() ? "" : " " + report.stopReason());
                result.setErrorMessage(summary); warnings.add(summary); warnings.addAll(report.warnings());
            }
        }
        if (run.relationPlanJson == null) {
            result.setProvisionalRelations(works(run.id).stream()
                    .filter(work -> work.taskType.equals(AnalysisTaskType.RELATION_ANALYSIS.name())
                            && work.inputJson == null && work.resultJson != null)
                    .flatMap(work -> read(work.resultJson, ClusterRelationComputation.ScoreOnly.class).hypotheses().stream())
                    .toList());
        }
        result.setWarnings(warnings); result.setDiscrepancies(discrepancies); result.setProductCoverageGaps(gaps);
        result.setProvider(provider); result.setStatus(warnings.isEmpty() ? "SUCCESS" : "PARTIAL");
        result.setAnalysisScope(read(run.commandJson, AnalyzeRequirementCommand.class).analysisScope());
        result.setViewContext(run.viewJson == null ? null : read(run.viewJson, ViewContext.class));
        result.setAnalysisDurationMillis(Math.max(0, System.currentTimeMillis() - run.createdAt));
        return result;
    }

    private void appendEvent(ClusterAnalysisRun run, AnalysisProgressPhase phase) {
        run.revision++; run.updatedAt = System.currentTimeMillis();
        var context = read(run.contextJson, AnalysisOperationContext.class);
        var event = factory(context).progress(run.revision, phase, null, run.completedRoots, run.totalRoots);
        var record = new ClusterAnalysisEvent(); record.id = key(run.id + ":event:" + run.revision);
        record.operationId = run.id; record.revision = run.revision; record.eventJson = encode(event); em.persist(record);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { events.accept(event); }
                catch (RuntimeException ignored) { /* Durable replay remains authoritative after a live delivery gap. */ }
            }
        });
    }

    private void addWork(AnalysisTaskMessage task, int ordinal, String input) {
        var work = new ClusterAnalysisWork(); work.id = key(task.taskId().value());
        work.operationId = task.envelope().operationId(); work.taskId = task.taskId().value();
        work.taskType = task.taskType().name(); work.root = task.routingRoot() == null ? null : task.routingRoot().code();
        work.ordinal = ordinal; work.messageJson = encode(task); work.inputJson = input; work.state = "QUEUED";
        em.persist(work);
    }
    private ClusterAnalysisRun requireRun(String id, boolean lock) {
        if (lock) {
            // A real row write serializes the short coordinator mutation on every
            // supported MVCC database. Some SELECT FOR UPDATE implementations can
            // return a pre-lock snapshot: that would allocate the same event revision
            // twice. Refresh after the write; never hold this lock during computation.
            int changed = em.createQuery("update ClusterAnalysisRun r set r.version=r.version+1 where r.id=:id")
                    .setParameter("id", id).executeUpdate();
            if (changed != 1) throw new IllegalStateException("Analysis operation is unavailable in this scope");
        }
        var run = em.find(ClusterAnalysisRun.class, id);
        if (run == null) throw new IllegalStateException("Analysis operation is unavailable in this scope");
        if (lock) em.refresh(run);
        return run;
    }
    private ClusterAnalysisWork requireWork(AnalysisTaskMessage task) {
        var work = em.find(ClusterAnalysisWork.class, key(task.taskId().value()));
        if (work == null || !work.taskId.equals(task.taskId().value())) throw new IllegalStateException("Unexpected analysis task");
        var expected = (AnalysisTaskMessage) decode(work.messageJson);
        AnalysisTaskIdentity.requireSameSource(expected.envelope(), task.envelope());
        if (expected instanceof RelationAnalysisTask relation && task instanceof RelationAnalysisTask delivered
                && (!relation.prerequisiteTasks().equals(delivered.prerequisiteTasks()) || !relation.targetRoots().equals(delivered.targetRoots())))
            throw new IllegalStateException("Relation input references changed");
        return work;
    }
    private List<ClusterAnalysisWork> works(String operation) {
        return em.createQuery("select w from ClusterAnalysisWork w where w.operationId=:id order by w.ordinal", ClusterAnalysisWork.class)
                .setParameter("id", operation).getResultList();
    }
    private void requireAuthority(ClusterAnalysisRun run, AnalysisOperationContext context) {
        if (!read(run.contextJson, AnalysisOperationContext.class).equals(context))
            throw new IllegalStateException("Analysis operation source identity mismatch");
    }
    private static void validateInput(AnalysisOperationContext context, AnalyzeRequirementCommand command,
                                      Map<TaxonomyShardRoot, String> shards) {
        Objects.requireNonNull(context); Objects.requireNonNull(command); Objects.requireNonNull(shards);
        var workspace = Objects.requireNonNull(command.workspaceContext());
        if (!context.requirement().matches(command.businessText()) || command.username() == null || command.username().isBlank()
                || !command.username().equals(workspace.username())
                || !context.authority().repositoryId().equals(workspace.repositoryId())
                || !Objects.equals(context.authority().workspaceId(), workspace.workspaceId())
                || !Objects.equals(context.authority().branch(), workspace.currentBranch()))
            throw new IllegalStateException("Operation input does not match its immutable authority");
        if (shards.isEmpty() || shards.size() > 8 || shards.keySet().stream().anyMatch(r -> !r.defaultCatalogueRoot()
                || !command.analysisScope().selects(r.code()))) throw new IllegalArgumentException("Invalid selected catalogue shards");
        if (!command.analysisScope().taxonomyRoots().isEmpty()
                && !shards.keySet().stream().map(TaxonomyShardRoot::code).collect(java.util.stream.Collectors.toSet())
                .equals(command.analysisScope().taxonomyRoots())) throw new IllegalArgumentException("Incomplete selected catalogue shards");
        long characters = 0;
        for (String value : shards.values()) { if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing shard input"); characters += value.length(); }
        if (characters > MAX_JSON_CHARACTERS) throw new IllegalArgumentException("Frozen catalogue input exceeds operation bound");
    }
    public static AnalysisOperationContext context(AnalysisEnvelope envelope) {
        return new AnalysisOperationContext(envelope.operationId(), envelope.authority(), envelope.requirement(), envelope.correlationId());
    }
    private static AnalysisMessageFactory factory(AnalysisOperationContext context) { return new AnalysisMessageFactory(context, Clock.systemUTC()); }
    private String json(Object value) {
        String result = mapper.writeValueAsString(value);
        if (result.length() > MAX_JSON_CHARACTERS) throw new IllegalArgumentException("Analysis payload exceeds operation bound");
        return result;
    }
    private <T> T read(String json, Class<T> type) { return mapper.readValue(json, type); }
    private String encode(AnalysisMessage message) { return new String(codec.encode(message), StandardCharsets.UTF_8); }
    private AnalysisMessage decode(String json) { return codec.decode(json.getBytes(StandardCharsets.UTF_8)); }
    private static String key(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String scopeKey(WorkspaceContext scope) {
        Objects.requireNonNull(scope, "workspace scope");
        var parts = java.util.Arrays.asList(scope.username(), scope.repositoryId(), scope.workspaceId(), scope.currentBranch());
        return key(parts.stream().map(value -> value == null ? "-1:" : value.length() + ":" + value)
                .collect(java.util.stream.Collectors.joining()));
    }
}
