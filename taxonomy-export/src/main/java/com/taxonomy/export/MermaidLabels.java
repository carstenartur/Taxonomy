package com.taxonomy.export;

import java.util.Map;

/**
 * Locale-dependent labels for Mermaid diagram rendering.
 *
 * <p>Provides human-readable, localized display text for layer titles,
 * relation types, anchor/hotspot markers, and legend text.
 * The Mermaid graph itself uses stable internal identifiers (node IDs,
 * classDef names); only rendered labels are localized.</p>
 *
 * @param layerLabels   maps internal layer type (e.g. "Capabilities") → localized display label
 * @param relationLabels maps internal relation type (e.g. "REALIZES") → localized display label
 * @param anchorMarker  marker symbol for anchor nodes (e.g. "★")
 * @param hotspotMarker marker symbol for hotspot nodes (e.g. "⚠")
 */
public record MermaidLabels(
        Map<String, String> layerLabels,
        Map<String, String> relationLabels,
        String anchorMarker,
        String hotspotMarker
) {

    /** Hotspot threshold: nodes with relevance ≥ this value are marked as hotspots. */
    public static final double HOTSPOT_THRESHOLD = 0.80;

    /**
     * Returns the default English labels.
     */
    public static MermaidLabels english() {
        return new MermaidLabels(
                Map.ofEntries(
                        Map.entry("Capabilities", "Capabilities"),
                        Map.entry("Business Processes", "Business Processes"),
                        Map.entry("Business Roles", "Business Roles"),
                        Map.entry("Services", "Services"),
                        Map.entry("COI Services", "COI Services"),
                        Map.entry("Core Services", "Core Services"),
                        Map.entry("Applications", "Applications"),
                        Map.entry("User Applications", "User Applications"),
                        Map.entry("Information Products", "Information Products"),
                        Map.entry("Communications Services", "Communications Services")
                ),
                Map.ofEntries(
                        Map.entry("REALIZES", "realizes"),
                        Map.entry("SUPPORTS", "supports"),
                        Map.entry("USES", "uses"),
                        Map.entry("FULFILLS", "fulfills"),
                        Map.entry("DEPENDS_ON", "depends on"),
                        Map.entry("PRODUCES", "produces"),
                        Map.entry("CONSUMES", "consumes"),
                        Map.entry("ASSIGNED_TO", "assigned to"),
                        Map.entry("RELATED_TO", "related to"),
                        Map.entry("COMMUNICATES_WITH", "communicates with")
                ),
                "★",
                "\u26A0"
        );
    }

    /**
     * Returns German labels.
     */
    public static MermaidLabels german() {
        return new MermaidLabels(
                Map.ofEntries(
                        Map.entry("Capabilities", "F\u00e4higkeiten"),
                        Map.entry("Business Processes", "Gesch\u00e4ftsprozesse"),
                        Map.entry("Business Roles", "Gesch\u00e4ftsrollen"),
                        Map.entry("Services", "Dienste"),
                        Map.entry("COI Services", "COI-Dienste"),
                        Map.entry("Core Services", "Kerndienste"),
                        Map.entry("Applications", "Anwendungen"),
                        Map.entry("User Applications", "Benutzeranwendungen"),
                        Map.entry("Information Products", "Informationsprodukte"),
                        Map.entry("Communications Services", "Kommunikationsdienste")
                ),
                Map.ofEntries(
                        Map.entry("REALIZES", "realisiert"),
                        Map.entry("SUPPORTS", "unterst\u00fctzt"),
                        Map.entry("USES", "nutzt"),
                        Map.entry("FULFILLS", "erf\u00fcllt"),
                        Map.entry("DEPENDS_ON", "h\u00e4ngt ab von"),
                        Map.entry("PRODUCES", "erzeugt"),
                        Map.entry("CONSUMES", "konsumiert"),
                        Map.entry("ASSIGNED_TO", "zugeordnet zu"),
                        Map.entry("RELATED_TO", "bezieht sich auf"),
                        Map.entry("COMMUNICATES_WITH", "kommuniziert mit")
                ),
                "★",
                "\u26A0"
        );
    }

    /**
     * Returns the localized display label for a layer type, falling back to the
     * internal name if no mapping exists.
     */
    public String layerLabel(String internalType) {
        return layerLabels.getOrDefault(internalType, internalType);
    }

    /**
     * Returns the localized display label for a relation type, falling back to
     * a lowercase version of the internal key if no mapping exists.
     */
    public String relationLabel(String internalType) {
        return relationLabels.getOrDefault(internalType, internalType.toLowerCase().replace('_', ' '));
    }
}
