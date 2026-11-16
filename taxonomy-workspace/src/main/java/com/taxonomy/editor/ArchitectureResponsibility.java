package com.taxonomy.editor;

import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureOrganization;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** The working tree and effective responsibility are derived from the same revision. */
public final class ArchitectureResponsibility {
    public record Entry(String parentId, String organizationId, String sourceElementId, String legacyOrganization,
                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                        String capturedName) {
        Entry(String parentId, String organizationId, String sourceElementId, String legacyOrganization) {
            this(parentId, organizationId, sourceElementId, legacyOrganization, null);
        }
    }

    private ArchitectureResponsibility() {}

    static Map<String, Entry> resolve(CanonicalArchitectureModel model, Map<String, String> catalogueParents) {
        Map<String, ArchitectureElement> elements = new LinkedHashMap<>();
        model.getElements().forEach(element -> elements.put(element.getId(), element));
        Map<String, String> parents = new HashMap<>();
        model.getRelations().stream().filter(r -> "CONTAINS".equals(r.getRelationType())
                        && elements.containsKey(r.getSourceId()) && elements.containsKey(r.getTargetId()))
                .forEach(r -> parents.putIfAbsent(r.getTargetId(), r.getSourceId()));
        Set<String> explicitParents = Set.copyOf(parents.keySet());
        Map<CataloguePath, String> nearest = new HashMap<>();
        for (var element : elements.values()) {
            if (explicitParents.contains(element.getId())) continue;
            String parent = nearestParent(element, elements.keySet(), catalogueParents, nearest);
            if (parent != null) parents.put(element.getId(), parent);
        }
        // Independently valid containment and catalogue paths can form a cycle when combined.
        removeCycles(elements.keySet(), parents, explicitParents);

        Map<String, Entry> resolved = new LinkedHashMap<>();
        for (String start : elements.keySet()) {
            var path = new ArrayList<String>();
            String current = start;
            while (elements.containsKey(current) && !resolved.containsKey(current)) {
                path.add(current);
                current = parents.get(current);
            }
            Entry inherited = resolved.get(current);
            for (String id : path.reversed()) {
                var fields = elements.get(id).getExtensions();
                String unit = fields.get(ArchitectureOrganization.RESPONSIBLE_ID);
                String legacy = fields.get("x-responsible-organization");
                var row = new Entry(parents.get(id), null, null, null);
                if (unit != null && !unit.isBlank()) row = new Entry(row.parentId(), unit, id, null);
                else if (legacy != null && !legacy.isBlank()) row = new Entry(row.parentId(), null, id, legacy);
                else if (fields.containsKey(ArchitectureOrganization.CAPTURED_UNIT)
                        && fields.containsKey(ArchitectureOrganization.CAPTURED_NAME)
                        && fields.containsKey(ArchitectureOrganization.CAPTURED_SOURCE)) {
                    row = new Entry(row.parentId(), fields.get(ArchitectureOrganization.CAPTURED_UNIT),
                            fields.get(ArchitectureOrganization.CAPTURED_SOURCE), null, fields.get(ArchitectureOrganization.CAPTURED_NAME));
                }
                else if (inherited != null) row = new Entry(row.parentId(), inherited.organizationId(),
                        inherited.sourceElementId(), inherited.legacyOrganization(), inherited.capturedName());
                resolved.put(id, row);
                inherited = row;
            }
        }
        return resolved;
    }

    /** Project stable display identities onto the full decision catalogue, including unadopted descendants. */
    public static Map<String, Assignment> forAnalysis(CanonicalArchitectureModel model, Map<String, String> catalogueParents) {
        var titles = organizationTitles(model);
        if (!model.getCatalogueResponsibilities().isEmpty()) return Map.copyOf(catalogue(model, catalogueParents, titles));
        var resolved = resolve(model, catalogueParents);
        var architecture = namedAssignments(titles, resolved);
        // An explicitly modeled node owns its effective state, including an unassigned state.
        inherit(catalogueParents, architecture, new HashSet<>(resolved.keySet()));
        return Map.copyOf(architecture);
    }

