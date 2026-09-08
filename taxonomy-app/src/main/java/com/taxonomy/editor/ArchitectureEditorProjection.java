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

/** Graph, tree, forms and source are projections of the same immutable Git document. */
@Component
public class ArchitectureEditorProjection {
    private final LayeredDiagramLayoutService layout;

    public ArchitectureEditorProjection(LayeredDiagramLayoutService layout) { this.layout = layout; }

    public record Schema(List<String> elementTypes, List<String> elementProperties,
                         List<String> relationTypes, List<String> relationStatuses,
                         Map<String, Map<String, java.util.Set<String>>> relationRules,
                         Map<String, String> typeRoots, String layoutMode) {}
    public record View(ArchitectureEditorService.Document document, CanonicalArchitectureModel model,
                       DiagramScene scene, Schema schema, boolean mayEdit) {}

    public View project(ArchitectureEditorService.Document document, boolean mayEdit) {
        CanonicalArchitectureModel model = new ArchitectureDslCommands().model(document.dsl());
        List<DiagramNode> nodes = model.getElements().stream().map(element -> new DiagramNode(
                element.getId(), element.getTitle(), element.getType(), 0, false,
                Math.max(0, types().indexOf(element.getType())), 0, false,
                model.getRelations().stream().filter(r -> "CONTAINS".equals(r.getRelationType()) && element.getId().equals(r.getTargetId()))
                        .map(com.taxonomy.dsl.model.ArchitectureRelation::getSourceId).findFirst().orElse(null), false)).toList();
        List<DiagramEdge> edges = model.getRelations().stream().map(relation -> new DiagramEdge(
                relation.getSourceId() + " " + relation.getRelationType() + " " + relation.getTargetId(),
                relation.getSourceId(), relation.getTargetId(), relation.getRelationType(), 0)).toList();
        DiagramScene scene = layout.layout(new DiagramModel("Architecture · " + document.context().branch(), nodes, edges, new DiagramLayout("LR", true)));
        Schema schema = new Schema(types(), ArchitectureDslCommands.ELEMENT_PROPERTIES.stream().sorted().toList(),
                DslValidator.relationTypes().stream().sorted().toList(), DslValidator.relationStatuses().stream().sorted().toList(),
                DslValidator.relationTypeRules(), TaxonomyRootTypes.TYPE_TO_ROOT, "DERIVED_SERVER_LAYOUT");
        return new View(document, model, scene, schema, mayEdit && "PRIVATE_WORKSPACE".equals(document.context().writeMode())
                && !"HISTORICAL".equals(document.projectionState()));
    }

    private static List<String> types() { return TaxonomyRootTypes.TYPE_TO_ROOT.keySet().stream().sorted().toList(); }
}
