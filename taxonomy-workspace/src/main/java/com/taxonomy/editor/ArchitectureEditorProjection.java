package com.taxonomy.editor;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.model.TaxonomyRootTypes;
import com.taxonomy.dsl.validation.DslValidator;
import com.taxonomy.export.LayeredDiagramLayoutService;
import org.springframework.stereotype.Component;

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
                         Map<String, String> typeRoots, Map<String, PropertyRule> properties, String layoutMode) {}
    public record PropertyRule(boolean required, boolean readOnly, boolean derived,
                               List<String> values, String labelKey) {}
    public record SearchEntry(String id, String text) {}
    public record View(ArchitectureEditorService.Document document, CanonicalArchitectureModel model,
                       DiagramScene scene, Schema schema, boolean mayEdit, List<SearchEntry> searchIndex) {}

    public View project(ArchitectureEditorService.Document document, boolean mayEdit) {
        CanonicalArchitectureModel model = new ArchitectureDslCommands().model(document.dsl());
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
        List<DiagramEdge> edges = model.getRelations().stream().map(relation -> new DiagramEdge(
                relation.getSourceId() + " " + relation.getRelationType() + " " + relation.getTargetId(),
                relation.getSourceId(), relation.getTargetId(), relation.getRelationType(), 0)).toList();
        DiagramScene scene = layout.layout(new DiagramModel("Architecture · " + document.context().branch(), nodes, edges, new DiagramLayout("LR", true)));
        Schema schema = new Schema(types(), ArchitectureDslCommands.ELEMENT_PROPERTIES.stream().sorted().toList(),
                DslValidator.relationTypes().stream().sorted().toList(), DslValidator.relationStatuses().stream().sorted().toList(),
                DslValidator.relationTypeRules(), TaxonomyRootTypes.TYPE_TO_ROOT, properties(), "DERIVED_SERVER_LAYOUT");
        return new View(document, model, scene, schema, mayEdit && "PRIVATE_WORKSPACE".equals(document.context().writeMode())
                && "READY".equals(document.projectionState()),
                model.getElements().stream().map(element -> new SearchEntry(element.getId(),
                        (element.getId() + " " + element.getTitle() + " " + element.getType()).toLowerCase(java.util.Locale.ROOT))).toList());
    }

    private static List<String> types() { return TaxonomyRootTypes.TYPE_TO_ROOT.keySet().stream().sorted().toList(); }

    private static Map<String, PropertyRule> properties() {
        Map<String, PropertyRule> properties = new java.util.TreeMap<>();
        Map<String, String> labels = Map.of("title", "name", "description", "description", "x-editor-status", "elementStatus",
                "x-owner", "owner", "x-responsible-organization", "organization", "x-product-reference", "product", "x-system-reference", "system");
        ArchitectureDslCommands.ELEMENT_PROPERTIES.forEach(key -> properties.put(key,
                new PropertyRule("title".equals(key), false, false,
                        "x-editor-status".equals(key) ? List.of("draft", "active", "retired") : List.of(), "editor." + labels.get(key))));
        properties.put("id", new PropertyRule(true, true, true, List.of(), "editor.identity"));
        properties.put("taxonomy", new PropertyRule(false, true, false, List.of(), "editor.taxonomy"));
        properties.put("type", new PropertyRule(true, false, false, types(), "editor.type"));
        return java.util.Collections.unmodifiableMap(properties);
    }
}
