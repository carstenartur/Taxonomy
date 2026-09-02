package com.taxonomy.portfolio.workbench;

import com.taxonomy.export.service.CanonicalDiagramExportService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ArchitectureSnapshotExportHeaderSafetyTest {

    private static final Long PROJECT_ID = 42L;
    private static final WorkspaceContext CONTEXT =
            new WorkspaceContext("alice", "workspace-a", "feature-a");

    private final ArchitectureWorkbenchService workbenchService =
            mock(ArchitectureWorkbenchService.class);
    private final CanonicalDiagramExportService diagramExportService =
            mock(CanonicalDiagramExportService.class);
    private final ArchitectureSnapshotExportService service =
            new ArchitectureSnapshotExportService(
                    workbenchService,
                    diagramExportService);

    @Test
    void rejectsHeaderUnsafeSnapshotIdsBeforeAuthorizationOrSerialization() {
        for (String snapshotId : List.of(
                "snapshot\rX-Injected: true",
                "snapshot\nX-Injected: true")) {
            PortfolioException exception = assertThrows(
                    PortfolioException.class,
                    () -> service.exportArchiMate(
                            PROJECT_ID,
                            snapshotId,
                            "alice",
                            CONTEXT));

            assertThat(exception.getKind())
                    .isEqualTo(PortfolioException.Kind.VALIDATION);
            assertThat(exception.getMessage())
                    .isEqualTo("snapshotId contains unsafe control characters")
                    .doesNotContain("Injected");
        }

        verifyNoInteractions(workbenchService, diagramExportService);
    }
}
