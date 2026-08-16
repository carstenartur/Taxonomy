package com.taxonomy.dsl.storage;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void catalogReservedStorageIsValidatedAndReused() {
        SystemRepositoryService catalog = mock(SystemRepositoryService.class);
        SystemRepository metadata = new SystemRepository();
        metadata.setRepositoryId("repo-a");
        metadata.setStorageRepositoryName("central-repo-a");
        when(catalog.getRepository("repo-a")).thenReturn(metadata);
        DslGitRepositoryFactory factory = new DslGitRepositoryFactory(null, catalog);

        DslGitRepository created = factory.createCentralRepository(
                "repo-a", "central-repo-a");

        assertSame(created, factory.getCentralRepository("repo-a"));
        assertThrows(IllegalArgumentException.class,
                () -> factory.createCentralRepository("repo-a", "wrong-storage"));
    }

    @Test
    void legacyCompatibilityRemainsBoundedToPrimaryRouting() throws IOException {
        DslGitRepositoryFactory factory = new DslGitRepositoryFactory(null);
        DslGitRepository primary = factory.getSystemRepository();
        primary.commitDsl(
                "draft", "meta { language: \"legacy\"; }", "system", "primary");

        assertSame(primary, factory.resolveRepository((WorkspaceContext) null));
        assertSame(primary, factory.resolveRepository(
                new WorkspaceContext("alice", null, "draft")));

        DslGitRepository legacyWorkspace = factory.resolveRepository(
                new WorkspaceContext("alice", "legacy-workspace", "draft"));
        assertEquals(
                "meta { language: \"legacy\"; }",
                legacyWorkspace.getDslAtHead("draft"));
    }

    @Test
    void invalidExplicitIdentitiesFailBeforeOpeningStorage() {
        DslGitRepositoryFactory factory = new DslGitRepositoryFactory(null);

        assertThrows(IllegalArgumentException.class,
                () -> factory.resolveRepository((RepositoryContext) null));
        assertThrows(IllegalArgumentException.class,
                () -> factory.getCentralRepository(" "));
        assertThrows(IllegalArgumentException.class,
                () -> factory.openWorkspaceRepository(null));
        assertThrows(IllegalArgumentException.class,
                () -> factory.createCentralRepository("repo-a", " "));
    }
}
