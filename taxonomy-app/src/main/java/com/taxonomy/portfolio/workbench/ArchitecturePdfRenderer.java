package com.taxonomy.portfolio.workbench;

import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.Projection;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Adapts portfolio snapshot metadata to the framework-neutral PDF export renderer. */
@Component
public class ArchitecturePdfRenderer {

    private final com.taxonomy.export.ArchitecturePdfRenderer delegate;

    public ArchitecturePdfRenderer(com.taxonomy.export.ArchitecturePdfRenderer delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public byte[] render(Projection projection) {
        if (projection == null || projection.scene() == null || projection.scene().isEmpty()) {
            throw new IllegalArgumentException("Architecture projection must contain a renderable scene");
        }
        var metadata = new com.taxonomy.export.ArchitecturePdfRenderer.SnapshotMetadata(
                projection.projectKey(),
                projection.requirementKey(),
                projection.snapshotId(),
                projection.provider(),
                projection.branchName(),
                projection.commitSha(),
                projection.warnings());
        return delegate.render(projection.scene(), metadata);
    }
}
