package com.taxonomy.dsl.storage;

import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DslGitRepositoryFactoryRepositoryAwareCompatibilityTest {

    @Test
    void exactCentralCompatibilityContextDoesNotFallBackToPrimaryRepository() {
        DslGitRepositoryFactory factory = new DslGitRepositoryFactory(null);
        WorkspaceContext context = new WorkspaceContext(
                "alice", null, "main", "repo-a");

        DslGitRepository selected = factory.resolveRepository(context);

        assertSame(factory.getCentralRepository("repo-a"), selected);
        assertNotSame(factory.getSystemRepository(), selected);
    }

    @Test
    void exactWorkspaceCompatibilityContextNeverSeedsFromPrimaryRepository()
            throws IOException {
        DslGitRepositoryFactory factory = new DslGitRepositoryFactory(null);
        factory.getSystemRepository().commitDsl(
                "draft", "meta { language: \"primary\"; }", "system", "primary");
        WorkspaceContext context = new WorkspaceContext(
                "alice", "workspace-a", "main", "repo-a");

        DslGitRepository selected = factory.resolveRepository(context);

        assertSame(factory.openWorkspaceRepository("workspace-a"), selected);
        assertNull(selected.getDslAtHead("draft"));
    }
}
