/* Working-model scene adapter. Geometry and metadata remain owned by the server. */
window.ArchitectureEditorRenderer = function (element, onSelect, onRelation, onSummary, onRegion, onSuggestion) {
    var diagram = window.TaxonomyArchitectureDiagram;
    var controller = diagram.create(element, {
        onSelect: onSelect, onRelation: onRelation, onSummary: onSummary,
        onRegion: onRegion, onSuggestion: onSuggestion
    });
    var render = controller.render;
    controller.render = function (scene, selected, typeRoots) {
        var palette = diagram.taxonomyPalette(), roots = typeRoots || {};
        var styled = Object.assign({}, scene, {
            nodes: (scene.nodes || []).map(function (node) {
                var type = Object.hasOwn(roots, node.type) ? roots[node.type] : node.type;
                var color = palette.layer(type);
                return Object.assign({}, node, { color: color, textColor: diagram.readableTextColor(color) });
            }),
            edges: (scene.edges || []).map(function (edge) {
                return Object.assign({}, edge, { color: palette.relation(edge.relationType) });
            })
        });
        return render(styled, selected);
    };
    return controller;
};
