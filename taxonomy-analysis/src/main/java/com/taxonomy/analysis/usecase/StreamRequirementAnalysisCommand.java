package com.taxonomy.analysis.usecase;

import java.util.Locale;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.AnalysisReasonLength;

public record StreamRequirementAnalysisCommand(
        String businessText,
        String provider,
        Locale requestLocale,
        AnalysisScope analysisScope,
        AnalysisReasonLength analysisReasonLength,
        com.taxonomy.dto.AnalysisLanguage analysisLanguage) {
    public StreamRequirementAnalysisCommand {
        analysisScope = AnalysisScope.orDefault(analysisScope);
    }
    public StreamRequirementAnalysisCommand(String businessText, String provider, Locale requestLocale,
                                            AnalysisScope analysisScope, AnalysisReasonLength analysisReasonLength) {
        this(businessText, provider, requestLocale, analysisScope, analysisReasonLength, null);
    }
    public StreamRequirementAnalysisCommand(String businessText, String provider, Locale requestLocale,
                                            AnalysisScope analysisScope) {
        this(businessText, provider, requestLocale, analysisScope, null);
    }
    public StreamRequirementAnalysisCommand(String businessText, String provider, Locale requestLocale) {
        this(businessText, provider, requestLocale, AnalysisScope.full());
    }
}
