package com.taxonomy.editor;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramLayout.Strategy;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.dsl.admbw.AdmbwFffmlvProfile;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.model.TaxonomyRootTypes;
import com.taxonomy.dsl.validation.DslValidator;
import com.taxonomy.export.LayeredDiagramLayoutService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Graph, tree, forms and source are projections of the same canonical workspace revision or Git checkpoint. */
@Component
public class ArchitectureEditorProjection {
    private final LayeredDiagramLayoutService layout;

    public ArchitectureEditorProjection(LayeredDiagramLayoutService layout) { this.layout = layout; }

    public record Schema(List<String> elementTypes, List<String> elementProperties,
                         List<String> relationTypes, List<String> relationStatuses,
                         Map<String, Map<String, java.util.Set<String>>> relationRules,
                         Map<String, String> typeRoots, Map<String, PropertyRule> properties, String layoutMode,
                         String layoutStrategy, Long layoutRevision) {
        public Schema(List<String> elementTypes, List<String> elementProperties, List<String> relationTypes,
                      List<String> relationStatuses, Map<String, Map<String, java.util.Set<String>>> relationRules,
                      Map<String, String> typeRoots, Map<String, PropertyRule> properties, String layoutMode) {
            this(elementTypes, elementProperties, relationTypes, relationStatuses, relationRules, typeRoots,
                    properties, layoutMode, "stable", null);
        }
        public List<com.taxonomy.dsl.planning.PlanningProfile.Descriptor> getPlanningProfiles() {
            return new com.taxonomy.dsl.planning.PlanningInformation().descriptors();
        }
        public List<String> getPackageProperties() { return List.of("title", "description"); }
        public List<String> getPackageCommands() { return List.of("CREATE_PACKAGE", "UPDATE_PACKAGE", "SET_PACKAGE_PLACEMENTS", "DELETE_PACKAGE"); }
    }
    public record PropertyRule(boolean required, boolean readOnly, boolean derived,
                               List<String> values, String labelKey) {}
    public record SearchEntry(String id, String text) {}
    public record View(ArchitectureEditorService.Document document, CanonicalArchitectureModel model,
                       DiagramScene scene, Schema schema, boolean mayEdit, List<SearchEntry> searchIndex,
                       WorkspaceCollaborationService.Status collaboration) {
        public View(ArchitectureEditorService.Document document, CanonicalArchitectureModel model,
                    DiagramScene scene, Schema schema, boolean mayEdit, List<SearchEntry> searchIndex) {
            this(document, model, scene, schema, mayEdit, searchIndex, null);
        }
        public View withCollaboration(WorkspaceCollaborationService.Status status) {
            return new View(document, model, scene, schema, mayEdit, searchIndex, status);
        }
        public Map<String, List<com.taxonomy.dsl.planning.PlanningInformation.View>> getPlanningInformation() {
            var registry = new com.taxonomy.dsl.planning.PlanningInformation();
            Map<String, List<com.taxonomy.dsl.planning.PlanningInformation.View>> result = new java.util.LinkedHashMap<>();
            for (var requirement : model.getRequirements()) result.put(requirement.getId(), registry.read(model, requirement.getId()));
            return java.util.Collections.unmodifiableMap(result);
        }
        public Map<String, Map<String, List<String>>> getPlanningElementChoices() {
            return new com.taxonomy.dsl.planning.PlanningInformation().elementChoices(model);
        }
    }

    public View project(ArchitectureEditorService.Document document, boolean mayEdit) {
        return project(document, mayEdit, Strategy.STABLE, null);
    }

    public View project(ArchitectureEditorService.Document document, boolean mayEdit, Strategy strategy,
                        ArchitectureEditorService.Document layoutReference) {
        return project(document, mayEdit, strategy, layoutReference, null);
    }

    public View project(ArchitectureEditorService.Document document, boolean mayEdit, Strategy strategy,
                        ArchitectureEditorService.Document layoutReference, String selectedViewId) {
        Long layoutRevision = layoutReference == null ? null : layoutReference.context().revision();
        if (layoutRevision == null && strategy == Strategy.REDUCE_CROSSINGS
                && "WORKSPACE_REVISION".equals(document.source())) {
            layoutRevision = document.context().revision();
        }
        requireLayoutRevision(document, strategy, layoutRevision);
        CanonicalArchitectureModel model = new ArchitectureDslCommands().model(document.dsl());
        DiagramModel diagram = selectView(diagram(model, document.context().branch()), model, selectedViewId);
        DiagramModel reference = layoutReference == null ? null : diagram(
                new ArchitectureDslCommands().model(layoutReference.dsl()), document.context().branch());
        DiagramScene scene = layout.layout(diagram, strategy, reference);
        Schema schema = new Schema(types(), ArchitectureDslCommands.ELEMENT_PROPERTIES.stream().sorted().toList(),
                DslValidator.relationTypes().stream().sorted().toList(), DslValidator.relationStatuses().stream().sorted().toList(),
                DslValidator.relationTypeRules(), TaxonomyRootTypes.TYPE_TO_ROOT, properties(),
                strategy == Strategy.STABLE ? "DERIVED_SERVER_LAYOUT" : "DERIVED_SERVER_LAYOUT_REDUCE_CROSSINGS",
                strategy.id(), layoutRevision);
        return new View(document, model, scene, schema, mayEdit && List.of("PRIVATE_WORKSPACE", "SHARED_WORKSPACE").contains(document.context().writeMode())
                && "READY".equals(document.projectionState()),
                model.getElements().stream().map(element -> new SearchEntry(element.getId(),
                        (element.getId() + " " + element.getTitle() + " " + element.getType()).toLowerCase(java.util.Locale.ROOT))).toList());
    }

