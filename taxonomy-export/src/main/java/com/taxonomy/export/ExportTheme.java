package com.taxonomy.export;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import static com.taxonomy.export.DiagramPresentation.layer;

/**
 * Immutable export interpretation of the shared customer color tokens. This is
 * deliberately not a CSS engine: only literal opaque hexadecimal declarations
 * in top-level, unqualified {@code :root} blocks are portable to vector exports.
 */
public final class ExportTheme {
    private static final ExportTheme DEFAULT = new ExportTheme(Map.of());
    private static final Set<String> SURFACES = Set.of("navigation-background", "navigation-foreground",
            "surface-background", "surface-foreground", "panel-background", "panel-border",
            "canvas-background", "canvas-foreground");
    private static final Set<String> LAYERS = Set.of("cap", "proc", "svc", "app", "info", "comm", "system", "component", "default");
    private static final Set<String> RELATIONS = Set.of("supports", "realizes", "realized-by", "uses", "requires",
            "depends-on", "fulfills", "consumes", "assigned-to", "produces", "communicates-with", "contains", "related-to");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?");
    private final Map<String, String> colors;

    private ExportTheme(Map<String, String> colors) {
        this.colors = Map.copyOf(colors);
    }

    public static ExportTheme defaults() { return DEFAULT; }

