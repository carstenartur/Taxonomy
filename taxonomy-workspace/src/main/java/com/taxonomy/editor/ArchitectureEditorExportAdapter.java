package com.taxonomy.editor;

import com.taxonomy.diagram.DiagramCanonicalEvidence;
import com.taxonomy.editor.ArchitectureEditorExportPort.ExportDocument;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projects one exact editor document into the neutral export boundary. */
@Component
public final class ArchitectureEditorExportAdapter implements ArchitectureEditorExportPort {

    private final ArchitectureEditorService service;
    private final ArchitectureEditorProjection projection;

    public ArchitectureEditorExportAdapter(
            ArchitectureEditorService service,
            ArchitectureEditorProjection projection) {
        this.service = Objects.requireNonNull(service, "service");
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    @Override
    public CheckpointModel readCheckpoint(RepositoryContext context, String commit) throws IOException {
        if (commit == null || commit.isBlank()) throw new IllegalArgumentException("Select an exact Git checkpoint");
        var document = service.read(context, commit);
        return new CheckpointModel(document.context().commit(), document.dsl());
    }

    @Override
    public ExportDocument read(
            RepositoryContext context,
            String commit,
            Long revision) throws IOException {
        return read(context, commit, revision, com.taxonomy.diagram.DiagramLayout.Strategy.STABLE, null);
    }

    @Override
    public ExportDocument read(RepositoryContext context, String commit, Long revision,
            com.taxonomy.diagram.DiagramLayout.Strategy strategy, Long layoutRevision) throws IOException {
        return read(context, commit, revision, strategy, layoutRevision, null);
    }

    @Override
    public ExportDocument read(RepositoryContext context, String commit, Long revision,
            com.taxonomy.diagram.DiagramLayout.Strategy strategy, Long layoutRevision, String viewId) throws IOException {
        var document = service.read(context, commit, revision);
        ArchitectureEditorProjection.requireLayoutRevision(document, strategy, layoutRevision);
        var reference = layoutRevision == null ? null : service.read(context, null, layoutRevision);
        var view = projection.project(document, false, strategy, reference, viewId);
        var provenance = document.context();
        var selectedViews = view.model().getViews().stream()
                .filter(item -> viewId == null || viewId.isBlank() || viewId.equals(item.getId()))
                .toList();
        // The request selects an existing view; only its canonical identity is part of the export.
        String selectedId = viewId == null || viewId.isBlank() ? null : selectedViews.getFirst().getId();
        var analysisSources = selectedViews.stream()
                .filter(item -> item.getExtensions().containsKey("x-analysis-snapshot-id"))
                .map(item -> new DiagramCanonicalEvidence.AnalysisSource(item.getId(),
                        item.getExtensions().get("x-analysis-snapshot-id"), item.getExtensions().get("x-analysis-status")))
                .toList();
        var relationships = new LinkedHashMap<String, DiagramCanonicalEvidence.Relationship>();
        var displayed = view.scene().edges().stream().map(com.taxonomy.diagram.DiagramSceneEdge::id)
                .collect(java.util.stream.Collectors.toSet());
        var witnesses = new HashMap<String, List<DiagramCanonicalEvidence.Witness>>();
        var selectedSource = viewId == null || viewId.isBlank() ? null : analysisSources.stream().findFirst().orElse(null);
        for (var evidence : view.model().getEvidence()) {
            String edge = evidence.getForRelationSource() + " " + evidence.getForRelationType() + " " + evidence.getForRelationTarget();
            var fields = evidence.getExtensions();
            String sourceView = fields.get("x-analysis-view-id");
            String snapshot = fields.get("x-analysis-snapshot-id");
            if (!displayed.contains(edge)) continue;
            if (selectedSource != null && (sourceView == null && snapshot == null
                    || sourceView != null && !sourceView.equals(selectedSource.viewId())
                    || snapshot != null && !snapshot.equals(selectedSource.snapshotId()))) continue;
            witnesses.computeIfAbsent(edge, key -> new ArrayList<>()).add(new DiagramCanonicalEvidence.Witness(
                    evidence.getId(), evidence.getSummary(), sourceView, snapshot, evidence.getEvidenceType(), evidence.getModel(),
                    evidence.getConfidence(), fields.get("x-analysis-origin"), fields.get("x-analysis-direction"), fields.get("x-analysis-outcome"),
                    fields.get("x-analysis-necessity"), fields.get("x-analysis-alternative-group"), fields.get("x-analysis-question"),
                    endpoint(fields, "assessed"), endpoint(fields, "candidate")));
        }
        for (var relation : view.model().getRelations()) {
            String id = relation.getSourceId() + " " + relation.getRelationType() + " " + relation.getTargetId();
            if (displayed.contains(id)) relationships.putIfAbsent(id, new DiagramCanonicalEvidence.Relationship(
                    relation.getStatus(), relation.getProvenance(), relation.getConfidence(), witnesses.getOrDefault(id, List.of())));
        }
        return new ExportDocument(
                provenance.repositoryId(),
                provenance.workspaceScopeKey(),
                provenance.branch(),
                provenance.commit(),
                provenance.revision(),
                document.source(),
                view.schema().layoutMode(),
                view.scene(),
                new DiagramCanonicalEvidence(selectedId, analysisSources, relationships));
    }

    private static DiagramCanonicalEvidence.Endpoint endpoint(Map<String, String> fields, String which) {
        String prefix = "x-analysis-" + which + "-";
        return new DiagramCanonicalEvidence.Endpoint(fields.get(prefix + "id"), fields.get(prefix + "contribution"),
                fields.get(prefix + "quote"), fields.get(prefix + "condition"));
    }
}
