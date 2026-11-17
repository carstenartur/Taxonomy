package com.taxonomy.visio.converter;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

/** Localized display labels; the native Property row names identify the canonical keys. */
final class VisioPropertyLabels {
    private static final ResourceBundle EN = ResourceBundle.getBundle("visio.shape-data", Locale.ROOT);
    private static final ResourceBundle DE = ResourceBundle.getBundle("visio.shape-data", Locale.GERMAN);
    private static final Pattern EVIDENCE = Pattern.compile("evidence([0-9]+)([a-zA-Z]+)");

    private VisioPropertyLabels() { }

    static String label(String key, boolean german) {
        var labels = german ? DE : EN;
        String field = key.substring("taxonomy.".length());
        if (labels.containsKey(field)) return labels.getString(field);
        var evidence = EVIDENCE.matcher(field);
        if (evidence.matches() && labels.containsKey(evidence.group(2))) {
            return labels.getString("evidence") + " " + evidence.group(1) + " · " + labels.getString(evidence.group(2));
        }
        return key;
    }
}
