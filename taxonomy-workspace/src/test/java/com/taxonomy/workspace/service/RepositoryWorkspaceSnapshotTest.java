package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepositoryWorkspaceSnapshotTest {
    @Test
    void workingCopyContentAndProvenanceUseOneCapturedCommit() throws Exception {
        try (var factory = spy(new DslGitRepositoryFactory(null))) {
            var source = spy(factory.getSystemRepository());
            doReturn(source).when(factory).getCentralRepository("source");
            String captured = source.commitDsl("approved", "captured content", "owner", "Captured");
            var first = new AtomicBoolean(true);
            doAnswer(call -> {
                String head = (String) call.callRealMethod();
                if (first.getAndSet(false)) source.commitDsl("approved", "newer content", "other", "Concurrent change");
                return head;
            }).when(source).getHeadCommit("approved");
            var metadata = new SystemRepository(); metadata.setRepositoryId("source"); metadata.setDefaultBranch("approved");
            var catalog = mock(SystemRepositoryService.class);
            when(catalog.getRepository("source")).thenReturn(metadata);
            var rows = mock(UserWorkspaceRepository.class);
            when(rows.save(any(UserWorkspace.class))).thenAnswer(call -> call.getArgument(0));
            var service = new RepositoryWorkspaceService(rows, catalog, factory);
            var result = service.createWorkingCopy("owner", "source", "approved", "Working copy", null);
            var destination = factory.openWorkspaceRepository(result.getWorkspaceId());
            assertEquals(captured, result.getBaseCommit());
            assertEquals("captured content", destination.getDslAtHead("draft"));
            assertEquals(result.getCurrentCommit(), destination.getHeadCommit("draft"));
            assertEquals(result.getCurrentCommit(), destination.getHeadCommit("sync-base"));
            assertEquals(1, destination.getDslHistory("draft").size());
            assertNotEquals(captured, source.getHeadCommit("approved"));
        }
    }
}
