package com.taxonomy.editor;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceCollaborationService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceCollaborationServiceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void staleMetadataMergeCannotReverseAnOwnerSharingDecision(boolean enabled) throws Exception {
        try (var persistence = new EditorPersistenceFixture("jdbc:hsqldb:mem:sharing-merge-" + java.util.UUID.randomUUID(), UserWorkspace.class)) {
            var transactions = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.orm.jpa.JpaTransactionManager(persistence.factory));
            var entities = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(persistence.factory);
            var rows = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(entities)
                    .getRepository(UserWorkspaceRepository.class);
            var workspace = workspace(); workspace.setCreatedAt(java.time.Instant.now()); workspace.setCollaborative(!enabled);
            transactions.executeWithoutResult(status -> rows.saveAndFlush(workspace));
            var stale = transactions.execute(status -> rows.findByWorkspaceId("copy").orElseThrow());
            var editor = mock(ArchitectureEditorService.class);
            var owner = new RepositoryContext("repo", "copy", "draft", "owner",
                    com.taxonomy.workspace.service.RepositoryScope.WORKSPACE, !enabled);
            var expected = ArchitectureCommandPort.Context.of(owner, null, 0);
            when(editor.read(owner, null)).thenReturn(new ArchitectureEditorService.Document(expected, "", "READY",
                    java.util.List.of(), java.util.List.of(), 0, null, "WORKSPACE_REVISION"));
            var service = new WorkspaceCollaborationService(rows, editor, activeRepositories());
            service.setActorAccess(username -> {});
            transactions.executeWithoutResult(status -> {
                try { assertThat(service.setEnabled(owner, expected, enabled).enabled()).isEqualTo(enabled); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            var toggled = transactions.execute(status -> rows.findByWorkspaceId("copy").orElseThrow().isCollaborative());
            assertThat(toggled).isEqualTo(enabled);
            stale.setDescription("Unrelated metadata saved from an older request");
            transactions.executeWithoutResult(status -> rows.saveAndFlush(stale));
            var retained = transactions.execute(status -> rows.findByWorkspaceId("copy").orElseThrow());
            assertThat(retained.getDescription()).isEqualTo(stale.getDescription());
            assertThat(retained.isCollaborative()).as("only the owner sharing operation may update its flag").isEqualTo(enabled);
        }
    }

    @Test void archivedSourceCannotBeOptedIntoSharedEditing() throws Exception {
        var rows=mock(UserWorkspaceRepository.class); var editor=mock(ArchitectureEditorService.class);
        var sources=mock(com.taxonomy.workspace.service.SystemRepositoryService.class);
        var source=new com.taxonomy.workspace.model.SystemRepository();
        source.setLifecycleState(com.taxonomy.workspace.model.RepositoryLifecycleState.ARCHIVED);
        when(sources.getRepository("repo")).thenReturn(source);
        var workspace=workspace(); when(rows.findByWorkspaceIdForUpdate("copy")).thenReturn(Optional.of(workspace));
        var service=new WorkspaceCollaborationService(rows,editor,sources); service.setActorAccess(username -> {});
        var owner=RepositoryContext.workspace("repo","copy","draft","owner");
        assertThatThrownBy(()->service.setEnabled(owner,ArchitectureCommandPort.Context.of(owner,null,0),true))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(workspace.isCollaborative()).isFalse(); verifyNoInteractions(editor);
    }

    @Test void staleRevisionCannotChangeSharingOrDiscardAcceptedWork() throws Exception {
        var rows=mock(UserWorkspaceRepository.class); var editor=mock(ArchitectureEditorService.class);
        var workspace=workspace(); when(rows.findByWorkspaceIdForUpdate("copy")).thenReturn(Optional.of(workspace));
        var service=new WorkspaceCollaborationService(rows,editor,activeRepositories()); service.setActorAccess(username -> {});
        var owner=RepositoryContext.workspace("repo","copy","draft","owner");
        var expected=ArchitectureCommandPort.Context.of(owner,null,0);
        when(editor.read(owner,null)).thenReturn(new ArchitectureEditorService.Document(
                ArchitectureCommandPort.Context.of(owner,null,1),"accepted", "READY",java.util.List.of(),java.util.List.of(),0,null,"WORKSPACE_REVISION"));
        assertThatThrownBy(()->service.setEnabled(owner,expected,true))
                .isInstanceOf(com.taxonomy.editor.persistence.EditorJournal.RevisionConflict.class);
        assertThat(workspace.isCollaborative()).isFalse();
    }

    @Test
    void onlyTheWorkingCopyOwnerCanEnableOrDisableCollaboration() throws Exception {
        var rows = mock(UserWorkspaceRepository.class);
        var editor = mock(ArchitectureEditorService.class);
        var workspace = workspace();
        when(rows.findByWorkspaceIdForUpdate("copy")).thenReturn(Optional.of(workspace));
        when(rows.updateCollaborationFlag("copy", true)).thenReturn(1);
        var service = new WorkspaceCollaborationService(rows, editor,activeRepositories()); service.setActorAccess(username -> {});
        var owner = RepositoryContext.workspace("repo", "copy", "draft", "owner");
        var expected = ArchitectureCommandPort.Context.of(owner, null, 0);
        when(editor.read(owner, null)).thenReturn(new ArchitectureEditorService.Document(expected, "", "READY",
                java.util.List.of(), java.util.List.of(), 0, null, "WORKSPACE_REVISION"));
        var status = service.setEnabled(owner, expected, true);
        assertThat(workspace.isCollaborative()).isTrue();
        assertThat(status.enabled()).isTrue();
        assertThat(status.mayManage()).isTrue();
        verify(rows).updateCollaborationFlag("copy", true);
        var contributor = RepositoryContext.collaborativeWorkspace("repo", "copy", "draft", "contributor");
        assertThatThrownBy(() -> service.setEnabled(contributor, ArchitectureCommandPort.Context.of(contributor, null), false))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(workspace.isCollaborative()).isTrue();
    }

    private static UserWorkspace workspace() {
        var workspace = new UserWorkspace(); workspace.setWorkspaceId("copy"); workspace.setUsername("owner");
        workspace.setCurrentBranch("draft"); workspace.setSourceRepositoryId("repo"); return workspace;
    }
    private static com.taxonomy.workspace.service.SystemRepositoryService activeRepositories() {
        var repositories=mock(com.taxonomy.workspace.service.SystemRepositoryService.class);
        var repository=new com.taxonomy.workspace.model.SystemRepository();
        repository.setLifecycleState(com.taxonomy.workspace.model.RepositoryLifecycleState.ACTIVE);
        when(repositories.getRepository("repo")).thenReturn(repository); return repositories;
    }
}
