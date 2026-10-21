package com.taxonomy.reporting.render.decision;

import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.extension.api.report.ReportRenderContext;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

/** Document labels are independently selectable; saved explanation text is never translated. */
public final class DecisionReportLanguage {
    private DecisionReportLanguage() { }

    /** Null means no explicit override, allowing the frozen analysis language to win. */
    public static Locale optionalOverride(String language) {
        if (language == null || language.isBlank()) return null;
        Locale locale = Locale.forLanguageTag(language.strip());
        return locale.getLanguage().isBlank() ? null : locale;
    }

    public static Locale resolve(Locale override, AnalysisLanguage recorded) {
        if (override != null) return override;
        return recorded == null ? LocaleContextHolder.getLocale() : Locale.forLanguageTag(recorded.value());
    }

    public static String rendererLanguage(ReportRenderContext context) {
        Object language = context.options().get("language");
        Locale override = language instanceof String tag ? optionalOverride(tag) : null;
        return override == null ? "en" : override.toLanguageTag();
    }

    /** Original captured explanations; language selection affects labels only. */
    public static Map<String, String> rendererReasons(ReportRenderContext context) {
        var reasons = new LinkedHashMap<String, String>();
        if (context.options().get("nodeReasons") instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                if (key instanceof String code && value instanceof String reason && !reason.isBlank())
                    reasons.put(code, reason);
            });
        }
        return Map.copyOf(reasons);
    }
}
