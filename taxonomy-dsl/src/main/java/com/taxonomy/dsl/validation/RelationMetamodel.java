package com.taxonomy.dsl.validation;

import java.util.*;

/** Immutable, framework-free relation legality. Legality never implies an obligation. */
public final class RelationMetamodel {
    public static final String VERSION = "1.0.0";
    public enum DomainState { UNRESTRICTED, RESTRICTED, FORBIDDEN }
    public record TargetDomain(DomainState state, Set<String> roots) {
        public TargetDomain {
            Objects.requireNonNull(state, "state"); roots = Collections.unmodifiableSet(new TreeSet<>(roots));
            if ((state == DomainState.RESTRICTED) == roots.isEmpty())
                throw new IllegalArgumentException("Only restricted domains have nonempty roots");
        }
        public boolean allows(String root) {
            return state == DomainState.UNRESTRICTED || (state == DomainState.RESTRICTED && root != null && roots.contains(root));
        }
    }
    private static final Map<String, Map<String, Set<String>>> RESTRICTED_RULES;
    static {
        Map<String, Map<String, Set<String>>> m = new LinkedHashMap<>();
        m.put("REALIZES",          Map.of("CP", Set.of("CR")));
        m.put("SUPPORTS",          Map.of("CR", Set.of("BP", "BR"),
                                          "CI", Set.of("BP", "BR"),
                                          "CO", Set.of("BP"),
                                          "UA", Set.of("BP")));
        m.put("CONSUMES",          Map.of("BP", Set.of("IP"),
                                          "CR", Set.of("IP"),
                                          "CI", Set.of("IP"),
                                          "UA", Set.of("IP")));
        m.put("USES",              Map.of("UA", Set.of("CR", "CI", "CO"),
                                          "SY", Set.of("SY", "CR"),
                                          "BP", Set.of("CR", "CI"),
                                          "BR", Set.of("IP")));
        m.put("FULFILLS",          Map.of("CI", Set.of("CP"), "CR", Set.of("CP")));
        m.put("ASSIGNED_TO",       Map.of("BR", Set.of("BP")));
        m.put("DEPENDS_ON",        Map.of("CR", Set.of("CR", "CI"),
                                          "CI", Set.of("CR"),
                                          "CO", Set.of("CR"),
                                          "SY", Set.of("SY"),
                                          "CM", Set.of("CM", "CR")));
        m.put("PRODUCES",          Map.of("BP", Set.of("IP"),
                                          "CR", Set.of("IP"),
                                          "CI", Set.of("IP"),
                                          "UA", Set.of("IP")));
        m.put("COMMUNICATES_WITH", Map.of("CO", Set.of("CR", "CI", "UA")));
        m.put("CONTAINS",          Map.of("SY", Set.of("UA", "CM"), "CM", Set.of("CM")));
        m.put("REQUIRES",          Map.of("CP", Set.of("IP")));
        // RELATED_TO has no restrictions
        RESTRICTED_RULES = Collections.unmodifiableMap(m);
    }


    private static final Set<String> TYPES;
    static { var types = new TreeSet<>(RESTRICTED_RULES.keySet()); types.add("RELATED_TO"); TYPES = Collections.unmodifiableSet(types); }
    public record RelationDomain(TargetDomain defaultDomain, Map<String, TargetDomain> sourceDomains) {
        public RelationDomain { Objects.requireNonNull(defaultDomain); sourceDomains = Collections.unmodifiableMap(new TreeMap<>(sourceDomains)); }
    }
    public static Map<String, RelationDomain> relationDomains() {
        Map<String, RelationDomain> result = new TreeMap<>();
        for (String type : TYPES) {
            Map<String, TargetDomain> sources = new TreeMap<>();
            RESTRICTED_RULES.getOrDefault(type, Map.of()).forEach((root, targets) -> sources.put(root, targetDomain(root, type)));
            result.put(type, new RelationDomain(targetDomain(null, type), sources));
        }
        return Collections.unmodifiableMap(result);
    }
    private RelationMetamodel() {}
    public static Set<String> relationTypes() { return TYPES; }
    public static TargetDomain targetDomain(String sourceRoot, String relationType) {
        if ("RELATED_TO".equals(relationType)) return new TargetDomain(DomainState.UNRESTRICTED, Set.of());
        var rules = RESTRICTED_RULES.get(relationType);
        var roots = rules == null || sourceRoot == null ? null : rules.get(sourceRoot);
        return roots == null ? new TargetDomain(DomainState.FORBIDDEN, Set.of()) : new TargetDomain(DomainState.RESTRICTED, roots);
    }
    public static boolean isCompatible(String sourceRoot, String targetRoot, String relationType) {
        return targetDomain(sourceRoot, relationType).allows(targetRoot);
    }
}