    static Map<String, Entry> forEditor(CanonicalArchitectureModel model, Map<String, String> architectureParents,
                                        Map<String, String> catalogueParents) {
        var resolved = resolve(model, architectureParents);
        if (model.getCatalogueResponsibilities().isEmpty()) return resolved;
        var catalogue = catalogue(model, catalogueParents, organizationTitles(model));
        resolved.replaceAll((code, entry) -> {
            var unit = catalogue.get(code);
            return entry.sourceElementId() != null || unit == null ? entry
                    : new Entry(entry.parentId(), unit.unitId(), unit.sourceId(), null);
        });
        return resolved;
    }

    static Map<String, Assignment> namedAssignments(CanonicalArchitectureModel model, Map<String, Entry> entries) {
        return namedAssignments(organizationTitles(model), entries);
    }

    private static Map<String, String> organizationTitles(CanonicalArchitectureModel model) {
        var titles = new HashMap<String, String>();
        model.getOrganizations().forEach(unit -> titles.put(unit.id(), unit.title()));
        return titles;
    }

    private static Map<String, Assignment> namedAssignments(Map<String, String> titles, Map<String, Entry> entries) {
        var named = new LinkedHashMap<String, Assignment>();
        entries.forEach((code, entry) -> {
            String unit = entry.organizationId();
            String name = entry.capturedName() != null ? entry.capturedName()
                    : unit == null ? entry.legacyOrganization() : titles.get(unit);
            if (name != null && !name.isBlank()) {
                named.put(code, new Assignment(unit == null ? "legacy:" + name : unit, name, entry.sourceElementId()));
            }
        });
        return named;
    }

    private static Map<String, Assignment> catalogue(CanonicalArchitectureModel model, Map<String, String> parents,
                                                     Map<String, String> titles) {
        var catalogue = new LinkedHashMap<String, Assignment>();
        for (var rule : model.getCatalogueResponsibilities()) {
            String title = titles.get(rule.organizationId());
            if (title != null && !title.isBlank()) {
                catalogue.put(rule.code(), new Assignment(rule.organizationId(), title, rule.code()));
            }
        }
        if (!catalogue.isEmpty()) inherit(parents, catalogue, new HashSet<>(catalogue.keySet()));
        return catalogue;
    }

    private static void inherit(Map<String, String> catalogueParents, Map<String, Assignment> assignments, Set<String> known) {
        // Memoize positive and negative results so shared paths are traversed only once.
        for (String code : catalogueParents.keySet()) {
            if (known.contains(code)) continue;
            var path = new java.util.LinkedHashSet<String>();
            String ancestor = code;
            while (ancestor != null && !known.contains(ancestor) && path.add(ancestor))
                ancestor = catalogueParents.get(ancestor);
            var inherited = assignments.get(ancestor);
            if (inherited != null) path.forEach(id -> assignments.put(id, inherited));
            known.addAll(path);
        }
    }

    private record CataloguePath(String root, String code) {}

    private static String nearestParent(ArchitectureElement element, Set<String> elements, Map<String, String> catalogue,
                                        Map<CataloguePath, String> nearest) {
        String parent = element.getExtensions().get("x-catalogue-parent");
        String root = element.getTaxonomy();
        if (parent == null) parent = catalogue.get(element.getId());
        var visited = new HashSet<CataloguePath>();
        while (parent != null && !elements.contains(parent) && !parent.equals(root)) {
            var key = new CataloguePath(root, parent);
            if (nearest.containsKey(key)) { parent = nearest.get(key); break; }
            if (!visited.add(key)) { parent = null; break; }
            parent = catalogue.get(parent);
        }
        for (var key : visited) nearest.put(key, parent);
        return parent;
    }

    private static void removeCycles(Set<String> elements, Map<String, String> parents, Set<String> explicit) {
        Set<String> done = new HashSet<>();
        for (String start : elements) {
            if (done.contains(start)) continue;
            var path = new LinkedHashMap<String, Integer>();
            String current = start;
            while (current != null && !done.contains(current)) {
                Integer index = path.putIfAbsent(current, path.size());
                if (index != null) {
                    var cycle = path.keySet().stream().skip(index).toList();
                    String breakAt = cycle.stream().filter(id -> !explicit.contains(id)).min(String::compareTo)
                            .orElseGet(() -> cycle.stream().min(String::compareTo).orElseThrow());
                    parents.remove(breakAt);
                    break;
                }
                current = parents.get(current);
            }
            done.addAll(path.keySet());
        }
    }
}
