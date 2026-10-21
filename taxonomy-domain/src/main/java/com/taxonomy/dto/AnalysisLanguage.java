package com.taxonomy.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Language of generated analysis prose, captured once when a new operation is admitted. */
public enum AnalysisLanguage {
    GERMAN("de", "German"),
    ENGLISH("en", "English");

    private final String value;
    private final String displayName;

    AnalysisLanguage(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    @JsonValue public String value() { return value; }
    public String displayName() { return displayName; }

    /** A request without a supported German locale uses the product's English default. */
    public static AnalysisLanguage fromLocale(Locale locale) {
        return locale != null && "de".equals(locale.getLanguage()) ? GERMAN : ENGLISH;
    }

    /** Absence is unknown historical provenance, never an inferred or backfilled language. */
    @JsonCreator
    public static AnalysisLanguage fromValue(String value) {
        if (value == null) return null;
        for (var language : values()) if (language.value.equals(value)) return language;
        throw new IllegalArgumentException("Analysis language must be de or en");
    }
}
