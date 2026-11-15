/* Working-model scene adapter. Geometry and metadata remain owned by the server. */
window.ArchitectureEditorRenderer = function (element, onSelect, onRelation, onSummary, onRegion, onSuggestion, onStateChange) {
    var diagram = window.TaxonomyArchitectureDiagram;
    var controller = diagram.create(element, {
        onSelect: onSelect, onRelation: onRelation, onSummary: onSummary,
        onRegion: onRegion, onSuggestion: onSuggestion, onStateChange: onStateChange
    });
    var render = controller.render;
    controller.render = function (scene, selected, typeRoots) {
        var palette = diagram.taxonomyPalette(), roots = typeRoots || {};
        var styled = Object.assign({}, scene, {
            nodes: (scene.nodes || []).map(function (node) {
                var type = Object.hasOwn(roots, node.type) ? roots[node.type] : node.type;
                var color = palette.layer(type);
                var styledNode = Object.assign({}, node, { color: color, textColor: diagram.readableTextColor(color) });
                var display = window.TaxonomyResponsibilityDisplay;
                var unit = node.responsibility && display?.enabled() && display.unit?.(node.responsibility);
                if (unit) {
                    styledNode.responsibleUnit = unit;
                    styledNode.ariaLabel = (node.label || node.id) + ' · ' + node.id + ' · '
                        + window.TaxonomyI18n.t('views.diagram.responsibleUnit', unit.name);
                    styledNode.tooltip = styledNode.ariaLabel + '\n' + window.TaxonomyI18n.t('responsibility.source', unit.sourceId);
                }
                return styledNode;
            }),
            edges: (scene.edges || []).map(function (edge) {
                return Object.assign({}, edge, { color: palette.relation(edge.relationType) });
            })
        });
        return render(styled, selected);
    };
    return controller;
};
