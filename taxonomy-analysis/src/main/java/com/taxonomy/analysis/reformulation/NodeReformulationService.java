package com.taxonomy.analysis.reformulation;

import com.taxonomy.analysis.service.*;
import com.taxonomy.reformulation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Uses the existing throttled, recording-aware, final-prompt-budgeted provider transport. */
@Service
public class NodeReformulationService {
    private final LlmGatewayRegistry registry;
    private final LlmProviderConfig config;
    private final ReformulationPromptBuilder prompts;
    private final ReformulationResponseParser parser;
    private final ObjectMapper json;
    public NodeReformulationService(LlmGatewayRegistry registry,LlmProviderConfig config,ObjectMapper json) {
        this.json=json;this.registry=registry;this.config=config;this.prompts=new ReformulationPromptBuilder(json);this.parser=new ReformulationResponseParser(json);
    }
    public NodeSynthesisResult synthesize(NodeSynthesisInput input) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("LLM_CALL_INSIDE_TRANSACTION");
        var provider=config.getActiveProvider();String key=config.getApiKey(provider);
        if(provider==LlmProvider.LOCAL_ONNX || !config.isProviderConfigured(provider) || config.isMockMode())
            throw new IllegalStateException("PROVIDER_NOT_CONFIGURED: "+java.util.Objects.toString(config.getProviderConfigurationError(provider),"Generative provider required"));
        var gateway=registry.getGateway(provider);String errors=null;
        for(int attempt=0;attempt<2;attempt++) {
            String raw;
            try {raw=gateway.sendHttpRequest(prompts.build(input,errors),key);}
            catch(PromptBudgetExceededException tooLarge) {throw new IllegalStateException("INPUT_TOO_LARGE_FOR_PROVIDER",tooLarge);}
            if(raw==null) throw new IllegalStateException("PROVIDER_TRANSPORT_FAILED");
            try {
                if(truncated(raw)) throw new IllegalArgumentException("Provider reports truncated or filtered output");
                return parser.parse(gateway.extractResponseText(raw),input);
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
