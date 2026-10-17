package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.backup.SourceRecordId;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
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
    private final java.util.function.Predicate<com.taxonomy.extension.api.plugin.PluginInvocation> plugins;
    private final AnalysisMessageCodec codec = new AnalysisMessageCodec();

    public ClusterAnalysisBackupRestorer(EntityManager em, PlatformTransactionManager transactions, ObjectMapper json) {
        this(em, transactions, json, binding -> false);
    }
    /** Caller supplies its verified installation; no provider is invoked or resumed during restore. */
    public ClusterAnalysisBackupRestorer(EntityManager em, PlatformTransactionManager transactions, ObjectMapper json,
            java.util.function.Predicate<com.taxonomy.extension.api.plugin.PluginInvocation> plugins) {
        this.em = Objects.requireNonNull(em); this.transactions = new TransactionTemplate(transactions); this.json = Objects.requireNonNull(json);
        this.plugins = Objects.requireNonNull(plugins);
    }

    public void restore(Archive archive, AnalysisOperationContext target, AnalyzeRequirementCommand command) {
        validate(archive, target, command);
        if (command.providerBinding() != null && !plugins.test(command.providerBinding()))
            throw new IllegalArgumentException("Required provider artifact or configuration is unavailable for restore");
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
            run.contextJson = json.writeValueAsString(target); run.commandJson = json.writeValueAsString(command.withContinuationBinding(null));
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
                var progress = ClusterAnalysisProgress.read(work.progressJson(), json);
                if (progress != null) restored.progressJson = json.writeValueAsString(new ClusterAnalysisProgress.Worker(
                        UUID.randomUUID().toString(), progress.delivery(), progress.revision(), progress.phase(), progress.node(),
                        progress.scores(), progress.relations()));
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
            var callIds = new HashMap<SourceRecordId, String>();
            var executions = new HashMap<String, String>(); var invocations = new HashMap<String, String>();
            for (var call : archive.calls()) {
                var restored = new ClusterAnalysisCall(); restored.id = ClusterAnalysisDiagnostics.callKey(run.id, call.callNumber());
                restored.operationId = run.id; restored.callNumber = call.callNumber();
                var originalTask = archive.work().stream().filter(work -> work.taskId().equals(call.taskId())).findFirst().orElseThrow();
                restored.taskId = remapTask((AnalysisTaskMessage) decode(originalTask.messageJson()), source.context(), target).taskId().value();
                restored.executionId = executions.computeIfAbsent(call.executionId(), ignored -> UUID.randomUUID().toString());
                restored.logicalOrdinal = call.logicalOrdinal(); restored.deliveryAttempt = call.deliveryAttempt(); restored.provider = call.provider(); restored.node = call.node();
                restored.state = call.state(); restored.startedAt = call.startedAt(); restored.durationMillis = call.durationMillis(); restored.eventRevision = call.eventRevision();
                restored.prompt = call.prompt(); restored.response = call.response(); restored.error = call.error(); restored.promptLength = call.promptLength(); restored.responseLength = call.responseLength();
                callIds.put(call.sourceId(), restored.id); em.persist(restored);
            }
            for (var attempt : archive.attempts()) {
                var restored = new ClusterAnalysisAttempt(); restored.id = UUID.randomUUID().toString(); restored.operationId = run.id; restored.callId = callIds.get(attempt.call());
                restored.invocationId = invocations.computeIfAbsent(attempt.invocationId(), ignored -> UUID.randomUUID().toString());
                restored.provider = attempt.provider(); restored.source = attempt.source(); restored.retryIndex = attempt.retryIndex(); restored.startedAt = attempt.startedAt();
                if (attempt.observationJson() != null) {
                    var observation = read(attempt.observationJson(), com.taxonomy.analysis.service.LlmTransportMeter.Observation.class);
                    restored.observationJson = json.writeValueAsString(new com.taxonomy.analysis.service.LlmTransportMeter.Observation(restored.invocationId, observation.provider(), observation.source(),
                            observation.retryIndex(), observation.statusCode(), observation.outcome(), observation.durationMillis(), observation.usage()));
                }
                em.persist(restored);
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
                && sourceCommand.maxArchitectureNodes() == command.maxArchitectureNodes() && Objects.equals(sourceCommand.provider(), command.provider())
                && Objects.equals(sourceCommand.providerBinding(), command.providerBinding())
                && Objects.equals(sourceCommand.analysisReasonLength(), command.analysisReasonLength())
                && Objects.equals(json.valueToTree(sourceCommand.retainedAnalysis()), json.valueToTree(command.retainedAnalysis())),
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
            var progress = ClusterAnalysisProgress.read(work.progressJson(), json);
            validateProgress(progress, run.revision(), work.attempts(), work.sourceState(), task);
            validateProgressCatalogue(progress, task, archive);
            characters += bounded(work.messageJson()) + bounded(work.inputJson()) + bounded(work.resultJson()) + bounded(work.progressJson());
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
        require(archive.calls().size() <= ClusterAnalysisDiagnostics.MAX_CALLS && archive.attempts().size() <= ClusterAnalysisDiagnostics.MAX_ATTEMPTS, "Oversized archived diagnostics");
        var calls = new HashMap<SourceRecordId, Call>(); var numbers = new HashSet<Long>(); var logicalKeys = new HashSet<String>();
        for (var call : archive.calls()) {
            requireReference(call.sourceId(), "analysis.cluster-call", call.run(), run, sourceIds);
            validateCall(call, run.revision(), taskIds);
            require(numbers.add(call.callNumber()) && logicalKeys.add(call.executionId() + ":" + call.logicalOrdinal()), "Duplicate archived logical call");
            calls.put(call.sourceId(), call); characters += bounded(call.prompt()) + bounded(call.response());
        }
        var attemptCounts = new HashMap<SourceRecordId, Integer>(); var attemptIds = new HashSet<String>();
        for (var attempt : archive.attempts()) {
            requireReference(attempt.sourceId(), "analysis.cluster-attempt", attempt.run(), run, sourceIds);
            validateAttempt(attempt, calls, json);
            require(attemptIds.add(attempt.attemptId()) && attemptCounts.merge(attempt.call(), 1, Integer::sum) <= ClusterAnalysisDiagnostics.MAX_ATTEMPTS_PER_CALL, "Duplicate or oversized archived attempt");
            characters += bounded(attempt.observationJson());
        }
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

    static void validateProgress(ClusterAnalysisProgress.Worker progress, long revision, int attempts,
                                 String state, AnalysisTaskMessage task) {
        if (progress == null) return;
        require(progress.revision() <= revision && (progress.delivery() <= attempts
                || attempts == 0 && !Set.of("QUEUED", "RUNNING").contains(state)), "Invalid archived worker progress delivery");
        require(task.taskType() == AnalysisTaskType.SUBTAXONOMY_ANALYSIS || progress.scores().isEmpty(), "Foreign worker coverage");
        require(task.taskType() == AnalysisTaskType.RELATION_ANALYSIS || progress.relations() == null, "Foreign relation progress");
    }

    private void validateProgressCatalogue(ClusterAnalysisProgress.Worker progress, AnalysisTaskMessage task, Archive archive) {
        if (progress == null || progress.scores().isEmpty() && progress.relations() == null) return;
        var roots = new HashMap<String, RootCatalogueSnapshot>();
        boolean restored = ClusterAnalysisState.valueOf(archive.run().sourceState()).terminal()
                && archive.work().stream().allMatch(work -> work.attempts() == 0 && work.startedAt() == null);
        com.taxonomy.catalog.snapshot.CatalogueSourceIdentity original = null;
        for (var input : archive.inputs()) {
            var root = ClusterAnalysisProgress.frozen(input.inputJson(), json, archive.run().context(), input.root(), restored);
            if (root == null) continue;
            require(original == null || original.equals(root.source()), "Conflicting archived progress sources");
            original = root.source(); roots.put(input.root(), root);
        }
        if (task.taskType() == AnalysisTaskType.SUBTAXONOMY_ANALYSIS) {
            var root = roots.get(task.routingRoot().code());
            require(root != null && root.nodes().stream().map(RootCatalogueSnapshot.Node::code).collect(java.util.stream.Collectors.toSet())
                    .containsAll(progress.scores().keySet()), "Worker coverage differs from frozen catalogue");
            return;
        }
        var p = progress.relations(); if (p == null) return;
        int sources = archive.work().stream().filter(work -> work.taskType().equals(AnalysisTaskType.SUBTAXONOMY_ANALYSIS.name()))
                .map(work -> roots.get(work.root())).filter(Objects::nonNull).mapToInt(root -> root.nodes().size()).sum();
        require(p.totalSources() <= sources && p.taxonomies().stream().allMatch(part -> roots.containsKey(part.root())
                && part.total() <= p.totalSources()), "Relation progress differs from frozen catalogue");
        if (task.taskId().relationWorkOrdinal().isPresent()) {
            var work = archive.work().stream().filter(value -> value.taskId().equals(task.taskId().value())).findFirst().orElseThrow();
            var grant = read(work.inputJson(), RelationSearchDistribution.Work.class);
            var plan = read(archive.run().relationPlanJson(), RelationSearchDistribution.Plan.class);
            var item = plan.items().get(grant.ordinal());
            require(p.maxCalls() == grant.maxCalls() && p.totalSources() == 1 && p.totalSearches() <= 1
                    && p.taxonomies().stream().allMatch(part -> part.root().equals(grant.targetRoot().code())), "Progress differs from admitted relation grant");
            var current = p.current();
            if (current != null) {
                var root = roots.get(grant.targetRoot().code());
                require(root != null && root.nodes().stream().map(RootCatalogueSnapshot.Node::code).collect(java.util.stream.Collectors.toSet())
                        .containsAll(current.candidates()) && current.sourceId().equals(plan.sources().get(item.sourceIndex()).node().id())
                        && current.targetRoot().equals(grant.targetRoot().code()) && current.type().equals(item.type())
                        && current.direction() == item.direction(), "Foreign archived current relation search");
            }
        } else if (p.current() != null) {
            var sourceCodes = new HashSet<String>();
            for (var work : archive.work()) if (work.taskType().equals(AnalysisTaskType.SUBTAXONOMY_ANALYSIS.name())) {
                var root = roots.get(work.root()); if (root != null) root.nodes().forEach(node -> sourceCodes.add(node.code()));
            }
            var current = p.current();
            require(p.step() == RelationSearchProgress.Step.SOURCES && current.targetRoot().isEmpty() && current.type().isEmpty()
                    && current.direction() == null && current.depth() == 0 && sourceCodes.containsAll(current.candidates())
                    && current.sourceId().equals(String.join(", ", current.candidates())), "Foreign archived source assessment progress");
        }
    }

    static void validateCall(Call call, long revision, Set<String> taskIds) {
        ClusterAnalysisDiagnostics.uuid(call.executionId());
        require(taskIds.contains(call.taskId()) && call.callNumber() > 0 && call.callNumber() <= ClusterAnalysisDiagnostics.MAX_CALLS
                && call.logicalOrdinal() > 0 && call.deliveryAttempt() > 0 && call.startedAt() >= 0 && call.durationMillis() >= 0
                && call.eventRevision() > 0 && call.eventRevision() <= revision
                && Set.of("STARTED", "COMPLETED", "FAILED", "STOPPED").contains(call.state())
                && call.provider() != null && !call.provider().isBlank() && call.provider().length() <= 128
                && ClusterAnalysisDiagnostics.length(call.node()) <= 512 && ClusterAnalysisDiagnostics.length(call.error()) <= 1024
                && ClusterAnalysisDiagnostics.length(call.prompt()) <= ClusterAnalysisDiagnostics.MAX_TEXT && ClusterAnalysisDiagnostics.length(call.response()) <= ClusterAnalysisDiagnostics.MAX_TEXT
                && call.promptLength() >= ClusterAnalysisDiagnostics.length(call.prompt()) && call.responseLength() >= ClusterAnalysisDiagnostics.length(call.response()), "Invalid archived logical call");
        require(call.sourceId().equals(new SourceRecordId("analysis.cluster-call", ClusterAnalysisDiagnostics.callKey(call.run().value(), call.callNumber()))), "Invalid archived logical call key");
    }
    static void validateAttempt(Attempt attempt, Map<SourceRecordId, Call> calls, ObjectMapper json) {
        var call = calls.get(attempt.call());
        require(call != null, "Missing archived logical call");
        validateAttempt(attempt, call.run(), call.startedAt(), json);
    }
    static void validateAttempt(Attempt attempt, SourceRecordId callRun, long callStartedAt, ObjectMapper json) {
        require(callRun.equals(attempt.run()) && attempt.startedAt() >= callStartedAt
                && attempt.sourceId().equals(new SourceRecordId("analysis.cluster-attempt", attempt.attemptId())), "Invalid archived transport ownership");
        var start = new com.taxonomy.analysis.service.LlmTransportMeter.Attempt(attempt.attemptId(), attempt.invocationId(), attempt.provider(),
                com.taxonomy.analysis.service.LlmTransportMeter.Source.valueOf(attempt.source()), attempt.retryIndex());
        ClusterAnalysisDiagnostics.validate(start);
        if (attempt.observationJson() != null) {
            require(attempt.observationJson().length() <= 8192, "Oversized transport observation");
            ClusterAnalysisDiagnostics.validateResult(start, json.readValue(attempt.observationJson(), com.taxonomy.analysis.service.LlmTransportMeter.Observation.class));
        }
    }

    private AnalysisResult interruptedResult(Archive archive) {
        var command = read(archive.run().commandJson(), AnalyzeRequirementCommand.class);
        var retained = command.retainedAnalysis();
        var scores = new LinkedHashMap<String, Double>(); var reasons = new LinkedHashMap<String, String>();
        var contexts = new LinkedHashMap<String, AnalysisScoreSemantics.NodeContext>(); var tree = new ArrayList<TaxonomyNodeDto>();
        var warnings = new ArrayList<String>(retained == null ? List.of() : retained.getWarnings());
        var discrepancies = new ArrayList<TaxonomyDiscrepancy>(); var gaps = new ArrayList<ProductCoverageGap>();
        var coverage = new ClusterAnalysisCoverage();
        String provider = null;
        for (var work : archive.work()) if (retained == null && work.taskType().equals(AnalysisTaskType.SUBTAXONOMY_ANALYSIS.name())) {
            var result = work.resultJson() == null ? null : read(work.resultJson(), AnalysisResult.class);
            coverage.add(work.root(), result);
            if (result == null) { warnings.add(work.root() + ": " + Objects.toString(work.failureReason(), INTERRUPTED) + "; no negative finding"); continue; }
            for (var score : result.getRawScores().entrySet()) require(scores.putIfAbsent(score.getKey(), score.getValue()) == null, "Conflicting archived root evidence");
            reasons.putAll(result.getReasons()); contexts.putAll(result.getScoreSemanticsContext());
            if (result.getTree() != null) tree.addAll(result.getTree());
            warnings.addAll(result.getWarnings()); discrepancies.addAll(result.getDiscrepancies()); gaps.addAll(result.getProductCoverageGaps()); provider = result.getProvider();
            if (!"SUCCESS".equals(result.getStatus())) warnings.add(work.root() + ": " + Objects.toString(result.getErrorMessage(), "partial assessment"));
        }
        // Retained source evidence is canonical for an extension. Partitioned root
        // work records authorize prerequisites; they cannot reconstruct all node notes.
        var result = retained == null ? new AnalysisResult(scores, tree) : read(json.writeValueAsString(retained), AnalysisResult.class);
        if (retained == null) {
            result.setReasons(reasons); result.setScoreSemanticsContext(contexts);
            // Inputs retain their archived identity; target authority is remapped only after aggregation.
            result.setAnalysisCoverage(coverage.combine(archive.run().context().authority(), root -> read(
                    archive.inputs().stream().filter(input -> input.root().equals(root)).findFirst().orElseThrow().inputJson(),
                    RootCatalogueSnapshot.class)));
            result.setDiscrepancies(discrepancies); result.setProductCoverageGaps(gaps); result.setProvider(provider);
        }
        if (archive.run().relationPlanJson() != null) {
            var report = RelationSearchDistribution.combine(read(archive.run().relationPlanJson(), RelationSearchDistribution.Plan.class), relationEffects(archive));
            result.setRelationSearchReport(RelationSearchDistribution.retainAcceptedRelations(report,
                    retained == null ? null : retained.getRelationSearchReport()));
            result.setProvisionalRelations(List.of());
        } else result.setProvisionalRelations(scoreOnlyRelations(archive));
        warnings.add(INTERRUPTED + ": restored committed evidence; unfinished work requires a new explicit analysis request.");
        result.setWarnings(warnings); result.setStatus("PARTIAL"); result.setErrorMessage(INTERRUPTED);
        result.setAnalysisScope(command.analysisScope());
        result.setAnalysisReasonLength(command.analysisReasonLength());
        if (archive.run().viewJson() != null) result.setViewContext(read(archive.run().viewJson(), ViewContext.class));
        result.setAnalysisDurationMillis(Math.max(0, archive.run().updatedAt() - archive.run().createdAt()));
        if (result.getArchitectureView() != null) {
            result.getArchitectureView().setRelationSearchReport(result.getRelationSearchReport());
            result.getArchitectureView().setAnalysisStatus(result.getStatus());
        }
        return result;
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
