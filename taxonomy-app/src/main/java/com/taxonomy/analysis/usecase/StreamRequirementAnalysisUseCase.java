package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.service.AnalysisEventCallback;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class StreamRequirementAnalysisUseCase {

    private final LlmService llmService;

    public StreamRequirementAnalysisUseCase(LlmService llmService) {
        this.llmService = llmService;
    }

    public void stream(StreamRequirementAnalysisCommand command, AnalysisStreamEventHandler handler) {
        try {
            applyProviderOverride(command.provider());
            llmService.analyzeStreaming(command.businessText(), new AnalysisEventCallback() {
                @Override
                public void onPhase(String message, int progressPercent) {
                    handler.handle(new AnalysisStreamEvent.Phase(message, progressPercent));
                }

                @Override
                public void onScores(Map<String, Integer> newScores, Map<String, String> reasons,
                                     String description, LlmCallDetail detail) {
                    handler.handle(new AnalysisStreamEvent.Scores(newScores, reasons, description, detail));
                }

                @Override
                public void onExpanding(String parentCode, List<String> childCodes) {
                    handler.handle(new AnalysisStreamEvent.Expanding(parentCode, childCodes));
                }

                @Override
                public void onComplete(String status, Map<String, Integer> allScores,
                                       List<String> warnings,
                                       List<TaxonomyDiscrepancy> discrepancies) {
                    handler.handle(new AnalysisStreamEvent.Complete(status, allScores, warnings, discrepancies));
                }

                @Override
                public void onError(String status, String errorMessage,
                                    Map<String, Integer> partialScores,
                                    List<String> warnings,
                                    List<TaxonomyDiscrepancy> discrepancies) {
                    handler.handle(new AnalysisStreamEvent.Error(
                            status, errorMessage, partialScores, warnings, discrepancies));
                }
            });
        } finally {
            llmService.clearRequestProvider();
        }
    }

    private void applyProviderOverride(String provider) {
        if (provider == null || provider.isBlank()) {
            return;
        }
        try {
            llmService.setRequestProvider(LlmProvider.valueOf(provider.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new UnknownAnalysisProviderException(provider);
        }
    }
}
