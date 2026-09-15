package com.taxonomy.workspace.service;

import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceInfoFailureTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void metadataOutagesCannotBecomeReadyBrowserIdentities(boolean activeLookup) {
        var rows = mock(UserWorkspaceRepository.class);
        var manager = spy(new WorkspaceManager(rows, 10,
                mock(SystemRepositoryService.class), mock(DslGitRepositoryFactory.class)));
        doReturn(new UserWorkspaceState("owner", 10)).when(manager).getOrCreateWorkspace("owner");
        var outage = new DataAccessResourceFailureException("metadata unavailable");
        if (activeLookup) doThrow(outage).when(manager).findActiveWorkspace("owner");
        else {
            doReturn(null).when(manager).findActiveWorkspace("owner");
            when(rows.findByUsernameAndSharedFalse("owner")).thenThrow(outage);
        }
        assertSame(outage, assertThrows(DataAccessResourceFailureException.class,
                () -> manager.getWorkspaceInfo("owner")));
    }

    @Test
    void absentMetadataDoesNotCertifyAVolatileIdentityAsReady() {
        var rows = mock(UserWorkspaceRepository.class);
        var manager = spy(new WorkspaceManager(rows, 10,
                mock(SystemRepositoryService.class), mock(DslGitRepositoryFactory.class)));
        doReturn(new UserWorkspaceState("owner", 10)).when(manager).getOrCreateWorkspace("owner");
        doReturn(null).when(manager).findActiveWorkspace("owner");
        when(rows.findByUsernameAndSharedFalse("owner")).thenReturn(Optional.empty());
        assertEquals("NOT_PROVISIONED", manager.getWorkspaceInfo("owner").provisioningStatus());
    }
}
