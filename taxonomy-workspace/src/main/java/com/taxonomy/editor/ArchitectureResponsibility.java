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
    public record Entry(String parentId, String organizationId, String sourceElementId, String legacyOrganization) {}

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
                else if (inherited != null) row = new Entry(row.parentId(), inherited.organizationId(),
                        inherited.sourceElementId(), inherited.legacyOrganization());
                resolved.put(id, row);
                inherited = row;
            }
        }
        return resolved;
    }

    /** Project stable display identities onto the full decision catalogue, including unadopted descendants. */
    public static Map<String, Assignment> forAnalysis(CanonicalArchitectureModel model, Map<String, String> catalogueParents) {
        var titles = new HashMap<String, String>();
        model.getOrganizations().forEach(unit -> titles.put(unit.id(), unit.title()));
        var assignments = new LinkedHashMap<String, Assignment>();
        var resolved = resolve(model, catalogueParents);
        for (var item : resolved.entrySet()) {
            var entry = item.getValue();
            String unit = entry.organizationId();
            String title = unit == null ? entry.legacyOrganization() : titles.get(unit);
            if (title != null && !title.isBlank()) {
                assignments.put(item.getKey(), new Assignment(unit == null ? "legacy:" + title : unit, title, entry.sourceElementId()));
            }
        }
        // An explicitly modeled node owns its effective state, including an unassigned state.
        // Memoize positive and negative results so shared paths are traversed only once.
        var known = new HashSet<>(resolved.keySet());
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
        return Map.copyOf(assignments);
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
