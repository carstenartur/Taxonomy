package com.taxonomy.analysis.usecase;

import com.taxonomy.extension.api.llm.ProviderId;

import com.taxonomy.analysis.service.AiPromptBudgetPolicy;
import com.taxonomy.analysis.service.AnalysisEventCallback;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.ProductCoverageGap;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class StreamRequirementAnalysisUseCase {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.taxonomy.analysis.cluster.ClusterAnalysisExecution clusterExecution;

    private final LlmService llmService;
    private final AiPromptBudgetPolicy promptBudgetPolicy;

    public StreamRequirementAnalysisUseCase(
            LlmService llmService,
            AiPromptBudgetPolicy promptBudgetPolicy) {
        this.llmService = llmService;
        this.promptBudgetPolicy = promptBudgetPolicy;
    }

    public void stream(StreamRequirementAnalysisCommand command, AnalysisStreamEventHandler handler) {
        if (clusterExecution != null) {
            throw new IllegalStateException("Cluster streaming requires an exact workspace operation context");
        }
        long startedNanos = System.nanoTime();
        var previousLocale = LocaleContextHolder.getLocaleContext();
        var reasonLength = command.analysisReasonLength() != null ? command.analysisReasonLength()
                : llmService.resolveAnalysisReasonLength(null);
        var language = com.taxonomy.analysis.service.AnalysisLanguageContext.resolve(command.analysisLanguage(), command.requestLocale());
        try (var reasonScope = com.taxonomy.analysis.service.AnalysisReasonContext.open(reasonLength);
             var languageScope = com.taxonomy.analysis.service.AnalysisLanguageContext.open(language);
             var mockScope = "MOCK".equalsIgnoreCase(command.provider())
                ? llmService.withProviderBinding("MOCK", null) : null) {
            applyRequestLocale(command.requestLocale());
            llmService.requireProviderAdmissible(command.provider(), false);
            applyProviderOverride(command.provider());
            promptBudgetPolicy.requireWithinBudget(
                    command.businessText(), command.provider());
            AnalysisEventCallback callback = new AnalysisEventCallback() {
                @Override
                public void onPhase(String message, int progressPercent) {
                    handler.handle(new AnalysisStreamEvent.Phase(message, progressPercent));
                }

                @Override
                public void onScores(Map<String, Double> newScores, Map<String, String> reasons,
                                     String description, LlmCallDetail detail) {
                    handler.handle(new AnalysisStreamEvent.Scores(newScores, reasons, description, detail));
                }

                @Override
                public void onExpanding(String parentCode, List<String> childCodes) {
                    handler.handle(new AnalysisStreamEvent.Expanding(parentCode, childCodes));
                }

                @Override
                public void onComplete(String status, Map<String, Double> allScores,
                                       List<String> warnings,
                                       List<TaxonomyDiscrepancy> discrepancies,
                                       List<ProductCoverageGap> productCoverageGaps) {
                    onComplete(status, allScores, warnings, discrepancies, productCoverageGaps, null);
                }

                @Override
                public void onComplete(String status, Map<String, Double> allScores,
                                       List<String> warnings,
                                       List<TaxonomyDiscrepancy> discrepancies,
                                       List<ProductCoverageGap> productCoverageGaps,
                                       com.taxonomy.dto.AnalysisCoverage coverage) {
                    handler.handle(new AnalysisStreamEvent.Complete(
                            status, allScores, warnings, discrepancies, productCoverageGaps, elapsedMillis(startedNanos), coverage));
                }

                @Override
                public void onError(String status, String errorMessage,
                                    Map<String, Double> partialScores,
                                    List<String> warnings,
                                    List<TaxonomyDiscrepancy> discrepancies,
                                    List<ProductCoverageGap> productCoverageGaps) {
                    handler.handle(new AnalysisStreamEvent.Error(
                            status, errorMessage, partialScores, warnings, discrepancies,
                            productCoverageGaps, Map.of(), elapsedMillis(startedNanos)));
                }
                @Override
                public void onError(String status, String errorMessage,
                                    Map<String, Double> partialScores, Map<String, String> partialReasons,
                                    List<String> warnings, List<TaxonomyDiscrepancy> discrepancies,
                                    List<ProductCoverageGap> productCoverageGaps) {
                    onError(status, errorMessage, partialScores, partialReasons, warnings, discrepancies, productCoverageGaps, null);
                }

                @Override
                public void onError(String status, String errorMessage,
                                    Map<String, Double> partialScores, Map<String, String> partialReasons,
                                    List<String> warnings, List<TaxonomyDiscrepancy> discrepancies,
                                    List<ProductCoverageGap> productCoverageGaps,
                                    com.taxonomy.dto.AnalysisCoverage coverage) {
                    handler.handle(new AnalysisStreamEvent.Error(
                            status, errorMessage, partialScores, warnings, discrepancies,
                            productCoverageGaps, partialReasons, elapsedMillis(startedNanos), coverage));
                }
            };
            if (command.analysisScope().legacyFull()) llmService.analyzeStreaming(command.businessText(), callback);
            else llmService.analyzeStreaming(command.businessText(), command.analysisScope(), callback);
        } finally {
            try {
                llmService.clearRequestProvider();
            } finally {
                LocaleContextHolder.setLocaleContext(previousLocale);
            }
        }
    }

    /** Observes the durable operation; a detached handler never cancels that operation. */
    public void stream(StreamRequirementAnalysisCommand command,
                       com.taxonomy.analysis.dag.AnalysisOperationContext context,
                       AnalyzeRequirementCommand analyzeCommand, com.taxonomy.dto.ViewContext view,
                       AnalysisStreamEventHandler handler) {
        if (clusterExecution == null) throw new IllegalStateException("Cluster execution is not configured");
        if (!command.businessText().equals(analyzeCommand.businessText())
                || !java.util.Objects.equals(command.provider(), analyzeCommand.provider())
                || !command.analysisScope().equals(analyzeCommand.analysisScope())
                || command.analysisReasonLength() != null && analyzeCommand.analysisReasonLength() != null
                    && command.analysisReasonLength() != analyzeCommand.analysisReasonLength()
                || command.analysisLanguage() != null && (analyzeCommand.analysisLanguage() != null
                    || analyzeCommand.continuationBinding() != null || analyzeCommand.provenance() != null)
                    && command.analysisLanguage() != analyzeCommand.analysisLanguage()) {
            throw new IllegalArgumentException("Streaming request does not match durable analysis input");
        }
        var reasonLength = analyzeCommand.analysisReasonLength() != null ? analyzeCommand.analysisReasonLength()
                : command.analysisReasonLength() != null ? command.analysisReasonLength()
                : com.taxonomy.analysis.service.AnalysisReasonContext.effective(llmService.resolveAnalysisReasonLength(null));
        var language = analyzeCommand.analysisLanguage() != null ? analyzeCommand.analysisLanguage()
                : analyzeCommand.continuationBinding() != null || analyzeCommand.provenance() != null ? null
                : com.taxonomy.analysis.service.AnalysisLanguageContext.resolve(command.analysisLanguage(), command.requestLocale());
        var admitted = analyzeCommand.withAnalysisReasonLength(reasonLength).withAnalysisLanguage(language);
        var previousLocale = LocaleContextHolder.getLocaleContext();
        try {
            applyRequestLocale(command.requestLocale());
            promptBudgetPolicy.requireWithinBudget(command.businessText(), command.provider());
            clusterExecution.execute(context, admitted, view,
                    snapshot -> handler.handle(new AnalysisStreamEvent.DurableSnapshot(snapshot)));
        } finally {
            LocaleContextHolder.setLocaleContext(previousLocale);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private void applyRequestLocale(Locale requestLocale) {
        if (requestLocale != null) {
            LocaleContextHolder.setLocale(requestLocale);
        } else {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    private void applyProviderOverride(String provider) {
        if (provider == null || provider.isBlank()
                || "MOCK".equalsIgnoreCase(provider)) {
            return;
        }
        try {
            llmService.setRequestProviderId(new ProviderId(provider));
        } catch (IllegalArgumentException e) {
            throw new UnknownAnalysisProviderException(provider);
        }
    }
}
