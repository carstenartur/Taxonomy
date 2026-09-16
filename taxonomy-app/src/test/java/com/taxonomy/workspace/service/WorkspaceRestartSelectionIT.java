package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real queries; fresh managers represent loss of process-local selection after restart. */
@SpringBootTest
@TestPropertySource(properties = {"gemini.api.key=", "openai.api.key=", "deepseek.api.key=",
        "qwen.api.key=", "llama.api.key=", "mistral.api.key="})
class WorkspaceRestartSelectionIT {
    @Autowired UserWorkspaceRepository repository;
    private final String owner = "restart-" + UUID.randomUUID();
    private final List<UserWorkspace> rows = new ArrayList<>();

    @AfterEach void cleanup() { repository.deleteAll(rows); }

    private UserWorkspace row(String id, boolean primary, boolean archived, boolean shared, int access) {
        var w = new UserWorkspace();
        w.setWorkspaceId(owner + "-" + id); w.setUsername(owner); w.setDisplayName(id);
        w.setDefault(primary); w.setArchived(archived); w.setShared(shared);
        w.setCurrentBranch("draft"); w.setBaseBranch("draft");
        w.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        w.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        w.setLastAccessedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(access));
        w = repository.saveAndFlush(w); rows.add(w); return w;
    }
    private WorkspaceManager restarted() {
        return new WorkspaceManager(repository, 50, mock(SystemRepositoryService.class), mock(DslGitRepository.class));
    }
    @Test void restartPrefersDefaultWithSeveralPrivateRows() {
        var expected = row("default", true, false, false, 1);
        row("recent", false, false, false, 20);
        assertEquals(expected.getWorkspaceId(), restarted().findUserWorkspace(owner).getWorkspaceId());
    }
    @Test void newestActivePrivateWorkspaceIsFallbackWithoutDefault() {
        row("older", false, false, false, 1);
        var expected = row("newest", false, false, false, 20);
        assertEquals(expected.getWorkspaceId(), restarted().findUserWorkspace(owner).getWorkspaceId());
    }
    @Test void fallbackOrderingIsStableWhenAccessTimesTie() {
        row("b", false, false, false, 1);
        var expected = row("a", false, false, false, 1);
        for (int i=0; i<3; i++) {
            assertEquals(expected.getWorkspaceId(), repository.findByUsernameAndSharedFalse(owner).orElseThrow().getWorkspaceId());
        }
    }
    @Test void archivedDefaultAndSharedWorkspaceCannotBeSelected() {
        row("archived", true, true, false, 100);
        row("shared", false, false, true, 200);
        var expected = row("private", false, false, false, 1);
        assertEquals(expected.getWorkspaceId(), restarted().findUserWorkspace(owner).getWorkspaceId());
    }
    @Test void archivedOnlyUserHasNoImplicitWorkspace() {
        row("archived", true, true, false, 1);
        assertTrue(repository.findByUsernameAndSharedFalse(owner).isEmpty());
        assertNull(restarted().findUserWorkspace(owner));
    }
    @Test void activeSelectionStillTakesPrecedenceOverDefault() {
        row("default", true, false, false, 100);
        var selected = row("selected", false, false, false, 1);
        var manager = restarted(); manager.getOrCreateWorkspace(owner, selected.getWorkspaceId());
        assertEquals(selected.getWorkspaceId(), manager.findUserWorkspace(owner).getWorkspaceId());
    }
    @Test void absentOwnerHasNoSelection() {
        assertTrue(repository.findByUsernameAndSharedFalse(owner).isEmpty());
        assertNull(restarted().findUserWorkspace(owner));
    }
}
