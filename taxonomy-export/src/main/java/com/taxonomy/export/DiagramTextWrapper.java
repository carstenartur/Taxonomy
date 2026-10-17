package com.taxonomy.export;

import java.util.ArrayList;
import java.util.List;

/** Deterministically wraps diagram labels without silently dropping the final visible line. */
public final class DiagramTextWrapper {

    private static final String ELLIPSIS = "...";
    private static final String DEFAULT_FALLBACK = "Unnamed architecture element";

    private DiagramTextWrapper() {
    }

    public static List<String> wrap(String value,
                                    int maximumCharacters,
                                    int maximumLines,
                                    String fallback) {
        if (maximumCharacters < 1) {
            throw new IllegalArgumentException("maximumCharacters must be positive");
        }
        if (maximumLines < 1) {
            throw new IllegalArgumentException("maximumLines must be positive");
        }

        String normalized = normalize(value, fallback);
        List<String> lines = new ArrayList<>(maximumLines);
        String remaining = normalized;

        while (!remaining.isEmpty() && lines.size() < maximumLines) {
            if (lines.size() == maximumLines - 1) {
                lines.add(ellipsize(remaining, maximumCharacters));
                break;
            }
            if (remaining.length() <= maximumCharacters) {
                lines.add(remaining);
                break;
            }

            int split = remaining.lastIndexOf(' ', maximumCharacters);
            if (split <= 0) {
                split = maximumCharacters;
            }
            String line = remaining.substring(0, split).strip();
            if (!line.isEmpty()) {
                lines.add(line);
            }
            remaining = remaining.substring(split).strip();
        }

        return List.copyOf(lines);
    }

    /** Measured code-point wrapping; SVG fixes textLength to these advances across font substitutions. */
    public static List<String> wrapMeasured(String value, java.awt.Font font, double width, int maximumLines, String fallback) {
        if (width <= 0 || !Double.isFinite(width) || maximumLines < 1) throw new IllegalArgumentException("Invalid text bounds");
        String remaining = normalize(value, fallback);
        var lines = new ArrayList<String>();
        while (!remaining.isEmpty() && lines.size() < maximumLines) {
            boolean last = lines.size() == maximumLines - 1;
            if (measure(remaining, font) <= width) { lines.add(remaining); break; }
            String suffix = last ? "..." : "";
            while (!suffix.isEmpty() && measure(suffix, font) > width) suffix = suffix.substring(0, suffix.length() - 1);
            int end = 0, next;
            while (end < remaining.length()) {
                next = end + Character.charCount(remaining.codePointAt(end));
                if (measure(remaining.substring(0, next) + suffix, font) > width) break;
                end = next;
            }
            if (last) { lines.add(remaining.substring(0, end).stripTrailing() + suffix); break; }
            if (end == 0) { lines.add(""); break; }
            int space = remaining.lastIndexOf(' ', end);
            if (space > 0) end = space;
            lines.add(remaining.substring(0, end).stripTrailing());
            remaining = remaining.substring(end).stripLeading();
        }
        return List.copyOf(lines);
    }

    public static double measure(String value, java.awt.Font font) {
        return font.getStringBounds(value, new java.awt.font.FontRenderContext(null, true, true)).getWidth();
    }

    private static String normalize(String value, String fallback) {
        String candidate = value == null || value.isBlank() ? fallback : value;
        if (candidate == null || candidate.isBlank()) {
            candidate = DEFAULT_FALLBACK;
        }
        return candidate.strip().replaceAll("\\s+", " ");
    }

    private static String ellipsize(String value, int maximumCharacters) {
        if (value.length() <= maximumCharacters) {
            return value;
        }
        if (maximumCharacters <= ELLIPSIS.length()) {
            return ELLIPSIS.substring(0, maximumCharacters);
        }
        String prefix = value.substring(0, maximumCharacters - ELLIPSIS.length()).stripTrailing();
        return prefix + ELLIPSIS;
    }
}
