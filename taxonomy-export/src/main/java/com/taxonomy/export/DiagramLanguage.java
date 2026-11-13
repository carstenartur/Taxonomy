package com.taxonomy.export;

import java.util.Locale;

/** Language of labels in a standalone diagram. Captured node and relationship evidence stays verbatim. */
enum DiagramLanguage {
    ENGLISH, GERMAN;

    static DiagramLanguage from(Locale locale) {
        return locale != null && "de".equals(locale.getLanguage()) ? GERMAN : ENGLISH;
    }

    boolean german() {
        return this == GERMAN;
    }

    String text(String english, String german) {
        return german() ? german : english;
    }

    String tag() {
        return german() ? "de" : "en";
    }

    String percent(double relevance) {
        String value = DiagramPresentation.percent(relevance);
        return german() ? value.replace('.', ',') : value;
    }
}
