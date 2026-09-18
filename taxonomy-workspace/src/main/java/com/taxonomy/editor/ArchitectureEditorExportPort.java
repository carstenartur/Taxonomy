package com.taxonomy.editor;

import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.workspace.service.RepositoryContext;

import java.io.IOException;
import java.util.Objects;

/**
 * Workspace/editor-owned read boundary for exact, render-ready architecture exports.
 *
 * <p>The port exposes only neutral scene/provenance data. HTTP composition and
 * concrete SVG/PDF rendering remain outside the workspace authority boundary.</p>
 */
public interface ArchitectureEditorExportPort {

    ExportDocument read(RepositoryContext context, String commit, Long revision) throws IOException;

    record ExportDocument(
            String repositoryId,
            String workspaceScopeKey,
            String branch,
            String commit,
            long revision,
            String source,
            String layoutMode,
            DiagramScene scene) {
        public ExportDocument {
            repositoryId = Objects.requireNonNull(repositoryId, "repositoryId");
            workspaceScopeKey = Objects.requireNonNull(workspaceScopeKey, "workspaceScopeKey");
            branch = Objects.requireNonNull(branch, "branch");
            source = Objects.requireNonNull(source, "source");
            layoutMode = Objects.requireNonNull(layoutMode, "layoutMode");
            scene = Objects.requireNonNull(scene, "scene");
        }
    }
}
