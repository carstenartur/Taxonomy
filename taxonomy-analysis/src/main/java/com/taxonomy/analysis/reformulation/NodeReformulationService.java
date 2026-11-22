package com.taxonomy.analysis.reformulation;

import com.taxonomy.analysis.service.*;
import com.taxonomy.reformulation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Uses the existing throttled, recording-aware, final-prompt-budgeted provider transport. */
@Service
public class NodeReformulationService {
    private static final int RECONCILE_REPAIR_MESSAGE_LIMIT = 256;
    private final LlmGatewayRegistry registry;
    private final LlmProviderConfig config;
    private final ReformulationPromptBuilder prompts;
    private final ReformulationResponseParser parser;
    private final ObjectMapper json;
    public NodeReformulationService(LlmGatewayRegistry registry,LlmProviderConfig config,ObjectMapper json) {
        this.json=json;this.registry=registry;this.config=config;this.prompts=new ReformulationPromptBuilder(json);this.parser=new ReformulationResponseParser(json);
    }
    /** Prepare on the run thread; execute only on dedicated child threads and always clear their override. */
    <T> java.util.function.Supplier<T> captureProvider(java.util.function.Supplier<T> work) {
        var provider = java.util.Objects.requireNonNull(config.getActiveProviderId(), "Missing captured provider");
        var observedWork = LlmTransportMeter.capture(work);
        return () -> {
            config.setRequestProviderId(provider);
            try { return observedWork.get(); }
            finally { config.clearRequestProvider(); }
        };
    }
    public NodeSynthesisResult synthesize(NodeSynthesisInput input) {
        return synthesize(input, ReformulationStepExecutor.direct());
    }
    public NodeSynthesisResult synthesize(NodeSynthesisInput input, ReformulationStepExecutor steps) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("LLM_CALL_INSIDE_TRANSACTION");
        var provider = config.getActiveProviderId();
        if (provider.equals(LlmProvider.LOCAL_ONNX.id()) || !config.isProviderConfigured(provider) || config.isMockMode())
            throw new IllegalStateException("PROVIDER_NOT_CONFIGURED: Generative provider required");
        var gateway = registry.getGatewayById(provider);
        java.util.function.Predicate<NodeSynthesisInput> fits = candidate -> {
            try { gateway.validatePromptBudget(prompts.build(candidate, null)); return true; }
            catch (PromptBudgetExceededException tooLarge) { return false; }
        };
        if (fits.test(input)) return single(input);
        var grouping = new BoundedNodeSynthesis(json);
        var plan = grouping.partition(input, fits); // Check every group before any provider call.
        var results = new java.util.ArrayList<NodeSynthesisResult>();
        for (var group : plan)
            results.add(steps.execute("NODE_GROUP", group, NodeSynthesisResult.class, () -> single(group)));
        var aggregate = grouping.aggregate(input, results);
        if (!fits.test(aggregate)) throw new IllegalStateException("INPUT_TOO_LARGE_FOR_PROVIDER: complete aggregate decisions exceed budget");
        var summary = steps.execute("NODE_AGGREGATE", aggregate, NodeSynthesisResult.class, () -> single(aggregate));
        return grouping.combine(input, results, summary);
    }
    private NodeSynthesisResult single(NodeSynthesisInput input) {
        return call(errors->prompts.build(input,errors),raw->parser.parse(raw,input));
    }
    public ReconciliationResult reconcile(ReconciliationInput input) {
        var builder=new ReconcilePromptBuilder(json);var responses=new ReconcileResponseParser(json);
        return call(errors->builder.build(input,errors),raw->responses.parse(raw,input));
    }
    List<ReconciliationResult> reconcileBounded(ReconciliationInput input, ReformulationStepExecutor steps) {
        var builder = new ReconcilePromptBuilder(json);
        var responses = new ReconcileResponseParser(json);
        var gateway = registry.getGatewayById(config.getActiveProviderId());
        // A control character requires six JSON bytes. Reserve enough for the longest
        // permitted repair message, as well as its label, before admitting every view.
        String repairReserve = "\u0000".repeat(RECONCILE_REPAIR_MESSAGE_LIMIT);
        var plan = new BoundedReconciliation(json).plan(input, view -> {
            try {
                gateway.validatePromptBudget(builder.build(view, repairReserve));
                return true;
            } catch (PromptBudgetExceededException tooLarge) {
                return false;
            }
        });
        var reviews = new ArrayList<ReconciliationResult>();
        for (var view : plan.views()) {
            reviews.add(steps.execute("RECONCILE", view, ReconciliationResult.class, () ->
                    call(errors -> builder.build(view, boundedRepairMessage(errors)), raw -> responses.parse(raw, view.input()))));
        }
        reviews.add(new ReconciliationResult(List.of(), java.util.Map.of(), List.of(plan.coverageFinding())));
        return List.copyOf(reviews);
    }
    private static String boundedRepairMessage(String error) {
        return error == null || error.length() <= RECONCILE_REPAIR_MESSAGE_LIMIT ? error
                : "The previous response did not conform to the schema. Check all fields, references and evidence spans.";
    }
    private <T> T call(java.util.function.Function<String,String> prompt,java.util.function.Function<String,T> response) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("LLM_CALL_INSIDE_TRANSACTION");
        var provider=config.getActiveProviderId();String key=config.getApiKey(provider);
        if(provider.equals(LlmProvider.LOCAL_ONNX.id()) || !config.isProviderConfigured(provider) || config.isMockMode())
            throw new IllegalStateException("PROVIDER_NOT_CONFIGURED: "+java.util.Objects.toString(config.getProviderConfigurationError(provider),"Generative provider required"));
        var gateway=registry.getGatewayById(provider);String errors=null;
        for(int attempt=0;attempt<2;attempt++) {
            String raw;
            try {raw=gateway.sendHttpRequest(prompt.apply(errors),key);}
            catch(PromptBudgetExceededException tooLarge) {throw new IllegalStateException("INPUT_TOO_LARGE_FOR_PROVIDER",tooLarge);}
            if(raw==null) throw new IllegalStateException("PROVIDER_TRANSPORT_FAILED");
            try {
                if(truncated(raw)) throw new IllegalArgumentException("Provider reports truncated or filtered output");
                return response.apply(gateway.extractResponseText(raw));
            }
            catch(IllegalArgumentException invalid) { errors=invalid.getMessage(); }
        }
        throw new IllegalStateException("INVALID_MODEL_RESPONSE: bounded repair exhausted");
    }
    private boolean truncated(String raw) {
        tools.jackson.databind.JsonNode envelope;
        try {envelope=json.readTree(raw);} catch(RuntimeException invalid) {return false;}
        String openAi=envelope.at("/choices/0/finish_reason").asText();
        String gemini=envelope.at("/candidates/0/finishReason").asText();
        return "length".equals(openAi) || "content_filter".equals(openAi) || "MAX_TOKENS".equals(gemini)
                || "SAFETY".equals(gemini) || "RECITATION".equals(gemini);
    }

}
