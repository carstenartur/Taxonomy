package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A retry must retain the repository and branch selected before its first failure. */
class WorkspaceProvisioningProvenanceTest {
    private static final String CAPTURED = "meta { language: \"taxdsl\"; version: \"selected\"; }";
    private static final String WRONG = "meta { language: \"taxdsl\"; version: \"other-source\"; }";
    private static final String LATER = "meta { language: \"taxdsl\"; version: \"later\"; }";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesTheRecordedRepositoryAndBranchAtOneCapturedCommit(boolean primarySource) throws Exception {
        try (var factory = spy(new DslGitRepositoryFactory(null)); var secondary = new DslGitRepository()) {
            var primary = factory.getSystemRepository();
            String primaryDraft = primary.commitDsl("draft", WRONG, "system", "Unrelated default");
            var selected = spy(primarySource ? primary : secondary);
            String captured = selected.commitDsl("release/approved", CAPTURED, "source-owner", "Selected source");
            doAnswer(call -> {
                String content = (String) call.callRealMethod();
                selected.commitDsl("release/approved", LATER, "source-owner", "Concurrent source edit");
                return content;
            }).when(selected).getDslAtCommit(captured);
            String id = primarySource ? "primary-id" : "secondary-id";
            doReturn(selected).when(factory).getCentralRepository(id);
            var row = pending(id);
            var rows = rows(row);
            var catalog = mock(SystemRepositoryService.class);
            when(catalog.getPrimaryRepository()).thenReturn(source("primary-id"));
            when(catalog.getRepository(id)).thenReturn(source(id));
            var manager = new WorkspaceManager(rows, 50, catalog, factory);
            clearInvocations(factory, catalog);

            UserWorkspace ready = manager.provisionWorkspaceRepository("retry-owner", row.getWorkspaceId());

            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertEquals(id, ready.getSourceRepositoryId());
            assertEquals("release/approved", ready.getSourceBranch());
            assertEquals("release/approved", ready.getBaseBranch());
            assertEquals("release/approved", ready.getSyncTargetBranch());
            assertEquals("draft", ready.getCurrentBranch(), "Do not move a retried working copy to another branch");
            assertEquals(captured, ready.getBaseCommit());
            assertNotEquals(captured, selected.getHeadCommit("release/approved"));
            var destination = factory.openWorkspaceRepository(row.getWorkspaceId());
            assertEquals(CAPTURED, destination.getDslAtHead("draft"));
            assertEquals(destination.getHeadCommit("draft"), ready.getCurrentCommit());
            assertEquals(List.of("draft"), destination.getBranchNames());
            assertEquals(1, destination.getDslHistory("draft").size());
            assertEquals(primaryDraft, primary.getHeadCommit("draft"));
            verify(catalog, never()).getPrimaryRepository();
            verify(factory, never()).getSystemRepository();
            verify(factory, never()).getWorkspaceRepository(anyString());
        }
    }

    enum UnavailableSource { MISSING_CATALOG, ARCHIVED, MISSING_BRANCH, BLANK_DESTINATION }

    @ParameterizedTest
    @EnumSource(UnavailableSource.class)
    void cannotReplaceAnUnavailableRecordedSourceWithThePrimaryRepository(UnavailableSource unavailable) throws Exception {
        try (var factory = spy(new DslGitRepositoryFactory(null)); var secondary = new DslGitRepository()) {
            factory.getSystemRepository().commitDsl("draft", WRONG, "system", "Unrelated default");
            var row = pending("secondary-id");
            var rows = rows(row);
            var metadata = source("secondary-id");
            if (unavailable == UnavailableSource.BLANK_DESTINATION) row.setCurrentBranch(" ");
            if (unavailable == UnavailableSource.ARCHIVED) metadata.setLifecycleState(RepositoryLifecycleState.ARCHIVED);
            var catalog = mock(SystemRepositoryService.class);
            when(catalog.getPrimaryRepository()).thenReturn(source("primary-id"));
            if (unavailable == UnavailableSource.MISSING_CATALOG) {
                when(catalog.getRepository("secondary-id")).thenThrow(new IllegalStateException("Repository was removed"));
            } else when(catalog.getRepository("secondary-id")).thenReturn(metadata);
            if (unavailable != UnavailableSource.MISSING_BRANCH) {
                secondary.commitDsl("release/approved", CAPTURED, "source-owner", "Selected source");
            }
            doReturn(secondary).when(factory).getCentralRepository("secondary-id");
            var manager = new WorkspaceManager(rows, 50, catalog, factory);
            clearInvocations(factory, catalog);

            assertThrows(RuntimeException.class,
                    () -> manager.provisionWorkspaceRepository("retry-owner", row.getWorkspaceId()));

            assertEquals(WorkspaceProvisioningStatus.FAILED, row.getProvisioningStatus());
            assertEquals("secondary-id", row.getSourceRepositoryId());
            assertEquals("release/approved", row.getSourceBranch());
            assertEquals(unavailable == UnavailableSource.BLANK_DESTINATION ? " " : "draft", row.getCurrentBranch());
            assertNull(row.getCurrentCommit());
            assertNull(row.getProvisionedAt());
            verify(catalog, never()).getPrimaryRepository();
            verify(factory, never()).openWorkspaceRepository(anyString());
            verify(factory, never()).getWorkspaceRepository(anyString());
        }
    }

