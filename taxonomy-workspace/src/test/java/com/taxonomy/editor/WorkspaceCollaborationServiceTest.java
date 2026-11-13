package com.taxonomy.editor;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.repository.WorkspaceProvisioningFenceImpl;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceCollaborationServiceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void theFirstControlJournalRowJoinsItsCallersCommitOrRollback(boolean rollback) throws Exception {
        try (var fixture=new ConcurrentFixture(false)) {
            fixture.transactions.executeWithoutResult(status -> {
                fixture.changeSharing();
                if (rollback) status.setRollbackOnly();
            });
            var journal=fixture.persistence.journal.read(fixture.owner);
            if (rollback) assertThat(journal).isNull();
            else { assertThat(journal.state().revision()).isZero(); assertThat(journal.operations()).isEmpty(); }
            var retained=fixture.transactions.execute(status -> fixture.rows.findByWorkspaceId("copy").orElseThrow());
            assertThat(retained.isCollaborative()).isEqualTo(rollback);
        }
    }

    @Test void sharingDoesNotAdvanceRowOrSemanticVersionsOrCreateAnOperation() throws Exception {
        try (var fixture=new ConcurrentFixture()) {
            Long before=fixture.transactions.execute(status -> ((Number) org.springframework.orm.jpa.EntityManagerFactoryUtils
                    .getTransactionalEntityManager(fixture.persistence.factory)
                    .createNativeQuery("select row_version from editor_workspace",Long.class).getSingleResult()).longValue());
            fixture.transactions.execute(status -> fixture.changeSharing());
            Long after=fixture.transactions.execute(status -> ((Number) org.springframework.orm.jpa.EntityManagerFactoryUtils
                    .getTransactionalEntityManager(fixture.persistence.factory)
                    .createNativeQuery("select row_version from editor_workspace",Long.class).getSingleResult()).longValue());
            assertThat(after).isEqualTo(before);
            var retained=fixture.persistence.journal.read(fixture.owner);
            assertThat(retained.state().revision()).isZero(); assertThat(retained.operations()).isEmpty();
            assertThat(fixture.persistence.repositories.resolveRepository(fixture.owner).getCommitCount("draft")).isEqualTo(1);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"edit,false", "checkpoint,false", "edit,true", "checkpoint,true"})
    void sharingKeepsTheJournalLockedUntilTheCallerCommitsOrRollsBack(String competitor, boolean rollback) throws Exception {
        try (var fixture=new ConcurrentFixture(); var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var sharingChecked=new java.util.concurrent.CountDownLatch(1);
            var finishSharing=new java.util.concurrent.CountDownLatch(1);
            var competitorStarted=new java.util.concurrent.CountDownLatch(1);
            try {
                var sharing=workers.submit(() -> fixture.transactions.execute(status -> {
                    var result=fixture.changeSharing(); sharingChecked.countDown();
                    await(finishSharing); if (rollback) status.setRollbackOnly(); return result;
                }));
                await(sharingChecked);
                var writer=workers.submit(() -> {
                    competitorStarted.countDown();
                    return fixture.persistence.journal.locked(fixture.owner, fixture.initial, session -> {
                        fixture.change(session, competitor); return null;
                    });
                });
                await(competitorStarted);
                assertThatThrownBy(() -> writer.get(1, java.util.concurrent.TimeUnit.SECONDS))
                        .as("a journal change cannot commit before the sharing caller commits or rolls back")
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                finishSharing.countDown();
                sharing.get(5, java.util.concurrent.TimeUnit.SECONDS); writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
                var retained=fixture.transactions.execute(status -> fixture.rows.findByWorkspaceId("copy").orElseThrow());
                assertThat(retained.isCollaborative()).isEqualTo(rollback);
                var stand=fixture.persistence.journal.read(fixture.owner).state();
                if (competitor.equals("edit")) assertThat(stand.revision()).isEqualTo(1);
                else assertThat(stand.pendingCheckpoint()).isNotNull();
            } finally { finishSharing.countDown(); }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"edit", "checkpoint"})
    void aCompetitorHoldingTheJournalCommitsBeforeSharingValidatesItsStand(String competitor) throws Exception {
        try (var fixture=new ConcurrentFixture(); var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var competitorLocked=new java.util.concurrent.CountDownLatch(1);
            var finishCompetitor=new java.util.concurrent.CountDownLatch(1);
            var sharingStarted=new java.util.concurrent.CountDownLatch(1);
            try {
                var writer=workers.submit(() -> fixture.persistence.journal.locked(fixture.owner, fixture.initial, session -> {
                    fixture.change(session, competitor);
                    org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(fixture.persistence.factory).flush();
                    competitorLocked.countDown(); await(finishCompetitor); return null;
                }));
                await(competitorLocked);
                var sharing=workers.submit(() -> fixture.transactions.execute(status -> {
                    sharingStarted.countDown(); return fixture.changeSharing();
                }));
                await(sharingStarted);
                assertThatThrownBy(() -> sharing.get(1, java.util.concurrent.TimeUnit.SECONDS))
                        .as("sharing cannot accept a stand while another journal transaction is uncommitted")
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                finishCompetitor.countDown(); writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThatThrownBy(() -> sharing.get(5, java.util.concurrent.TimeUnit.SECONDS))
                        .hasCauseInstanceOf(competitor.equals("edit")
                                ? com.taxonomy.editor.persistence.EditorJournal.RevisionConflict.class : IllegalArgumentException.class);
                var retained=fixture.transactions.execute(status -> fixture.rows.findByWorkspaceId("copy").orElseThrow());
                assertThat(retained.isCollaborative()).isTrue();
            } finally { finishCompetitor.countDown(); }
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try { assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).as("concurrent fixture reached its barrier").isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static final class ConcurrentFixture implements AutoCloseable {
        final EditorPersistenceFixture persistence;
        final org.springframework.transaction.support.TransactionTemplate transactions;
        final UserWorkspaceRepository rows;
        final RepositoryContext owner=RepositoryContext.collaborativeWorkspace("repo", "copy", "draft", "owner");
        final ArchitectureCommandPort.Context expected;
        final com.taxonomy.editor.persistence.EditorJournal.State initial;
        final WorkspaceCollaborationService sharing;
        ConcurrentFixture() throws Exception { this(true); }
        ConcurrentFixture(boolean initialize) throws Exception {
            persistence=new EditorPersistenceFixture("jdbc:hsqldb:mem:sharing-stand-"+java.util.UUID.randomUUID()+";hsqldb.tx=mvcc", UserWorkspace.class);
            transactions=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.orm.jpa.JpaTransactionManager(persistence.factory));
            var entities=org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(persistence.factory);
            rows=new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(entities).getRepository(UserWorkspaceRepository.class,
                    RepositoryFragments.just(new WorkspaceProvisioningFenceImpl(entities)));
            var metadata=workspace(); metadata.setCreatedAt(java.time.Instant.now()); metadata.setCollaborative(true);
            transactions.executeWithoutResult(status -> rows.saveAndFlush(metadata));
            String source="element retained type Component { title: \"Retained\"; }\n";
            String commit=persistence.repositories.resolveRepository(owner).commitDsl("draft", source, "owner", "Fixture baseline");
            initial=new com.taxonomy.editor.persistence.EditorJournal.State(source,0,commit,0,null);
            if (initialize) persistence.journal.locked(owner,initial,session -> null);
            expected=persistence.service.read(owner,null).context();
            sharing=new WorkspaceCollaborationService(rows,persistence.service,activeRepositories());
            sharing.setActorAccess(username -> {});
        }
        WorkspaceCollaborationService.Status changeSharing() {
            try { return sharing.setEnabled(owner,expected,false); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        void change(com.taxonomy.editor.persistence.EditorJournal.Session session,String competitor) {
            session.expect(0); String id=java.util.UUID.randomUUID().toString();
            var metadata=new ArchitectureCommandPort.Metadata(id,id,id,"Reviewed contributor change");
            if (competitor.equals("edit")) session.append(metadata,"contributor","SEMANTIC",null,"fixture",initial.dsl()+"// contributor\n",java.util.List.of());
            else session.prepare(metadata,"contributor","fixture-checkpoint");
        }
        @Override public void close() { persistence.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void staleMetadataMergeCannotReverseAnOwnerSharingDecision(boolean enabled) throws Exception {
        try (var persistence = new EditorPersistenceFixture("jdbc:hsqldb:mem:sharing-merge-" + java.util.UUID.randomUUID(), UserWorkspace.class)) {
            var transactions = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.orm.jpa.JpaTransactionManager(persistence.factory));
            var entities = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(persistence.factory);
            var rows = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(entities)
                    .getRepository(UserWorkspaceRepository.class,
                    RepositoryFragments.just(new WorkspaceProvisioningFenceImpl(entities)));
            var workspace = workspace(); workspace.setCreatedAt(java.time.Instant.now()); workspace.setCollaborative(!enabled);
            transactions.executeWithoutResult(status -> rows.saveAndFlush(workspace));
            var stale = transactions.execute(status -> rows.findByWorkspaceId("copy").orElseThrow());
            var editor = mock(ArchitectureEditorService.class);
            var owner = new RepositoryContext("repo", "copy", "draft", "owner",
                    com.taxonomy.workspace.service.RepositoryScope.WORKSPACE, !enabled);
            var expected = ArchitectureCommandPort.Context.of(owner, null, 0);
            boundary(editor, owner, new ArchitectureEditorService.Document(expected, "", "READY",
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
        owned(rows);
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
        owned(rows);
        var workspace=workspace(); when(rows.findByWorkspaceIdForUpdate("copy")).thenReturn(Optional.of(workspace));
        var service=new WorkspaceCollaborationService(rows,editor,activeRepositories()); service.setActorAccess(username -> {});
        var owner=RepositoryContext.workspace("repo","copy","draft","owner");
        var expected=ArchitectureCommandPort.Context.of(owner,null,0);
        boundary(editor, owner, new ArchitectureEditorService.Document(
                ArchitectureCommandPort.Context.of(owner,null,1),"accepted", "READY",java.util.List.of(),java.util.List.of(),0,null,"WORKSPACE_REVISION"));
        assertThatThrownBy(()->service.setEnabled(owner,expected,true))
                .isInstanceOf(com.taxonomy.editor.persistence.EditorJournal.RevisionConflict.class);
        assertThat(workspace.isCollaborative()).isFalse();
    }

    @Test
    void onlyTheWorkingCopyOwnerCanEnableOrDisableCollaboration() throws Exception {
        var rows = mock(UserWorkspaceRepository.class);
        owned(rows);
        var editor = mock(ArchitectureEditorService.class);
        var workspace = workspace();
        when(rows.findByWorkspaceIdForUpdate("copy")).thenReturn(Optional.of(workspace));
        when(rows.updateCollaborationFlag("copy", true)).thenReturn(1);
        var service = new WorkspaceCollaborationService(rows, editor,activeRepositories()); service.setActorAccess(username -> {});
        var owner = RepositoryContext.workspace("repo", "copy", "draft", "owner");
        var expected = ArchitectureCommandPort.Context.of(owner, null, 0);
        boundary(editor, owner, new ArchitectureEditorService.Document(expected, "", "READY",
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

    private static void owned(UserWorkspaceRepository rows) {
        when(rows.countReadyOwnedWorkspace("copy", "owner", "repo", "draft",
                com.taxonomy.workspace.model.WorkspaceProvisioningStatus.READY)).thenReturn(1L);
    }
    private static void boundary(ArchitectureEditorService editor, RepositoryContext owner,
                                 ArchitectureEditorService.Document document) throws java.io.IOException {
        doAnswer(invocation -> invocation.<java.util.function.Function<ArchitectureEditorService.Document, Object>>getArgument(1).apply(document))
                .when(editor).collaborationBoundary(eq(owner), any());
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
