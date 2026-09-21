package com.taxonomy.composition.reformulation;

import com.taxonomy.analysis.reformulation.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.portfolio.reformulation.*;
import com.taxonomy.portfolio.reformulation.ReformulationDtos.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

/** Explicit capture/dispatch boundary. Worker never reads ambient authentication/workspace state. */
@Service
public class ReformulationExecutionService {
    private final ReformulationService proposals;
    private final FrozenReformulationEngine engine;
    private final CrossTaxonomyReconciler reconciler;
    private final LlmProviderConfig providers;
    private final AsyncTaskExecutor executor;
    public ReformulationExecutionService(ReformulationService proposals,FrozenReformulationEngine engine,CrossTaxonomyReconciler reconciler,LlmProviderConfig providers,
            @Qualifier("portfolioAnalysisExecutor") AsyncTaskExecutor executor) {
        this.proposals=proposals;this.engine=engine;this.reconciler=reconciler;this.providers=providers;this.executor=executor;
    }
    public Run start(Long projectId,Long requirementId,String proposalId,long expectedRevision,String actor,WorkspaceContext context) {
        var proposal=proposals.get(projectId,requirementId,proposalId,actor,context);
        if(proposal.currentRevision().number()!=expectedRevision) throw new ReformulationPreconditionException();
        var provider=providers.getActiveProvider();
        String model=provider==LlmProvider.GEMINI?java.net.URI.create(providers.getGeminiUrl()).getPath().replaceFirst(".*/models/","").split(":")[0]
                :provider==LlmProvider.LOCAL_ONNX?"LOCAL_ONNX":providers.getOpenAiCompatibleModel(provider);
        var frozen=proposal.baseline().frozenContext();
        String prompt=frozen.getOrDefault("reformulationPrompt",ReformulationPromptBuilder.template());
        var run=proposals.beginRun(projectId,requirementId,proposalId,expectedRevision,provider.name(),model,
                frozen.getOrDefault("reformulationPromptVersion",ReformulationPromptBuilder.PROMPT_VERSION),
                frozen.getOrDefault("reformulationSchemaVersion",ReformulationPromptBuilder.SCHEMA_VERSION),prompt,ReconcilePromptBuilder.freeze(frozen),actor,context);
        String configurationError=providers.getProviderConfigurationError(provider);
        if(provider==LlmProvider.LOCAL_ONNX || !providers.isProviderConfigured(provider) || configurationError!=null || providers.isMockMode()) {
            proposals.finishRun(projectId,requirementId,proposalId,run.id(),null,"PROVIDER_NOT_CONFIGURED",actor,context);
        } else {
            try {executor.execute(()->execute(projectId,requirementId,proposal,run,provider,actor,context));}
            catch(org.springframework.core.task.TaskRejectedException rejected) {
                proposals.finishRun(projectId,requirementId,proposalId,run.id(),null,"DISPATCH_CAPACITY_EXHAUSTED",actor,context);
            }
        }
        return proposals.runs(projectId,requirementId,proposalId,actor,context).stream().filter(r->r.id().equals(run.id())).findFirst().orElseThrow();
    }
    private void execute(Long projectId,Long requirementId,Proposal proposal,Run run,LlmProvider provider,String actor,WorkspaceContext context) {
        providers.setRequestProvider(provider);
        try {
            proposals.running(projectId,requirementId,proposal.id(),run.id(),actor,context);
            var baseline=proposal.baseline();var captured=new java.util.TreeMap<>(baseline.frozenContext());
            captured.put("reformulationPrompt",run.promptContent());
            captured.putAll(run.reconcileContext());
            captured.put("reformulationPromptVersion",run.promptVersion());captured.put("reformulationSchemaVersion",run.schemaVersion());
            var runBaseline=new com.taxonomy.reformulation.ReformulationBaseline(baseline.scope(),baseline.sourceVersionId(),baseline.originalText(),
                    baseline.originalTextHash(),baseline.snapshotId(),baseline.snapshotPayload(),captured,baseline.language(),baseline.algorithmVersion());
            var revision=proposal.currentRevision();
            com.taxonomy.reformulation.ReformulationDocument reconciled;
            if(!revision.impact().sectionIds().isEmpty()) {
                var trace=proposals.runs(projectId,requirementId,proposal.id(),actor,context).stream()
                        .filter(r->r.resultRevision()!=null && r.candidate()!=null && r.resultRevision()<=revision.number())
                        .max(java.util.Comparator.comparingLong(Run::resultRevision)).map(r->r.candidate().reconciliation()).orElse(null);
                var before=new com.taxonomy.reformulation.ReformulationDocument(revision.text(),revision.sections(),revision.statements(),revision.questions(),revision.validation(),java.util.List.of(),trace);
                reconciled=engine.synthesizeAffected(runBaseline,before,revision.answers(),revision.impact());
            } else {
                var result=engine.synthesize(runBaseline,revision.statements(),revision.answers(),revision.questions());
                reconciled=reconciler.reconcile(runBaseline,result,revision.answers(),revision.questions());
            }
            proposals.finishRun(projectId,requirementId,proposal.id(),run.id(),reconciled,null,actor,context);
        } catch(RuntimeException failure) {
            proposals.finishRun(projectId,requirementId,proposal.id(),run.id(),null,failureCode(failure),actor,context);
        } finally {providers.clearRequestProvider();}
    }
    private static String failureCode(RuntimeException failure) {
        if(failure instanceof LlmRateLimitException)return "PROVIDER_RATE_LIMIT";
        if(failure instanceof LlmTimeoutException)return "PROVIDER_TIMEOUT";
        if(failure instanceof LlmProviderException provider)return "PROVIDER_"+provider.getReason().name();
        if(failure instanceof IllegalArgumentException)return "INVALID_FROZEN_INPUT";
        String message=failure.getMessage();
        for(String code:new String[]{"INPUT_TOO_LARGE_FOR_PROVIDER","INVALID_MODEL_RESPONSE","PROVIDER_NOT_CONFIGURED","PROVIDER_TRANSPORT_FAILED"})
            if(message!=null && message.startsWith(code))return code;
        return "SYNTHESIS_FAILED";
    }
}
