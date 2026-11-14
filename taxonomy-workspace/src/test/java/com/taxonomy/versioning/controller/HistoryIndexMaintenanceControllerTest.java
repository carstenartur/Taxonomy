package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.DslOperationsFacade;
import com.taxonomy.versioning.service.HistoryIndexMaintenance;
import com.taxonomy.versioning.service.HistoryIndexJournal.Kind;
import com.taxonomy.versioning.service.HistoryIndexJournal.Receipt;
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

    @Mock
    private HistoryIndexMaintenance maintenance;

    static Receipt done(Kind kind, String branch, int count) {
        return new Receipt("id", "cause", kind, "repo", "workspace", branch, "a".repeat(40), "actor", "COMPLETED",
                1, count, "executor", "APPLIED", 1L, 2L, 3L, null);
    }

    @Test
    void rebuildNormalizesAndDelegatesToRepositoryScopedFacadeOperation() {
        when(operations.maintainHistory(Kind.REBUILD, "review", "k", null)).thenReturn(done(Kind.REBUILD, "review", 7));
        HistoryIndexMaintenanceController controller =
                new HistoryIndexMaintenanceController(operations, maintenance);

        var response = controller.rebuild("  review  ", "k", null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("operation", "rebuild")
                .containsEntry("branch", "review")
                .containsEntry("indexed", 7);
        verify(operations).maintainHistory(Kind.REBUILD, "review", "k", null);
    }

    @Test
    void rebuildRejectsBlankBranchWithoutTouchingTheTenantFacade() {
        HistoryIndexMaintenanceController controller =
                new HistoryIndexMaintenanceController(operations, maintenance);

        var response = controller.rebuild("   ", null, null);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getBody())
                .containsEntry("operation", "rebuild")
                .containsEntry("error", "branch must not be blank");
        verifyNoInteractions(operations);
    }

    @Test
    void rebuildRejectsNullBranchWithoutTouchingTheTenantFacade() {
        HistoryIndexMaintenanceController controller =
                new HistoryIndexMaintenanceController(operations, maintenance);

        var response = controller.rebuild(null, null, null);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getBody())
                .containsEntry("operation", "rebuild")
                .containsEntry("error", "branch must not be blank");
        verifyNoInteractions(operations);
    }

    @Test
    void purgeDelegatesToExactTenantFacadeOperation() {
        when(operations.maintainHistory(Kind.PURGE, null, null, "cause")).thenReturn(done(Kind.PURGE, "draft", 3));
        HistoryIndexMaintenanceController controller =
                new HistoryIndexMaintenanceController(operations, maintenance);

        var response = controller.purge(null, "cause");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("operation", "purge")
                .containsEntry("purged", 3);
        verify(operations).maintainHistory(Kind.PURGE, null, null, "cause");
    }
}