    public static ExportTheme fromStylesheet(String css) {
        if (css == null || css.isBlank()) return DEFAULT;
        var values = new HashMap<String, String>();
        var priorities = new HashMap<String, Boolean>();
        StringBuilder clean = new StringBuilder(css.length());
        int depth = 0, parentheses = 0, brackets = 0, selectorStart = 0, bodyStart = -1;
        boolean root = false;
        char quote = 0;
        for (int index = 0; index < css.length(); index++) {
            char c = css.charAt(index);
            if (quote != 0) {
                clean.append(c);
                if (c == '\\' && index + 1 < css.length()) clean.append(css.charAt(++index));
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '/' && index + 1 < css.length() && css.charAt(index + 1) == '*') {
                int end = css.indexOf("*/", index + 2);
                if (end < 0) break;
                clean.append(' ');
                index = end + 1;
                continue;
            }
            if (c == '\\') {
                clean.append(c);
                if (index + 1 < css.length()) clean.append(css.charAt(++index));
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; clean.append(c); continue; }
            if (c == '(') parentheses++;
            else if (c == ')') parentheses = Math.max(0, parentheses - 1);
            else if (c == '[') brackets++;
            else if (c == ']') brackets = Math.max(0, brackets - 1);
            // A URL, function or attribute prelude cannot introduce a root rule.
            if (parentheses > 0 || brackets > 0) { clean.append(c); continue; }
            if (c == '{') {
                if (depth == 0) {
                    root = clean.substring(selectorStart).strip().equals(":root");
                    bodyStart = clean.length() + 1;
                } else root = false; // Nested rules are outside the supported subset.
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0) {
                    if (root) readDeclarations(clean.substring(bodyStart), values, priorities);
                    selectorStart = clean.length() + 1;
                    root = false;
                }
            } else if (c == ';' && depth == 0) selectorStart = clean.length() + 1;
            clean.append(c);
        }
        return values.isEmpty() ? DEFAULT : new ExportTheme(values);
    }

    private static void readDeclarations(String declarations, Map<String, String> values, Map<String, Boolean> priorities) {
        var entries = new ArrayList<String>();
        char quote = 0;
        int parentheses = 0, brackets = 0, start = 0;
        for (int index = 0; index < declarations.length(); index++) {
            char c = declarations.charAt(index);
            if (quote != 0) {
                if (c == '\\') index++;
                else if (c == quote) quote = 0;
            } else if (c == '\\') index++;
            else if (c == '\'' || c == '"') quote = c;
            else if (c == '(') parentheses++;
            else if (c == ')') parentheses = Math.max(0, parentheses - 1);
            else if (c == '[') brackets++;
            else if (c == ']') brackets = Math.max(0, brackets - 1);
            else if (c == ';' && parentheses == 0 && brackets == 0) {
                entries.add(declarations.substring(start, index));
                start = index + 1;
            }
        }
        if (quote == 0 && parentheses == 0 && brackets == 0) entries.add(declarations.substring(start));
        for (String declaration : entries) {
            int colon = declaration.indexOf(':');
            if (colon < 0) continue;
            String name = declaration.substring(0, colon).strip();
            if (!supported(name)) continue;
            String value = declaration.substring(colon + 1).strip();
            boolean important = value.matches("(?is).*!\\s*important\\s*$");
            if (important) value = value.replaceFirst("(?is)\\s*!\\s*important\\s*$", "").strip();
            if (!HEX.matcher(value).matches() || (priorities.getOrDefault(name, false) && !important)) continue;
            if (value.length() == 4) value = "#" + value.charAt(1) + value.charAt(1)
                    + value.charAt(2) + value.charAt(2) + value.charAt(3) + value.charAt(3);
            values.put(name, value.toUpperCase(Locale.ROOT));
            priorities.put(name, important);
        }
    }

    private static boolean supported(String name) {
        if (!name.startsWith("--taxonomy-")) return false;
        String key = name.substring("--taxonomy-".length());
        if (SURFACES.contains(key)) return true;
        if (key.startsWith("layer-") && key.endsWith("-surface")) {
            int start = "layer-".length(), end = key.length() - "-surface".length();
            return end > start && LAYERS.contains(key.substring(start, end));
        }
        return key.startsWith("relation-") && RELATIONS.contains(key.substring(9));
    }

    public String canvasBackground() { return color("canvas-background", "#ffffff"); }

    public String canvasText(String fallback) {
        String preferred = color("canvas-foreground", fallback);
        return has("canvas-background") || has("canvas-foreground") ? readable(canvasBackground(), preferred) : preferred;
    }

    public String nodeFill(String type) {
        return color("layer-" + layer(type) + "-surface", DiagramPresentation.fill(type));
    }

    public String nodeText(String type, String fallback) {
        return has("layer-" + layer(type) + "-surface") ? readable(nodeFill(type), fallback) : fallback;
    }

    public String nodeBorder(String type, boolean anchor) {
        return nodeBorder(type, anchor, anchor ? "#172554" : "#64748b", nodeFill(type));
    }

    /** Native formats can supply their existing stroke and actual rendered background. */
    public String nodeBorder(String type, boolean anchor, String fallback, String background) {
        String border = color(anchor ? "navigation-background" : "panel-border", fallback);
        return has("layer-" + layer(type) + "-surface") || !border.equals(fallback)
                ? contrasting(background, border, 3) : border;
    }

    public String relationshipStroke(String type, boolean proposed) {
        String stroke = relationshipStroke(type, proposed ? "#9333ea" : "#586174", canvasBackground());
        return has("canvas-background") ? contrasting(canvasBackground(), stroke, 3) : stroke;
    }

    public String relationshipStroke(String type, String fallback, String background) {
        String key = "relation-" + (type == null ? "" : type.toLowerCase(Locale.ROOT).replace('_', '-'));
        String stroke = color(key, fallback);
        return has(key) ? contrasting(background, stroke, 3) : stroke;
    }

    /** Self-contained, bounded HTML surface branding; no operator CSS is embedded. */
    public String htmlReportStyles() {
        if (SURFACES.stream().filter(key -> !key.startsWith("canvas-")).noneMatch(this::has)) return "";
        String paper = color("surface-background", "#FFFFFF");
        String ink = readable(paper, color("surface-foreground", "#26303A"));
        String panel = color("panel-background", paper);
        String panelInk = readable(panel, ink);
        String accent = color("navigation-background", "#0B1F33");
        String heading = readable(paper, accent);
        String accentInk = readable(accent, color("navigation-foreground", "#FFFFFF"));
        String border = color("panel-border", "#CDD5DC");
        return "<style data-taxonomy-export-theme>"
                + ":root{--navy:" + heading + ";--ink:" + ink + ";--muted:" + ink
                + ";--paper:" + paper + ";--line:" + border + "}"
                + "html,body{background:" + paper + ";color:" + ink + "}"
                + "a:link,a:visited{color:inherit;text-decoration:underline}"
                + ".small-label{color:" + ink + "}"
                + "h1,h2,h3,h4,strong,.eyebrow,.section-kicker,.chapter-number{color:" + heading + ";border-color:" + border + "}"
                + "th,.leading-leaf{background:" + accent + ";color:" + accentInk + "}"
                + "th strong,.leading-leaf strong,.leading-leaf .small-label{color:" + accentInk + "}"
                + "td,th,hr,pre{border-color:" + border + "}"
                + "tbody tr:nth-child(even),code,pre,.mermaid,.metadata-item,.requirement-card,.rationale-grid>div,.running-footer"
                + "{background:" + panel + ";color:" + panelInk + "}"
                + ".metadata-item span,.metadata-item strong,.requirement-card h2,.rationale-grid h3,.rationale-grid strong,.rationale-grid .muted{color:" + panelInk + "}"
                + ".title-page{background:" + paper + "}.subtitle,.parent-description,.classification-note{color:" + ink + "}"
                + ".muted,.row-rejected,.row-missing{color:inherit}td strong{color:inherit}"
                // Score, warning and leading-row backgrounds keep their semantic palette.
                + ".score-orb,.parent-score{color:#FFFFFF}.warning-box,.warning-box h3{color:#745400}"
                + ".row-leading,.row-leading td{color:#0B1F33}.row-leading code{background:#E1F4F3;color:#0B1F33}"
                + "</style>";
    }

    private boolean has(String key) { return colors.containsKey("--taxonomy-" + key); }
    private String color(String key, String fallback) { return colors.getOrDefault("--taxonomy-" + key, fallback); }

    private static String readable(String background, String preferred) { return contrasting(background, preferred, 4.5); }

    private static String contrasting(String background, String preferred, double minimum) {
        if (contrast(background, preferred) >= minimum) return preferred;
        if (contrast(background, "#FFFFFF") >= minimum) return "#FFFFFF";
        if (contrast(background, "#17212B") >= minimum) return "#17212B";
        return "#000000";
    }

    private static double contrast(String first, String second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }

    private static double luminance(String hex) {
        Color color = Color.decode(hex);
        return .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen()) + .0722 * linear(color.getBlue());
    }

    private static double linear(int value) {
        double channel = value / 255.0;
        return channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
    }
}
