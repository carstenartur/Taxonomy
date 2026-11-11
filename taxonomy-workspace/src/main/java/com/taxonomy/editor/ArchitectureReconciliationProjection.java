package com.taxonomy.editor;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.diagram.DiagramSceneEdge;
import com.taxonomy.diagram.DiagramSceneNode;
import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dsl.model.CatalogueDefinitionResolver;
import com.taxonomy.export.LayeredDiagramLayoutService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Projects the frozen source, including the catalogue generation recorded by each document. */
public final class ArchitectureReconciliationProjection {
    private static final List<String> PARTS = List.of("base", "local", "incoming", "proposed");
    private static final Set<String> PRESENTATION_PROPERTIES = Set.of("layout", "x", "y", "width", "height",
            "x-layout", "x-position", "x-bounds", "x-diagram-geometry");
    private final LayeredDiagramLayoutService layout;

    private final CatalogueDefinitionResolver catalogues;

    ArchitectureReconciliationProjection(LayeredDiagramLayoutService layout, CatalogueDefinitionResolver catalogues) {
        this.layout = layout;
        this.catalogues = catalogues;
    }

    public record PropertyDifference(String blockId, String property, String category,
                                     List<String> base, List<String> local, List<String> incoming, List<String> proposed) {}
    public record Comparison(Map<String, DiagramScene> scenes, List<PropertyDifference> properties,
                             Map<String, String> typeRoots, Map<String, Map<String, String>> sceneTypeRoots) {}
    public record Preview(ArchitectureCommandPort.Context context, String headCommit, String fingerprint,
                          String baseDsl, String localDsl, String incomingDsl, String mergedDsl,
                          List<ArchitectureSemanticPatch.BlockChange> changes,
                          List<ArchitectureEditorService.ReconciliationConflict> conflicts, boolean canAccept,
                          Comparison comparison) {}

    Preview project(ArchitectureEditorService.ReconciliationPreview preview) {
        List<String> sources = java.util.Arrays.asList(preview.baseDsl(), preview.localDsl(), preview.incomingDsl(), preview.mergedDsl());
        var commands = new ArchitectureDslCommands();
        Map<String, DiagramModel> diagrams = new LinkedHashMap<>();
        Map<String, Map<String, String>> sceneTypeRoots = new LinkedHashMap<>();
        Map<String, String> sharedTypeRoots = new LinkedHashMap<>();
        Map<String, CatalogueDefinition> definitions = new LinkedHashMap<>();
        for (int index = 0; index < PARTS.size(); index++) {
            // An unresolved preview has no proposed document; an empty scene would imply deletion.
            String source = sources.get(index);
            if (source == null) continue;
            var model = commands.model(source);
            var catalogue = definitions.computeIfAbsent(model.getCatalogueRevision(), key -> catalogues.forModel(model));
            String part = PARTS.get(index);
            sceneTypeRoots.put(part, catalogue.typeRoots());
            // Older renderers receive only mappings shared by every visible source.
            if (diagrams.isEmpty()) sharedTypeRoots.putAll(catalogue.typeRoots());
            else sharedTypeRoots.entrySet().retainAll(catalogue.typeRoots().entrySet());
            diagrams.put(part, ArchitectureEditorProjection.diagram(model, preview.context().branch(), catalogue));
        }
        Map<String, DiagramNode> nodes = new LinkedHashMap<>();
        Map<String, DiagramEdge> edges = new LinkedHashMap<>();
        diagrams.values().forEach(diagram -> {
            diagram.nodes().forEach(node -> nodes.putIfAbsent(node.id(), node));
            diagram.edges().forEach(edge -> edges.putIfAbsent(edge.id(), edge));
        });
        // Lay out each semantic identity exactly once. Version-specific labels/types stay in their own scene.
        DiagramScene common = layout.layout(new DiagramModel("Reconciliation", List.copyOf(nodes.values()),
                List.copyOf(edges.values()), new DiagramLayout("LR", true)));
        Map<String, DiagramScene> scenes = new LinkedHashMap<>();
        diagrams.forEach((part, diagram) -> scenes.put(part, scene(diagram, common)));
        Comparison comparison = new Comparison(Collections.unmodifiableMap(scenes), differences(sources),
                Collections.unmodifiableMap(sharedTypeRoots), Collections.unmodifiableMap(sceneTypeRoots));
        return new Preview(preview.context(), preview.headCommit(), preview.fingerprint(), preview.baseDsl(), preview.localDsl(),
                preview.incomingDsl(), preview.mergedDsl(), preview.changes(), preview.conflicts(), preview.canAccept(), comparison);
    }

