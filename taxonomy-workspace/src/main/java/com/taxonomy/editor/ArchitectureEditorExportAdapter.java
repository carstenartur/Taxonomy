package com.taxonomy.editor;

import com.taxonomy.diagram.DiagramCanonicalEvidence;
import com.taxonomy.editor.ArchitectureEditorExportPort.ExportDocument;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
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
        var analysisSources = view.model().getViews().stream()
                .filter(item -> viewId == null || viewId.isBlank() || viewId.equals(item.getId()))
                .filter(item -> item.getExtensions().containsKey("x-analysis-snapshot-id"))
                .map(item -> new DiagramCanonicalEvidence.AnalysisSource(item.getId(),
                        item.getExtensions().get("x-analysis-snapshot-id"), item.getExtensions().get("x-analysis-status")))
                .toList();
        var relationships = new LinkedHashMap<String, DiagramCanonicalEvidence.Relationship>();
        var displayed = view.scene().edges().stream().map(com.taxonomy.diagram.DiagramSceneEdge::id)
                .collect(java.util.stream.Collectors.toSet());
        for (var relation : view.model().getRelations()) {
            String id = relation.getSourceId() + " " + relation.getRelationType() + " " + relation.getTargetId();
            if (displayed.contains(id)) relationships.putIfAbsent(id, new DiagramCanonicalEvidence.Relationship(
                    relation.getStatus(), relation.getProvenance(), relation.getConfidence()));
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
                new DiagramCanonicalEvidence(viewId, analysisSources, relationships));
    }
}
