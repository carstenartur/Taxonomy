package com.taxonomy.analysis.usecase;

import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.AnalysisReasonLength;
import com.taxonomy.workspace.service.WorkspaceContext;

import java.util.Locale;

public record StreamRequirementAnalysisCommand(
        String businessText,
        String provider,
        Locale requestLocale,
        AnalysisScope analysisScope,
        AnalysisReasonLength analysisReasonLength,
        AnalysisLanguage analysisLanguage,
        AnalysisModelBinding modelBinding, WorkspaceContext workspace) {
    public StreamRequirementAnalysisCommand {
        analysisScope = AnalysisScope.orDefault(analysisScope);
    }
    public StreamRequirementAnalysisCommand(String businessText, String provider, Locale requestLocale,
                                            AnalysisScope analysisScope, AnalysisReasonLength analysisReasonLength,
                                            AnalysisLanguage analysisLanguage) {
        this(businessText, provider, requestLocale, analysisScope, analysisReasonLength, analysisLanguage, null, null);
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