    public static void requireLayoutRevision(ArchitectureEditorService.Document document, Strategy strategy, Long revision) {
        if (revision != null && (revision < 0 || revision > document.context().revision()
                || strategy != Strategy.REDUCE_CROSSINGS || !"WORKSPACE_REVISION".equals(document.source()))) {
            throw new IllegalArgumentException("Layout reference must be a retained workspace revision at or before the selected revision");
        }
    }

    private static DiagramModel selectView(DiagramModel diagram, CanonicalArchitectureModel model, String selectedViewId) {
        if (selectedViewId == null || selectedViewId.isBlank()) return diagram;
        var view = model.getViews().stream().filter(item -> selectedViewId.equals(item.getId())).findFirst()
                .orElseThrow(() -> new ArchitectureDslCommands.CommandProblem("NOT_FOUND", "viewId", "Canonical view is not present in this revision", List.of()));
        var members = new HashSet<>(view.getIncludes());
        String recorded = view.getExtensions().get("x-analysis-relation-members");
        var relations = recorded == null || recorded.isEmpty() ? new HashSet<String>()
                : new HashSet<>(List.of(recorded.split("\n")));
        var delivery = model.getAdmbwDelivery();
        if (recorded != null && delivery != null && selectedViewId.equals(delivery.sourceViewId())) {
            Map<String, ArchitectureElement> elements = new HashMap<>();
            model.getElements().stream().filter(element -> members.contains(element.getId()))
                    .forEach(element -> elements.putIfAbsent(element.getId(), element));
            for (var relation : model.getRelations()) {
                if ("accepted".equals(relation.getStatus()) && AdmbwFffmlvProfile.c1S1Connector(
                        elements.get(relation.getSourceId()), relation.getRelationType(), elements.get(relation.getTargetId())) != null) {
                    relations.add(relationId(relation));
                }
            }
        }
        return new DiagramModel(view.getTitle(), diagram.nodes().stream().filter(node -> members.contains(node.id())).toList(),
                diagram.edges().stream().filter(edge -> members.contains(edge.sourceId()) && members.contains(edge.targetId())
                        && (recorded == null || relations.contains(edge.id()))).toList(), diagram.layout());
    }

    private static String relationId(ArchitectureRelation relation) {
        return relation.getSourceId() + " " + relation.getRelationType() + " " + relation.getTargetId();
    }

    private DiagramModel diagram(CanonicalArchitectureModel model, String branch) {
        Map<String, String> parents = new java.util.LinkedHashMap<>();
        model.getRelations().stream().filter(relation -> "CONTAINS".equals(relation.getRelationType()))
                .forEach(relation -> parents.putIfAbsent(relation.getTargetId(), relation.getSourceId()));
        List<String> elementTypes = types();
        Map<String, Integer> typeRanks = new java.util.HashMap<>();
        for (int rank = 0; rank < elementTypes.size(); rank++) typeRanks.put(elementTypes.get(rank), rank);
        List<DiagramNode> nodes = model.getElements().stream().map(element -> new DiagramNode(
                element.getId(), element.getTitle(), element.getType(), 0, false,
                typeRanks.getOrDefault(element.getType(), 0), 0, false,
                parents.get(element.getId()), false)).toList();
        List<DiagramEdge> edges = model.getRelations().stream().map(relation -> new DiagramEdge(relationId(relation),
                relation.getSourceId(), relation.getTargetId(), relation.getRelationType(), 0)).toList();
        return new DiagramModel("Architecture · " + branch, nodes, edges, new DiagramLayout("LR", true));
    }

    private static List<String> types() { return TaxonomyRootTypes.TYPE_TO_ROOT.keySet().stream().sorted().toList(); }

    private static Map<String, PropertyRule> properties() {
        Map<String, PropertyRule> properties = new java.util.TreeMap<>();
        Map<String, String> labels = Map.of("title", "name", "description", "description", "x-editor-status", "elementStatus",
                "x-owner", "owner", "x-responsible-organization", "organization", "x-product-reference", "product", "x-system-reference", "system",
                AdmbwFffmlvProfile.TYPE, "admbw.stereotype", AdmbwFffmlvProfile.CATALOGUE_GUID, "admbw.catalogueGuid",
                AdmbwFffmlvProfile.CATALOGUE_REFERENCE, "admbw.catalogueReference");
        ArchitectureDslCommands.ELEMENT_PROPERTIES.forEach(key -> {
            List<String> values = switch (key) {
                case "x-editor-status" -> List.of("draft", "active", "retired");
                case AdmbwFffmlvProfile.TYPE -> AdmbwFffmlvProfile.ELEMENT_STEREOTYPES;
                default -> List.of();
            };
            properties.put(key, new PropertyRule("title".equals(key), false, false, values, "editor." + labels.get(key)));
        });
        properties.put("id", new PropertyRule(true, true, true, List.of(), "editor.identity"));
        properties.put("taxonomy", new PropertyRule(false, true, false, List.of(), "editor.taxonomy"));
        properties.put("type", new PropertyRule(true, false, false, types(), "editor.type"));
        return java.util.Collections.unmodifiableMap(properties);
    }
}
