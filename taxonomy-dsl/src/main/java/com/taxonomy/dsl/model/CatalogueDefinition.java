package com.taxonomy.dsl.model;

import com.taxonomy.dsl.validation.RelationMetamodel;
import com.taxonomy.dsl.validation.RelationMetamodel.DomainState;
import com.taxonomy.dsl.validation.RelationMetamodel.RelationDomain;
import com.taxonomy.dsl.validation.RelationMetamodel.TargetDomain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** The workbook's ordered sheet identities, DSL element types and directed relation domains. */
public final class CatalogueDefinition {
    public record Root(String sheet, String code, String type) {
        public Root {
            if (sheet == null || sheet.isBlank() || !sheet.equals(sheet.strip())
                    || code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")
                    || type == null || !type.matches("[A-Z][A-Za-z0-9_]{0,63}"))
                throw new IllegalArgumentException("Invalid catalogue sheet, root code or DSL type");
        }
    }
    public record Direction(String source, String relation, String target) {}

    private static final List<Root> STANDARD_ROOTS = List.of(
            new Root("Business Processes", "BP", "Process"),
            new Root("Business Roles", "BR", "BusinessRole"),
            new Root("Capabilities", "CP", "Capability"),
            new Root("COI Services", "CI", "COIService"),
            new Root("Communications Services", "CO", "CommunicationsService"),
            new Root("Core Services", "CR", "CoreService"),
            new Root("Information Products", "IP", "InformationProduct"),
            new Root("User Applications", "UA", "UserApplication"));
    private static final TargetDomain FORBIDDEN = new TargetDomain(DomainState.FORBIDDEN, Set.of());
    private static final CatalogueDefinition STANDARD = new CatalogueDefinition(
            "standard-v1", true, List.of(), List.of());

    public record Spec(String version, boolean extendsStandard, List<Root> roots, List<Direction> directions) {
        public Spec {
            roots = List.copyOf(roots);
            directions = List.copyOf(directions);
        }

        public CatalogueDefinition definition() {
            return new CatalogueDefinition(version, extendsStandard, roots, directions);
        }
    }

    private final Spec spec;
    private final List<Root> roots;
    private final Set<String> rootCodes;
    private final Map<String, String> types;
    private final Map<String, String> typeRoots;
    private final Map<String, RelationDomain> domains;

    public CatalogueDefinition(String version, boolean extendsStandard, List<Root> declaredRoots,
                               List<Direction> directions) {
        if (version == null || version.isBlank() || version.length() > 128)
            throw new IllegalArgumentException("A catalogue definition needs a version");
        this.spec = new Spec(version, extendsStandard, declaredRoots, directions);
        var combined = new ArrayList<Root>();
        if (extendsStandard) combined.addAll(STANDARD_ROOTS);
        combined.addAll(declaredRoots);
        if (combined.isEmpty()) throw new IllegalArgumentException("A catalogue definition needs a root");
        this.roots = List.copyOf(combined);

        var rootToType = new LinkedHashMap<String, String>();
        var typeToRoot = new LinkedHashMap<String, String>();
        var sheets = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Root root : roots) {
            if (!sheets.add(root.sheet()) || !register(root.code(), root.type(), rootToType, typeToRoot))
                throw new IllegalArgumentException("Duplicate catalogue sheet, root code or DSL type: " + root);
        }
        this.rootCodes = Collections.unmodifiableSet(new LinkedHashSet<>(rootToType.keySet()));
        // C4-only element types belong to the architecture metamodel, not to an imported sheet.
        if (!register("SY", "System", rootToType, typeToRoot) || !register("CM", "Component", rootToType, typeToRoot))
            throw new IllegalArgumentException("Catalogue root collides with a C4 architecture type");
        this.types = Collections.unmodifiableMap(rootToType);
        this.typeRoots = Collections.unmodifiableMap(typeToRoot);
        var result = new TreeMap<String, RelationDomain>();
        if (extendsStandard) result.putAll(RelationMetamodel.relationDomains());
        var seen = new LinkedHashSet<Direction>();
        for (Direction direction : directions) {
            if (!types.containsKey(direction.source()) || !types.containsKey(direction.target())
                    || !RelationMetamodel.relationTypes().contains(direction.relation()) || !seen.add(direction))
                throw new IllegalArgumentException("Invalid or duplicate catalogue relation direction: " + direction);
            var domain = result.getOrDefault(direction.relation(), new RelationDomain(FORBIDDEN, Map.of()));
            var bySource = new TreeMap<>(domain.sourceDomains());
            var current = bySource.getOrDefault(direction.source(), domain.defaultDomain());
            if (current.allows(direction.target())) continue;
            var targets = new TreeSet<>(current.roots());
            targets.add(direction.target());
            bySource.put(direction.source(), new TargetDomain(DomainState.RESTRICTED, targets));
            result.put(direction.relation(), new RelationDomain(domain.defaultDomain(), bySource));
        }
        this.domains = Collections.unmodifiableMap(result);
    }

    private static boolean register(String code, String type, Map<String, String> byCode, Map<String, String> byType) {
        if (byCode.containsKey(type) || byType.containsKey(code)
                || byCode.containsKey(code) || byType.containsKey(type)) return false;
        byCode.put(code, type);
        byType.put(type, code);
        return true;
    }

    public static CatalogueDefinition standard() { return STANDARD; }
    public String version() { return spec.version(); }
    public Spec spec() { return spec; }
    public List<Root> roots() { return roots; }
    public Set<String> rootCodes() { return rootCodes; }
    public Map<String, String> rootTypes() { return types; }
    public Map<String, String> typeRoots() { return typeRoots; }
    public String typeFor(String root) { return types.get(root); }
    public String rootFor(String type) { return types.containsKey(type) ? type : typeRoots.get(type); }
    public String rootFromId(String id) {
        if (id == null) return null;
        int dash = id.indexOf('-');
        String prefix = dash < 1 ? id : id.substring(0, dash);
        return types.containsKey(prefix) ? prefix : null;
    }
    public Map<String, RelationDomain> relationDomains() { return domains; }

    public TargetDomain targetDomain(String source, String relation) {
        if (relation == null || source != null && !types.containsKey(source)) return FORBIDDEN;
        var domain = domains.get(relation);
        if (domain == null) return FORBIDDEN;
        return source == null ? domain.defaultDomain() : domain.sourceDomains().getOrDefault(source, domain.defaultDomain());
    }

    public boolean allows(String source, String relation, String target) {
        return source != null && target != null && types.containsKey(target) && targetDomain(source, relation).allows(target);
    }
}
