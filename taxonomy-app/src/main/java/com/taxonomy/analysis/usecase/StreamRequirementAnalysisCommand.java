package com.taxonomy.analysis.usecase;

import java.util.Locale;

public record StreamRequirementAnalysisCommand(
        String businessText,
        String provider,
        Locale requestLocale) {
}
