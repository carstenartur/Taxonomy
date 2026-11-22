package com.taxonomy.dsl.admbw;

import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.ArchitectureView;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Accounts for the complete source checkpoint, independently of a selected view's structural validity. */
public record AdmbwDeliveryCoverage(String status, String sourceViewId,
                                   Counts elements, Counts relations, Counts requirements, Counts mappings,
                                   List<Omission> omissions, List<SourceAnalysis> sourceAnalyses) {
    public record Counts(int total, int represented, int omitted) { }
    public record Omission(String kind, String ref, String name, String reason) { }
    public record SourceAnalysis(String viewId, String snapshotId, String status) { }

    public AdmbwDeliveryCoverage {
        omissions = List.copyOf(omissions);
        sourceAnalyses = List.copyOf(sourceAnalyses);
    }

    static AdmbwDeliveryCoverage assess(CanonicalArchitectureModel model, String viewId,
                                       AdmbwFffmlvProfile.View c1, AdmbwFffmlvProfile.View r7) {
        ArchitectureView selected = viewId == null || viewId.isBlank() ? null : model.getViews().stream()
                .filter(view -> viewId.equals(view.getId())).findFirst().orElseThrow();
        Set<String> members = selected == null ? model.allElementIds() : new HashSet<>(selected.getIncludes());
        String recorded = AdmbwFffmlvProfile.recordedRelationMembers(selected);
        Set<String> allowedRelations = recorded == null ? null : new HashSet<>(Arrays.asList(recorded.split("\n")));
        Set<String> represented = Stream.concat(c1.elements().stream(), r7.elements().stream())
                .map(AdmbwFffmlvProfile.Element::ref).collect(Collectors.toSet());
        Map<String, ArchitectureElement> byId = model.getElements().stream()
                .collect(Collectors.toMap(ArchitectureElement::getId, element -> element));
        var omitted = new ArrayList<Omission>();

        Counts elements = count(model.getElements(), element -> {
            String ref = "element:" + element.getId();
            if (represented.contains(ref)) return true;
            String declaredRole = element.getExtensions().get(AdmbwFffmlvProfile.TYPE);
            String reason = !members.contains(element.getId()) ? "OUTSIDE_SELECTED_C1_S1_VIEW"
                    : declaredRole != null && !"ServiceSpecification".equals(declaredRole)
                    && AdmbwFffmlvProfile.ELEMENT_STEREOTYPES.contains(declaredRole)
                    ? "NOT_USED_BY_R7" : "UNSUPPORTED_ELEMENT_TYPE";
            omitted.add(new Omission("ELEMENT", ref, element.getTitle(), reason));
            return false;
        });
        Counts relations = count(model.getRelations(), relation -> {
            String identity = relationIdentity(relation);
            String reason;
            if (!"accepted".equals(relation.getStatus())) reason = "RELATION_NOT_ACCEPTED";
            else if (!byId.containsKey(relation.getSourceId()) || !byId.containsKey(relation.getTargetId()))
                reason = "RELATION_ENDPOINT_NOT_FOUND";
            else if (!members.contains(relation.getSourceId()) || !members.contains(relation.getTargetId())
                    || allowedRelations != null && !allowedRelations.contains(identity)) {
                reason = "RELATION_OUTSIDE_SELECTED_VIEW";
            } else if (representedRelation(relation, byId, c1)) return true;
            else reason = "UNSUPPORTED_RELATION";
            omitted.add(new Omission("RELATION", "relation:" + identity, identity, reason));
            return false;
        });
        Counts requirements = count(model.getRequirements(), requirement -> {
            String ref = "requirement:" + requirement.getId();
            if (represented.contains(ref)) return true;
            omitted.add(new Omission("REQUIREMENT", ref, requirement.getTitle(), "REQUIREMENT_NOT_DELIVERABLE"));
            return false;
        });
        Counts mappings = count(model.getMappings(), mapping -> {
            String identity = mapping.getRequirementId() + " -> " + mapping.getElementId();
            // C1-S1/R7 connectors have distinct semantics. Sharing endpoints is not a mapping export.
            omitted.add(new Omission("MAPPING", "mapping:" + identity, identity, "REQUIREMENT_MAPPING_NOT_EXPORTED"));
            return false;
        });
        omitted.sort(Comparator.comparing(Omission::kind).thenComparing(Omission::ref));
        List<SourceAnalysis> sources = model.getViews().stream()
                .filter(view -> view.getExtensions().containsKey("x-analysis-snapshot-id"))
                .map(view -> new SourceAnalysis(view.getId(), view.getExtensions().get("x-analysis-snapshot-id"),
                        view.getExtensions().getOrDefault("x-analysis-status", "UNRECORDED")))
                .sorted(Comparator.comparing(SourceAnalysis::viewId)).toList();
        int total = elements.total() + relations.total() + requirements.total() + mappings.total();
        String status = total == 0 ? "NO_SOURCE_OBJECTS" : omitted.isEmpty() ? "ALL_SOURCE_OBJECTS_REPRESENTED" : "PARTIAL";
        return new AdmbwDeliveryCoverage(status, selected == null ? null : selected.getId(),
                elements, relations, requirements, mappings, omitted, sources);
    }

    private static boolean representedRelation(ArchitectureRelation relation, Map<String, ArchitectureElement> elements,
                                               AdmbwFffmlvProfile.View c1) {
        ArchitectureElement source = elements.get(relation.getSourceId());
        ArchitectureElement target = elements.get(relation.getTargetId());
        if (source == null || target == null) return false;
        String service = null, capability = null;
        if ("REALIZES".equals(relation.getRelationType()) && "Capability".equals(source.getType())
                && "CoreService".equals(target.getType())) {
            service = target.getId(); capability = source.getId();
        } else if ("FULFILLS".equals(relation.getRelationType())
                && Set.of("CoreService", "COIService").contains(source.getType()) && "Capability".equals(target.getType())) {
            service = source.getId(); capability = target.getId();
        }
        return service != null && c1.connectors().contains(new AdmbwFffmlvProfile.Connector(
                "Exhibits", "element:" + service, "element:" + capability));
    }

    private static String relationIdentity(ArchitectureRelation relation) {
        return relation.getSourceId() + " " + relation.getRelationType() + " " + relation.getTargetId();
    }

    private static <T> Counts count(List<T> items, Predicate<T> represented) {
        int included = 0;
        for (T item : items) if (represented.test(item)) included++;
        return new Counts(items.size(), included, items.size() - included);
    }

    /** Portable disclosure for native EA diagram notes; never a conformance certificate. */
    public String notes() {
        StringBuilder text = new StringBuilder("Delivery coverage: ").append(status)
                .append("\nC1-S1 selection: ").append(sourceViewId == null ? "entire checkpoint" : sourceViewId)
                .append("\nR7 scope: all checkpoint requirements")
                .append("\nRepresented source objects (not full architecture or ADMBw method conformance):");
        appendCounts(text, "Elements", elements);
        appendCounts(text, "Relations", relations);
        appendCounts(text, "Requirements", requirements);
        appendCounts(text, "Requirement mappings", mappings);
        for (SourceAnalysis source : sourceAnalyses) text.append("\nSource analysis: ").append(source.viewId())
                .append(" / ").append(source.snapshotId()).append(" / ").append(source.status());
        for (Omission omission : omissions) text.append("\nOmitted: ").append(omission.ref())
                .append(" / ").append(omission.reason());
        return text.append("\nStructural findings and all manual reviews remain applicable.").toString();
    }

    private static void appendCounts(StringBuilder text, String label, Counts counts) {
        text.append('\n').append(label).append(": ").append(counts.represented()).append('/').append(counts.total())
                .append(" represented; ").append(counts.omitted()).append(" omitted");
    }
}
