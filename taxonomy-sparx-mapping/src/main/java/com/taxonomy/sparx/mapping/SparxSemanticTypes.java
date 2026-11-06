package com.taxonomy.sparx.mapping;

import java.util.Map;
import java.util.Set;

/**
 * Canonical meanings shared by the Sparx XMI and native-file adapters.
 * Association transports require an explicit Taxonomy declaration; they do not
 * make the corresponding concept a native UML Association or Realization.
 */
public final class SparxSemanticTypes {
    private static final Map<String, String> ELEMENTS = Map.of(
            "Capability", "UseCase", "Process", "Activity", "CoreService", "Interface", "COIService", "Interface",
            "CommunicationsService", "Interface", "UserApplication", "Component", "InformationProduct", "Artifact",
            "BusinessRole", "Actor", "System", "Component", "Component", "Component");
    private static final Map<String, String> RELATIONS = Map.ofEntries(
            Map.entry("DEPENDS_ON", "Dependency"), Map.entry("REALIZES", "Association"),
            Map.entry("COMMUNICATES_WITH", "InformationFlow"), Map.entry("RELATED_TO", "Association"),
            Map.entry("CONSUMES", "Usage"), Map.entry("CONTAINS", "Composition"),
            Map.entry("SUPPORTS", "Association"), Map.entry("USES", "Association"),
            Map.entry("FULFILLS", "Association"), Map.entry("ASSIGNED_TO", "Association"),
            Map.entry("PRODUCES", "Association"), Map.entry("REQUIRES", "Association"));
    private static final Set<String> ASSOCIATIONS = Set.of(
            "REALIZES", "SUPPORTS", "USES", "FULFILLS", "ASSIGNED_TO", "PRODUCES", "REQUIRES");

    private SparxSemanticTypes() { }

    public static Set<String> canonicalTypes() { return ELEMENTS.keySet(); }
    public static Map<String, String> elementTypes() { return ELEMENTS; }
    public static Map<String, String> relationTypes() { return RELATIONS; }
    public static Set<String> associationTypes() { return ASSOCIATIONS; }
    public static String umlType(String canonical) { return required(ELEMENTS, canonical, "element"); }
    public static String relationTransport(String canonical) { return required(RELATIONS, canonical, "relation"); }

    /** The seven explicit Association projections; RELATED_TO retains its generic Association meaning. */
    public static boolean associationTransport(String canonical) {
        return canonical != null && ASSOCIATIONS.contains(canonical);
    }

    public static String taxonomyStereotype(String canonical) {
        if (canonical == null || !ELEMENTS.containsKey(canonical) && !RELATIONS.containsKey(canonical))
            throw new IllegalArgumentException("Unsupported Taxonomy stereotype");
        return "Taxonomy::" + canonical;
    }

    private static String required(Map<String, String> mappings, String canonical, String kind) {
        String transport = canonical == null ? null : mappings.get(canonical);
        if (transport == null) throw new IllegalArgumentException("Unsupported Taxonomy " + kind + " type");
        return transport;
    }
}
