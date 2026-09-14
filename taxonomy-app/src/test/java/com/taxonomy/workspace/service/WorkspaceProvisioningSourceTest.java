package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceProvisioningSourceTest {
    static Stream<Arguments> sourceFailures() {
        return Stream.of(Arguments.of(false, false), Arguments.of(true, false),
                Arguments.of(false, true), Arguments.of(true, true));
    }
    @ParameterizedTest
    @MethodSource("sourceFailures")
    void absentSourceCheckpointFailsProvisioningWithoutPublishingReady(boolean isolated, boolean hasHead) throws Exception {
        var repository = mock(UserWorkspaceRepository.class);
        var system = mock(SystemRepositoryService.class);
        var source = hasHead ? mock(DslGitRepository.class) : new DslGitRepository();
        if (hasHead) when(source.getHeadCommit("draft")).thenReturn("1".repeat(40));
        var factory = mock(DslGitRepositoryFactory.class);
        when(factory.getSystemRepository()).thenReturn(source);
        when(factory.getWorkspaceRepository("workspace-source-test")).thenReturn(new DslGitRepository());
        var metadata = new SystemRepository();
        metadata.setRepositoryId("central-source-test");
        metadata.setDefaultBranch("draft");
        when(system.getPrimaryRepository()).thenReturn(metadata);
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId("workspace-source-test");
        workspace.setUsername("source-test");
        workspace.setCurrentBranch("draft");
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        workspace.setCreatedAt(Instant.now());
        when(repository.findByWorkspaceId(workspace.getWorkspaceId())).thenReturn(Optional.of(workspace));
        when(repository.claimProvisioning(anyString(), anyString(),
                eq(WorkspaceProvisioningStatus.PROVISIONING), anyCollection())).thenReturn(1);
        when(repository.save(any(UserWorkspace.class))).thenAnswer(call -> call.getArgument(0));
        var manager = isolated ? new WorkspaceManager(repository, 50, system, factory)
                : new WorkspaceManager(repository, 50, system, source);

        var failure = assertThrows(RuntimeException.class,
                () -> manager.provisionWorkspaceRepository("source-test", workspace.getWorkspaceId()));
        assertEquals(WorkspaceProvisioningStatus.FAILED, workspace.getProvisioningStatus());
        assertNotNull(workspace.getProvisioningError());
        assertNull(workspace.getProvisionedAt());
        assertNull(workspace.getCurrentCommit());
        assertTrue(failure.getCause() instanceof IllegalStateException);
        if (isolated) verify(factory, never()).getWorkspaceRepository(anyString());
    }
}