    private static DiagramScene scene(DiagramModel diagram, DiagramScene common) {
        Map<String, DiagramNode> nodes = new LinkedHashMap<>();
        diagram.nodes().forEach(node -> nodes.put(node.id(), node));
        Map<String, DiagramEdge> edges = new LinkedHashMap<>();
        diagram.edges().forEach(edge -> edges.put(edge.id(), edge));
        List<DiagramSceneNode> projectedNodes = common.nodes().stream().filter(position -> nodes.containsKey(position.id()))
                .map(position -> {
                    var node = nodes.get(position.id());
                    return new DiagramSceneNode(node.id(), node.label(), node.type(), node.relevance(), node.anchor(), node.layer(),
                            node.depth(), node.selectedForImpact(), node.parentId(), node.container(),
                            position.x(), position.y(), position.width(), position.height());
                }).toList();
        List<DiagramSceneEdge> projectedEdges = common.edges().stream().filter(position -> edges.containsKey(position.id()))
                .filter(position -> nodes.containsKey(position.sourceId()) && nodes.containsKey(position.targetId()))
                .map(position -> {
                    var edge = edges.get(position.id());
                    return new DiagramSceneEdge(edge.id(), edge.sourceId(), edge.targetId(), edge.relationType(), edge.relevance(),
                            edge.relationCategory(), position.sourceX(), position.sourceY(), position.targetX(), position.targetY(), edge.metadata());
                }).toList();
        return new DiagramScene(diagram.title(), common.width(), common.height(), common.direction(), projectedNodes, projectedEdges);
    }

    private static List<PropertyDifference> differences(List<String> sources) {
        List<Map<String, BlockAst>> versions = sources.stream()
                .map(source -> source == null ? null : ArchitectureSemanticPatch.index(source)).toList();
        Set<String> identities = new LinkedHashSet<>();
        versions.stream().filter(java.util.Objects::nonNull).forEach(version -> identities.addAll(version.keySet()));
        List<PropertyDifference> rows = new ArrayList<>();
        for (String identity : identities) {
            List<Map<String, List<String>>> values = versions.stream()
                    .map(version -> version == null ? null : values(version.get(identity))).toList();
            Set<String> keys = new LinkedHashSet<>();
            values.stream().filter(java.util.Objects::nonNull).forEach(properties -> keys.addAll(properties.keySet()));
            for (String key : keys) {
                List<List<String>> parts = values.stream().map(properties -> properties == null ? null : properties.getOrDefault(key, List.of())).toList();
                if (parts.stream().filter(java.util.Objects::nonNull).distinct().count() > 1) rows.add(new PropertyDifference(identity, key, category(identity, key),
                        parts.get(0), parts.get(1), parts.get(2), parts.get(3)));
            }
        }
        return List.copyOf(rows);
    }

    private static Map<String, List<String>> values(BlockAst block) {
        if (block == null) return Map.of();
        Map<String, List<String>> result = new LinkedHashMap<>();
        // Header values may change while the kind-aware semantic identity remains the same.
        result.put("@header", List.of(String.join(" ", block.getHeaderTokens())));
        block.getProperties().forEach(property -> result.computeIfAbsent(property.key(), ignored -> new ArrayList<>()).add(property.value()));
        result.replaceAll((key, value) -> "view".equals(block.getKind()) && "include".equals(key)
                ? value.stream().distinct().sorted().toList() : List.copyOf(value));
        return result;
    }

    private static String category(String identity, String property) {
        if (property.startsWith("x-exchange-")) return "EXCHANGE_EVIDENCE";
        if (identity.startsWith("view:") || PRESENTATION_PROPERTIES.contains(property)) return "PRESENTATION";
        return "SEMANTIC";
    }
}
