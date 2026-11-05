package com.taxonomy.composition.analysis;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.shared.service.AiStatusSource;
import com.taxonomy.dto.AiAvailabilityLevel;
import com.taxonomy.dto.AiStatusResponse;
import com.taxonomy.shared.features.ConditionalOnFeature;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnFeature("analysis")
public final class AnalysisAiStatusSource implements AiStatusSource {
    private final LlmService llm;
    public AnalysisAiStatusSource(LlmService llm) { this.llm = llm; }

    @Override public AiStatusResponse snapshot() { return llm.getAiStatus(); }

    @Override public AiStatusResponse snapshot(String provider) { return llm.getAiStatus(provider); }

    @Override public AiAvailabilityLevel getAvailabilityLevel() { return snapshot().getLevel(); }
    @Override public String getActiveProviderName() { return llm.getActiveProviderName(); }
}
