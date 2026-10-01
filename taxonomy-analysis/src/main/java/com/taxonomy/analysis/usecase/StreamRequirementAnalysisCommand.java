package com.taxonomy.analysis.usecase;

import java.util.Locale;
import com.taxonomy.dto.AnalysisScope;

public record StreamRequirementAnalysisCommand(
        String businessText,
        String provider,
        Locale requestLocale,
        AnalysisScope analysisScope) {
    public StreamRequirementAnalysisCommand {
        analysisScope = AnalysisScope.orDefault(analysisScope);
    }
    public StreamRequirementAnalysisCommand(String businessText, String provider, Locale requestLocale) {
        this(businessText, provider, requestLocale, AnalysisScope.full());
    }
}