    @Test
    void legacyProvisioningCreatesItsBranchAtTheCapturedCommit() throws Exception {
        try (var storage = new DslGitRepository()) {
            var git = spy(storage);
            String captured = git.commitDsl("draft", CAPTURED, "system", "Captured fork point");
            doAnswer(call -> {
                String content = (String) call.callRealMethod();
                git.commitDsl("draft", LATER, "system", "Concurrent source edit");
                return content;
            }).when(git).getDslAtCommit(captured);
            var row = pending(null);
            row.setSourceBranch(null);
            var catalog = mock(SystemRepositoryService.class);
            when(catalog.getPrimaryRepository()).thenReturn(source("primary-id"));
            var manager = new WorkspaceManager(rows(row), 50, catalog, git);

            UserWorkspace ready = manager.provisionWorkspaceRepository("retry-owner", row.getWorkspaceId());

            assertEquals(captured, ready.getBaseCommit());
            assertEquals(captured, ready.getCurrentCommit());
            assertEquals(captured, git.getHeadCommit(ready.getCurrentBranch()));
            assertEquals(CAPTURED, git.getDslAtHead(ready.getCurrentBranch()));
            assertNotEquals(captured, git.getHeadCommit("draft"));
            assertEquals(1, git.getDslHistory(ready.getCurrentBranch()).size());
        }
    }

    @Test
    void legacyStorageCannotBeReboundToAnotherRecordedRepository() throws Exception {
        try (var storage = new DslGitRepository()) {
            storage.commitDsl("draft", WRONG, "system", "Primary default");
            storage.commitDsl("release/approved", WRONG, "system", "Still primary content");
            var row = pending("secondary-id");
            var catalog = mock(SystemRepositoryService.class);
            when(catalog.getPrimaryRepository()).thenReturn(source("primary-id"));
            when(catalog.getRepository("secondary-id")).thenReturn(source("secondary-id"));
            var manager = new WorkspaceManager(rows(row), 50, catalog, storage);

            assertThrows(RuntimeException.class,
                    () -> manager.provisionWorkspaceRepository("retry-owner", row.getWorkspaceId()));

            assertEquals(WorkspaceProvisioningStatus.FAILED, row.getProvisioningStatus());
            assertEquals("secondary-id", row.getSourceRepositoryId());
            assertEquals("release/approved", row.getSourceBranch());
            assertNull(row.getCurrentCommit());
            assertNull(storage.getHeadCommit("retry-owner/workspace/retry-workspace"));
        }
    }

    private static UserWorkspace pending(String sourceId) {
        var row = new UserWorkspace();
        row.setWorkspaceId("retry-workspace");
        row.setUsername("retry-owner");
        row.setCurrentBranch("draft");
        row.setSourceRepositoryId(sourceId);
        row.setSourceBranch("release/approved");
        row.setBaseBranch("release/approved");
        row.setSyncTargetBranch("release/approved");
        row.setProvisioningStatus(WorkspaceProvisioningStatus.FAILED);
        row.setCreatedAt(Instant.now());
        return row;
    }

    private static SystemRepository source(String id) {
        var source = new SystemRepository();
        source.setRepositoryId(id);
        source.setDefaultBranch("draft");
        source.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        source.setLifecycleState(RepositoryLifecycleState.ACTIVE);
        return source;
    }

    private static UserWorkspaceRepository rows(UserWorkspace row) {
        var rows = mock(UserWorkspaceRepository.class);
        when(rows.findByWorkspaceId(row.getWorkspaceId())).thenReturn(Optional.of(row));
        when(rows.claimProvisioning(anyString(), anyString(),
                eq(WorkspaceProvisioningStatus.PROVISIONING), anyCollection())).thenReturn(1);
        when(rows.save(any(UserWorkspace.class))).thenAnswer(call -> call.getArgument(0));
        return rows;
    }
}
