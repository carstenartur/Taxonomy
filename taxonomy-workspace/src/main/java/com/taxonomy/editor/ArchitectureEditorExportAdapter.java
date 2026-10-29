package com.taxonomy.editor;

import com.taxonomy.editor.ArchitectureEditorExportPort.ExportDocument;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
        return new ExportDocument(
                provenance.repositoryId(),
                provenance.workspaceScopeKey(),
                provenance.branch(),
                provenance.commit(),
                provenance.revision(),
                document.source(),
                view.schema().layoutMode(),
                view.scene());
    }
}
