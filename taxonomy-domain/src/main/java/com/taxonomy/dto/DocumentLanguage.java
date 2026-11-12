package com.taxonomy.dto;

import java.util.Locale;

/** Selects export labels without changing the language of stored analysis evidence. */
public final class DocumentLanguage {
    private DocumentLanguage() { }

    public static Locale optionalOverride(String language) {
        if (language == null || language.isBlank()) return null;
        Locale locale = Locale.forLanguageTag(language.strip());
        return locale.getLanguage().isBlank() ? null : locale;
    }

    public static Locale resolve(Locale override, AnalysisLanguage recorded, Locale requestLocale) {
        if (override != null) return override;
        return recorded == null ? requestLocale : Locale.forLanguageTag(recorded.value());
    }
}
