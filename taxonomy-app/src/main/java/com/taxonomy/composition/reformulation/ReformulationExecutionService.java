package com.taxonomy.composition.reformulation;

import com.taxonomy.analysis.reformulation.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.portfolio.reformulation.*;
import com.taxonomy.portfolio.reformulation.ReformulationDtos.*;
import com.taxonomy.portfolio.reformulation.ReformulationRecoveryService.Claim;
import com.taxonomy.portfolio.reformulation.ReformulationRecoveryService.Dispatch;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.reformulation.ReformulationStepExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Captured execution scope; recoverable dispatch never reads ambient authentication. */
@Service
public class ReformulationExecutionService {
    private final ReformulationService proposals;
    private final FrozenReformulationEngine engine;
    private final CrossTaxonomyReconciler reconciler;
    private final LlmProviderConfig providers;
    private final AsyncTaskExecutor executor;
    private final ObjectMapper checkpointJson;
    private final ReformulationRecoveryService recovery;
    private final ReformulationUsageService usage;
    private final String owner = UUID.randomUUID().toString();
    private final Map<String,LocalExecution> scheduled = new ConcurrentHashMap<>();
    private volatile boolean stopping;
    private String scanCursor = "";

    public ReformulationExecutionService(ReformulationService proposals,FrozenReformulationEngine engine,
            CrossTaxonomyReconciler reconciler,LlmProviderConfig providers,
            @Qualifier("portfolioAnalysisExecutor") AsyncTaskExecutor executor,ObjectMapper json,
            ReformulationRecoveryService recovery, ReformulationUsageService usage) {
        this.proposals=proposals;this.engine=engine;this.reconciler=reconciler;this.providers=providers;this.executor=executor;
        this.recovery=recovery; this.usage=usage;
        this.checkpointJson=json.rebuild().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }
    public Run start(Long projectId,Long requirementId,String proposalId,long expectedRevision,String actor,WorkspaceContext context) {
        if (stopping) throw new IllegalStateException("REFORMULATION_EXECUTOR_STOPPED");
        var proposal=proposals.get(projectId,requirementId,proposalId,actor,context);
        if(proposal.currentRevision().number()!=expectedRevision) throw new ReformulationPreconditionException();
        var provider=providers.getActiveProvider();
        String model=provider==LlmProvider.LOCAL_ONNX?"LOCAL_ONNX":model(provider);
        var frozen=proposal.baseline().frozenContext();
        String prompt=frozen.getOrDefault("reformulationPrompt",ReformulationPromptBuilder.template());
        var dispatch=recovery.enqueue(projectId,requirementId,proposalId,expectedRevision,provider.name(),model,
                frozen.getOrDefault("reformulationPromptVersion",ReformulationPromptBuilder.PROMPT_VERSION),
                frozen.getOrDefault("reformulationSchemaVersion",ReformulationPromptBuilder.SCHEMA_VERSION),prompt,
                ReconcilePromptBuilder.freeze(frozen),endpointHash(provider),actor,context);
        submit(dispatch);
        return proposals.runs(projectId,requirementId,proposalId,actor,context).stream()
                .filter(r->r.id().equals(dispatch.run().id())).findFirst().orElseThrow();
    }
    /** Bounded timer tick; the persistent row remains queued if the shared executor is full. */
    public synchronized void recoverAvailable(int maximumInFlight) {
        if (stopping) return;
        int remaining=maximumInFlight-scheduled.size();
        if(remaining<=0)return;
        var page=recovery.dueAfter(Math.min(remaining,100),scanCursor);
        scanCursor=page.isEmpty()?"":page.getLast().run().id();
        for(var dispatch:page) submit(dispatch);
    }
    public void heartbeat() {
        for (var entry : scheduled.entrySet()) {
            var local = entry.getValue();
            Claim token = local.claim();
            if (token == null) continue;
            try {
                if (!recovery.heartbeat(token)) retire(entry.getKey(), local);
            } catch (RuntimeException failure) {
                // The next successful DB-clock check decides ownership. Do not
                // replace authoritative lease time with a process-local clock.
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Reformulation lease renewal failed for run {}", entry.getKey());
            }
        }
    }
    /** Stop local dispatch promptly; valid database leases expire for restart recovery. */
    public void shutdown() {
        stopping = true;
        scheduled.forEach(this::retire);
    }
    private void retire(String id, LocalExecution local) {
        local.retire();
        scheduled.remove(id, local);
    }
    private void submit(Dispatch dispatch) {
        if (stopping) return;
        String id = dispatch.run().id();
        var local = new LocalExecution();
        if (scheduled.putIfAbsent(id, local) != null) return;
        if (stopping) { retire(id, local); return; }
        try { executor.execute(() -> execute(dispatch, local)); }
        catch (org.springframework.core.task.TaskRejectedException rejected) { retire(id, local); }
    }
    /** A unique reservation per delivery, not just a run ID shared with its successor. */
    private static final class LocalExecution {
        private Thread runner;
        private Claim claim;
        private boolean entered;
        private boolean retired;
        synchronized boolean enter() {
            if (entered || retired) return false;
            entered = true; runner = Thread.currentThread(); return true;
        }
        synchronized boolean attach(Claim token) { claim = token; return !retired; }
        synchronized Claim claim() { return claim; }
        synchronized boolean retired() { return retired; }
        synchronized void retire() {
            retired = true;
            // Interrupt while holding the local monitor: finish cannot detach and
            // return this thread to a shared executor between lookup and interrupt.
            if (runner != null) runner.interrupt();
        }
        synchronized void finished() { runner = null; }
    }
    private void execute(Dispatch dispatch, LocalExecution local) {
        if (!local.enter()) return; // A duplicate queue delivery owns no cleanup.
        Claim token=null;
        try {
            if (stopping) return;
            token=recovery.claim(dispatch,owner).orElseThrow(()->com.taxonomy.portfolio.service.PortfolioException.conflict("REFORMULATION_CLAIM_REJECTED"));
            if (!local.attach(token)) return;
            var run=dispatch.run();var provider=LlmProvider.valueOf(run.provider());
            providers.setRequestProvider(provider);
            if(provider==LlmProvider.LOCAL_ONNX || !providers.isProviderConfigured(provider)
                    || providers.getProviderConfigurationError(provider)!=null || providers.isMockMode())
                throw new IllegalStateException("PROVIDER_NOT_CONFIGURED");
            if(!run.model().equals(model(provider)) || !dispatch.endpointHash().equals(endpointHash(provider)))
                throw new IllegalStateException("MODEL_CONFIGURATION_CHANGED");
            var proposal=recovery.source(dispatch);
            var baseline=proposal.baseline();var captured=new java.util.TreeMap<>(baseline.frozenContext());
            captured.put("reformulationPrompt",run.promptContent());captured.putAll(run.reconcileContext());
            captured.put("reformulationPromptVersion",run.promptVersion());captured.put("reformulationSchemaVersion",run.schemaVersion());
            var runBaseline=new com.taxonomy.reformulation.ReformulationBaseline(baseline.scope(),baseline.sourceVersionId(),baseline.originalText(),
                    baseline.originalTextHash(),baseline.snapshotId(),baseline.snapshotPayload(),captured,baseline.language(),baseline.algorithmVersion());
            var revision=proposal.currentRevision();
            var steps=checkpointExecutor(dispatch.projectId(),dispatch.requirementId(),proposal.id(),run,dispatch.actor(),dispatch.context(),token,local::retired);
            usage.activate(token);
            try (var journal = LlmTransportMeter.openJournal(usageJournal(token, local))) {
                com.taxonomy.reformulation.ReformulationDocument reconciled;
                if(!revision.impact().sectionIds().isEmpty()) {
                    var trace=proposals.runs(dispatch.projectId(),dispatch.requirementId(),proposal.id(),dispatch.actor(),dispatch.context()).stream()
                            .filter(r->r.resultRevision()!=null && r.candidate()!=null && r.resultRevision()<=revision.number())
                            .max(java.util.Comparator.comparingLong(Run::resultRevision)).map(r->r.candidate().reconciliation()).orElse(null);
                    var before=new com.taxonomy.reformulation.ReformulationDocument(revision.text(),revision.sections(),revision.statements(),revision.questions(),revision.validation(),java.util.List.of(),trace);
                    reconciled=engine.synthesizeAffected(runBaseline,before,revision.answers(),revision.impact(),steps);
                } else {
                    var result=engine.synthesize(runBaseline,revision.statements(),revision.answers(),revision.questions(),steps);
                    reconciled=reconciler.reconcile(runBaseline,result,revision.answers(),revision.questions(),steps);
                }
                if (!local.retired()) recovery.finish(token,reconciled,null);
            }
        } catch(RuntimeException failure) {
            // An unclaimed delivery cannot fail another worker. Expired owners also
            // cannot publish failures because finish verifies the exact epoch again.
            if(token==null)throw failure;
            if (!local.retired()) recovery.finish(token,null,failureCode(failure));
        } finally {
            providers.clearRequestProvider();
            local.finished();
            scheduled.remove(dispatch.run().id(), local);
        }
    }
    private LlmTransportMeter.Journal usageJournal(Claim token, LocalExecution local) {
        return new LlmTransportMeter.Journal() {
            @Override public void started(LlmTransportMeter.Attempt attempt) {
                if (local.retired()) throw new IllegalStateException("REFORMULATION_EXECUTOR_STOPPED");
                usage.start(token, new ReformulationUsageService.Start(attempt.id(), attempt.invocationId(),
                        attempt.provider(), attempt.source().name(), attempt.retryIndex()));
                if (local.retired()) throw new IllegalStateException("REFORMULATION_EXECUTOR_STOPPED");
            }
            @Override public void completed(LlmTransportMeter.Attempt attempt, LlmTransportMeter.Observation result) {
                var values = result.usage();
                usage.complete(token, attempt.id(), new ReformulationUsageService.Completion(result.statusCode(), result.outcome().name(),
                        result.durationMillis(), values.inputTokens(), values.outputTokens(), values.totalTokens(),
                        values.cachedInputTokens(), values.reasoningTokens(), values.invalid()));
            }
        };
    }
    /** Legacy direct runs retain explicit cancel/retry; new dispatched runs use the lease-bound overload. */
    public ReformulationStepExecutor checkpoints(Long projectId,Long requirementId,String proposalId,Run run,String actor,WorkspaceContext context) {
        return checkpointExecutor(projectId,requirementId,proposalId,run,actor,context,null);
    }
    private ReformulationStepExecutor checkpointExecutor(Long projectId,Long requirementId,String proposalId,Run run,String actor,WorkspaceContext context,Claim token) {
        return checkpointExecutor(projectId, requirementId, proposalId, run, actor, context, token, () -> false);
    }
    private ReformulationStepExecutor checkpointExecutor(Long projectId,Long requirementId,String proposalId,Run run,String actor,WorkspaceContext context,Claim token,
            java.util.function.BooleanSupplier stopped) {
        var provider=LlmProvider.valueOf(run.provider());String endpoint=endpoint(provider);
        return ReformulationStepExecutor.of((kind,input,type,work)->{
            if (stopped.getAsBoolean()) throw new IllegalStateException("REFORMULATION_EXECUTOR_STOPPED");
            if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("CHECKPOINT_EXECUTION_INSIDE_TRANSACTION");
            if(!run.model().equals(model(provider)) || !endpoint.equals(endpoint(provider)))throw new IllegalStateException("MODEL_CONFIGURATION_CHANGED");
            String fingerprint=StableIdentityHash.sha256(checkpointJson.writeValueAsString(Map.ofEntries(
                    Map.entry("format","reformulation-step-v1"),Map.entry("provider",provider.name()),Map.entry("model",run.model()),
                    Map.entry("endpointHash",StableIdentityHash.sha256(endpoint)),Map.entry("prompt",run.promptContent()),
                    Map.entry("promptVersion",run.promptVersion()),Map.entry("schemaVersion",run.schemaVersion()),
                    Map.entry("reconciliation",run.reconcileContext()),Map.entry("resultType",type.getName()),Map.entry("input",input))));
            var cached=token==null?proposals.checkpoint(projectId,requirementId,proposalId,run.id(),kind,fingerprint,actor,context)
                    :recovery.checkpoint(token,kind,fingerprint);
            if(cached.isPresent())return checkpointJson.readValue(cached.get(),type);
            Object result=Objects.requireNonNull(work.get(),"Validated synthesis result is required");
            if (stopped.getAsBoolean()) throw new IllegalStateException("REFORMULATION_EXECUTOR_STOPPED");
            String payload=checkpointJson.writeValueAsString(result);
            String accepted=token==null?proposals.completeCheckpoint(projectId,requirementId,proposalId,run.id(),kind,fingerprint,payload,actor,context)
                    :recovery.completeCheckpoint(token,kind,fingerprint,payload);
            return checkpointJson.readValue(accepted,type);
        });
    }
    private String endpointHash(LlmProvider provider) {return StableIdentityHash.sha256(provider==LlmProvider.LOCAL_ONNX?"LOCAL_ONNX":endpoint(provider));}
    private String endpoint(LlmProvider provider) {return provider==LlmProvider.GEMINI?providers.getGeminiUrl():providers.getOpenAiCompatibleUrl(provider);}
    private String model(LlmProvider provider) {
        return provider==LlmProvider.GEMINI?java.net.URI.create(providers.getGeminiUrl()).getPath().replaceFirst(".*/models/", "").split(":")[0]
                :providers.getOpenAiCompatibleModel(provider);
    }
    private static String failureCode(RuntimeException failure) {
        if(failure instanceof LlmRateLimitException)return "PROVIDER_RATE_LIMIT";
        if(failure instanceof LlmTimeoutException)return "PROVIDER_TIMEOUT";
        if(failure instanceof LlmProviderException provider)return "PROVIDER_"+provider.getReason().name();
        if(failure instanceof IllegalArgumentException)return "INVALID_FROZEN_INPUT";
        String message=failure.getMessage();
        for(String code:new String[]{"INPUT_TOO_LARGE_FOR_PROVIDER","INVALID_MODEL_RESPONSE","PROVIDER_NOT_CONFIGURED","PROVIDER_TRANSPORT_FAILED","MODEL_CONFIGURATION_CHANGED","CHECKPOINT_EXECUTION_INSIDE_TRANSACTION","CHECKPOINT_RESULT_TOO_LARGE_OR_EMPTY","USAGE_RECORDING_UNAVAILABLE"})
            if(message!=null && message.startsWith(code))return code;
        return "SYNTHESIS_FAILED";
    }
}
