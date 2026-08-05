package com.taxonomy.export;

import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.dto.RequirementArchitectureView;

import java.util.Objects;

/**
 * Projects an already persisted architecture view without applying the current
 * user preference policy a second time.
 *
 * <p>The view pipeline has already selected and curated its elements. Replaying
 * a snapshot must therefore remain independent of later preference changes.</p>
 */
public final class PersistedDiagramProjection {

    private PersistedDiagramProjection() {
    }

    public static DiagramModel project(
            DiagramProjectionService projectionService,
            RequirementArchitectureView view,
            String title) {
        Objects.requireNonNull(projectionService, "projectionService");
        return projectionService.projectRaw(view, title);
    }
}
