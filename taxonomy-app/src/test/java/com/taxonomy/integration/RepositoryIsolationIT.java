package com.taxonomy.integration;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.ExternalGitSyncService;
import com.taxonomy.workspace.service.SyncIntegrationService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-level tests for repository isolation and cross-repository sync.
 *
 * <p>These tests verify the complete workspace isolation workflow using
 * in-memory Git repositories (no external dependencies required). They
 * exercise the full stack from factory to sync service.
 *
 * <p>For end-to-end tests with a real Gitea server, see the Docker Compose
 * setup in {@code docker-compose.integration-test.yml}.
 */
@Tag("integration")
class RepositoryIsolationIT {

    private DslGitRepositoryFactory factory;

    private static final String BASE_DSL = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "architecture";
            }

            element CP-1023 type Capability {
              title: "Secure Voice";
            }
            """;

    private static final String ALICE_DSL = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "architecture";
            }

            element CP-1023 type Capability {
              title: "Secure Voice v2";
            }

            element CP-1024 type Capability {
              title: "alice-changed-element";
            }
            """;

    private static final String BOB_DSL = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "architecture";
            }

            element CP-1023 type Capability {
              title: "Secure Voice";
            }

            element CP-1025 type Capability {
              title: "bob-changed-element";
            }
            """;

    @BeforeEach
    void setUp() {
        factory = new DslGitRepositoryFactory(null); // in-memory
    }

    @Test
    void workspaceRepositories_areFullyIsolated() throws IOException {
        // Create two workspace repos and a system repo
        DslGitRepository sysRepo = factory.getSystemRepository();
        DslGitRepository wsAlice = factory.getWorkspaceRepository("alice-ws");
        DslGitRepository wsBob = factory.getWorkspaceRepository("bob-ws");

        // Initial content in system repo
        sysRepo.commitDsl("draft", BASE_DSL, "system", "initial");

        // Fork into both workspaces
        wsAlice.commitDsl("main", BASE_DSL, "alice", "fork");
        wsBob.commitDsl("main", BASE_DSL, "bob", "fork");

        // Alice modifies her workspace
        wsAlice.commitDsl("main", ALICE_DSL, "alice", "update voice");

        // Bob should still see base DSL
        assertEquals(BASE_DSL, wsBob.getDslAtHead("main"),
                "Bob should not see Alice's changes");

        // System repo should still have base DSL
        assertEquals(BASE_DSL, sysRepo.getDslAtHead("draft"),
                "System repo should not see Alice's workspace changes");

        // Alice should see her own changes
        assertTrue(wsAlice.getDslAtHead("main").contains("alice-changed-element"),
                "Alice should see her own changes");
    }

    @Test
    void publishAndSync_fullWorkflow() throws IOException {
        DslGitRepository sysRepo = factory.getSystemRepository();

        // System repo starts with base content
        sysRepo.commitDsl("draft", BASE_DSL, "system", "initial");

        // Alice forks and modifies
        DslGitRepository wsAlice = factory.getWorkspaceRepository("ws-alice");
        wsAlice.commitDsl("main", BASE_DSL, "alice", "fork");
        wsAlice.commitDsl("main", ALICE_DSL, "alice", "update");

        // Alice publishes: copy workspace content to system repo
        String aliceDsl = wsAlice.getDslAtHead("main");
        sysRepo.commitDsl("draft", aliceDsl, "alice", "Published from workspace");

        // Verify system repo now has Alice's changes
        assertTrue(sysRepo.getDslAtHead("draft").contains("alice-changed-element"),
                "System repo should have Alice's published changes");

        // Bob syncs: copy system content to workspace
        DslGitRepository wsBob = factory.getWorkspaceRepository("ws-bob");
        String sharedDsl = sysRepo.getDslAtHead("draft");
        wsBob.commitDsl("main", sharedDsl, "bob", "Synced from shared");

        // Bob should now see Alice's changes
        assertTrue(wsBob.getDslAtHead("main").contains("alice-changed-element"),
                "Bob should see Alice's changes after sync");
    }

    @Test
    void multipleWorkspaces_sameUser() throws IOException {
        DslGitRepository sysRepo = factory.getSystemRepository();
        sysRepo.commitDsl("draft", BASE_DSL, "system", "initial");

        // Alice creates two workspaces
        DslGitRepository wsAlpha = factory.getWorkspaceRepository("alice-alpha");
        DslGitRepository wsBeta = factory.getWorkspaceRepository("alice-beta");

        // Fork into both
        wsAlpha.commitDsl("main", BASE_DSL, "alice", "fork");
        wsBeta.commitDsl("main", BASE_DSL, "alice", "fork");

        // Modify only alpha
        wsAlpha.commitDsl("main", ALICE_DSL, "alice", "alpha change");

        // Beta should still have base DSL
        assertEquals(BASE_DSL, wsBeta.getDslAtHead("main"),
                "Beta workspace should not see Alpha's changes");
    }

    @Test
    void evictedWorkspace_createsNewInstance() throws IOException {
        DslGitRepository ws = factory.getWorkspaceRepository("evict-test");
        ws.commitDsl("main", BASE_DSL, "alice", "initial");

        // Evict and recreate
        factory.evict("evict-test");
        DslGitRepository wsNew = factory.getWorkspaceRepository("evict-test");

        // New instance should have no content (in-memory mode)
        assertNull(wsNew.getDslAtHead("main"),
                "Evicted workspace should have no content");
    }

    @Test
    void systemRepository_persistsAcrossAccesses() throws IOException {
        DslGitRepository sys1 = factory.getSystemRepository();
        sys1.commitDsl("draft", BASE_DSL, "system", "initial");

        DslGitRepository sys2 = factory.getSystemRepository();
        assertEquals(BASE_DSL, sys2.getDslAtHead("draft"),
                "System repo should persist content across accesses (same instance)");
    }
}
