/* Working-model scene adapter. Geometry and metadata remain owned by the server. */
window.ArchitectureEditorRenderer = function (element, onSelect, onRelation, onSummary, onRegion, onSuggestion) {
    return window.TaxonomyArchitectureDiagram.create(element, {
        onSelect: onSelect, onRelation: onRelation, onSummary: onSummary,
        onRegion: onRegion, onSuggestion: onSuggestion
    });
};
