package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.DslOperationsFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoryIndexMaintenanceControllerTest {

    @Mock
    private DslOperationsFacade operations;

    @Test
    void rebuildNormalizesAndDelegatesToRepositoryScopedFacadeOperation() {
        when(operations.rebuildHistoryBranch("review")).thenReturn(7);
        HistoryIndexMaintenanceController controller =
                new HistoryIndexMaintenanceController(operations);

        var response = controller.rebuild("  review  ");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("operation", "rebuild")
                .containsEntry("branch", "review")
                .containsEntry("indexed", 7);
        verify(operations).rebuildHistoryBranch("review");
    }

    @Test
    void rebuildRejectsBlankBranchWithoutTouchingTheTenantFacade() {
        HistoryIndexMaintenanceController controller =
                new HistoryIndexMaintenanceController(operations);

        var response = controller.rebuild("   ");

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getBody())
                .containsEntry("operation", "rebuild")
                .containsEntry("error", "branch must not be blank");
        verifyNoInteractions(operations);
    }

    @Test
    void rebuildRejectsNullBranchWithoutTouchingTheTenantFacade() {
        HistoryIndexMaintenanceController controller =
                new HistoryIndexMaintenanceController(operations);

        var response = controller.rebuild(null);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getBody())
                .containsEntry("operation", "rebuild")
                .containsEntry("error", "branch must not be blank");
        verifyNoInteractions(operations);
    }

    @Test
    void purgeDelegatesToExactTenantFacadeOperation() {
        when(operations.purgeHistoryIndex()).thenReturn(3);
        HistoryIndexMaintenanceController controller =
                new HistoryIndexMaintenanceController(operations);

        var response = controller.purge();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("operation", "purge")
                .containsEntry("purged", 3);
        verify(operations).purgeHistoryIndex();
    }
}
