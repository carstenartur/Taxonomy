package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.runtime.*;
import com.taxonomy.backup.snapshot.*;
import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureElement;
import com.taxonomy.editor.*;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.security.backup.IdentityBackupContributor;
import com.taxonomy.workspace.backup.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.model.WorkspaceSyncScope;
import com.taxonomy.workspace.repository.WorkspaceSyncStateUpdatesImpl;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceFetchAuthority;
import com.taxonomy.workspace.service.WorkspaceFetchJournal;
import com.taxonomy.workspace.service.WorkspaceFetchRecovery;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceUpstreamService;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.WorkspaceProvisioningFenceImpl;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import com.taxonomy.workspace.storage.*;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real owner journal, history exporter, native ZIP, staged Hibernate Git host and editor application boundary. */
class WorkspaceEditorRestoreRuntimeTest {
    private static final String VERSION="1.5.1-SNAPSHOT",SOURCE_SCOPE="source-owner",TARGET_SCOPE="target-owner";
    private static final String EDIT="00000000-0000-0000-0000-000000000211",SAVE="00000000-0000-0000-0000-000000000212";
    private static final BackupCheckpoint CHECK=()->{};
    private static final BackupRepositoryKey CENTRAL=new BackupRepositoryKey("editor-source-repository",null),WORKSPACE=new BackupRepositoryKey("editor-source-repository","editor-source-workspace");
    private static final BackupRepositoryKey FORK = new BackupRepositoryKey("editor-source-fork", null);
    private static final BackupComponentId APPLICATION=new BackupComponentId("application"),WORKSPACE_OWNER=new BackupComponentId("workspace");
    @TempDir Path temporary;

    @Test void explicitActorRebindingPreservesActualEditorHistoryUndoRedoAndInterruptedCheckpointEvidence()throws Exception {
        try(var fixture=fixture(SOURCE_SCOPE,null,true)) {
            fixture.restore();
            var target=RepositoryContext.workspace("editor-target-repository","editor-target-workspace","main",TARGET_SCOPE);
            var journal=new EditorJournal(fixture.targetFactory,new JpaTransactionManager(fixture.targetFactory));
            try(var repositories=new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(fixture.targetFactory))) {
                var editor=new ArchitectureEditorService(repositories,journal,new ArchitectureCheckpointWriter());
                var before=editor.read(target,null);
                assertThat(before.dsl()).contains("Restored editor draft");
                assertThat(before.context().revision()).isEqualTo(1);
                assertThat(before.history()).extracting(ArchitectureEditorService.HistoryEntry::commandId).containsExactly(EDIT);
                assertThat(before.history().getFirst().actor()).isEqualTo(TARGET_SCOPE);
                assertThat(before.pendingCheckpointId()).isNull();
                assertThat(journal.checkpoints(target)).singleElement().satisfies(checkpoint->{
                    assertThat(checkpoint.commandId()).isEqualTo(SAVE);assertThat(checkpoint.failureCode()).isEqualTo("RESTORED_INTERRUPTED");
                    assertThat(checkpoint.actor()).isEqualTo(TARGET_SCOPE);assertThat(checkpoint.completed()).isTrue();
                });
                var undo=editor.execute(target,new Command(before.context(),metadata(UUID.randomUUID().toString()),new UndoArchitectureCommand(UUID.fromString(EDIT))));
                assertThat(editor.read(target,null).dsl()).isEmpty();
                var redo=editor.execute(target,new Command(editor.read(target,null).context(),metadata(UUID.randomUUID().toString()),new RedoArchitectureCommand(UUID.fromString(undo.commandId()))));
                assertThat(redo.context().revision()).isEqualTo(3);
                assertThat(editor.read(target,null).dsl()).isEqualTo(before.dsl());
                assertThat(new JdbcTemplate(fixture.target).queryForObject("select count(*) from app_principal where enabled<>0",Integer.class)).isZero();
                assertThat(new JdbcTemplate(fixture.target).queryForObject("select record_json from restore_workspace_evidence where record_kind='operations'",String.class)).contains("\"actor\":\""+SOURCE_SCOPE+"\"");
                assertThat(new JdbcTemplate(fixture.source).queryForObject("select actor from editor_operation",String.class)).isEqualTo(SOURCE_SCOPE);
                assertThat(fixture.sourceJournal.read(fixture.sourceContext).state().pendingCheckpoint()).isEqualTo(SAVE);
                var targetRows = new JdbcTemplate(fixture.target);
                var progress = new WorkspaceSyncStateUpdatesImpl(SharedEntityManagerCreator.createSharedEntityManager(fixture.targetFactory),
                        new JpaTransactionManager(fixture.targetFactory));
                var main = progress.readScope(new WorkspaceSyncScope(TARGET_SCOPE, target.repositoryId(), target.workspaceId(), "main"));
                var review = progress.readScope(new WorkspaceSyncScope(TARGET_SCOPE, target.repositoryId(), target.workspaceId(), "review"));
                assertThat(main.getSyncStatus()).isEqualTo("RESTORED_REVIEW_REQUIRED");
                assertThat(main.getLastPublishedCommitId()).isEqualTo(new JdbcTemplate(fixture.source)
                        .queryForObject("select base_commit from user_workspace", String.class));
                assertThat(review.getLastPublishedCommitId()).isNull();
                assertThat(review.getUnpublishedCommitCount()).isEqualTo(3);
                assertThat(progress.readScope(new WorkspaceSyncScope(TARGET_SCOPE, target.repositoryId(), null, "main"))
                        .getLastPublishedCommitId()).isNotNull().isNotEqualTo(main.getLastPublishedCommitId());
                assertThat(progress.readScope(new WorkspaceSyncScope(SOURCE_SCOPE, target.repositoryId(), target.workspaceId(), "main"))
                        .getLastSyncedCommitId()).isNull();
                var detachedActor = new WorkspaceSyncScope("archived-collaborator", CENTRAL.repositoryId(), null, "main");
                assertThat(targetRows.queryForObject("""
                        select classification from restore_workspace_evidence
                        where record_kind='branch-synchronization' and record_key=?""",String.class,detachedActor.key()))
                        .isEqualTo("PRIVATE_QUARANTINE");
                assertThat(progress.readScope(new WorkspaceSyncScope("quarantined-collaborator",target.repositoryId(),null,"main"))
                        .getLastPublishedCommitId()).isNull();
                String evidence = " from restore_workspace_evidence where record_kind='fetch-journal' and record_key=?";
                assertThat(targetRows.queryForObject("select classification" + evidence, String.class, "f".repeat(64)))
                        .isEqualTo("INTERRUPTED_SOURCE_OPERATION");
                assertThat(targetRows.queryForObject("select classification" + evidence, String.class, "e".repeat(64)))
                        .isEqualTo("PRIVATE_QUARANTINE");
                assertThat(targetRows.queryForObject("select record_json" + evidence, String.class, "f".repeat(64)))
                        .contains("requestedCommit", "RUNNING", SOURCE_SCOPE)
                        .doesNotContain("private-source-worker", "leaseId", "bindingKey");
                assertThat(targetRows.queryForObject("select count(*) from workspace_fetch_operation", Long.class)).isZero();
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ARCHITECTURE_PUBLISH+READER","ARCHITECTURE_PUBLISH+READER+KEYLESS"})
    void interruptedPullRestoresItsRecordedSourceAndRemainsUndispatchable(String policy) throws Exception {
        try (var f=fixture(SOURCE_SCOPE,null,false,false,true)) {
            String id="1".repeat(64);
            new JdbcTemplate(f.source).update("""
                    update workspace_publication_operation set policy_code=?,operation_step='LOCAL_PORTFOLIO',
                    central_portfolio_digest=null where operation_id=?""",policy,id);
            try (var archive=export(f.source,f.sourceFactory,f.principals.keySet().iterator().next(),false,Set.of(CENTRAL,WORKSPACE),null)) {
                f.restore(archive);
                var target=new JdbcTemplate(f.target);
                String query=" from restore_workspace_evidence where record_kind='publication-journal' and record_key=?";
                assertThat(target.queryForObject("select classification"+query,String.class,id)).isEqualTo("INTERRUPTED_SOURCE_OPERATION");
                assertThat(target.queryForObject("select record_json"+query,String.class,id))
                        .contains(policy,"LOCAL_PORTFOLIO","inputSource").doesNotContain("private-lease-sentinel","private-binding-sentinel");
                assertThat(target.queryForObject("select count(*) from workspace_publication_operation",Long.class)).isZero();
            }
        }
    }

    @Test
    void collaboratorReceiptsKeepTheActorAndOriginalWorkspaceOwnerAcrossExportAndRestore() throws Exception {
        try (var fixture=fixture(SOURCE_SCOPE,null,true,false,true)) {
            fixture.restore();
            var target=new JdbcTemplate(fixture.target);
            String id="5".repeat(64);
            String filter=" from restore_workspace_evidence where record_kind='publication-journal' and record_key=?";
            assertThat(target.queryForObject("select classification"+filter,String.class,id)).isEqualTo("PRIVATE_QUARANTINE");
            var captured=com.taxonomy.exchange.backup.PortableRows.json().readTree(
                    target.queryForObject("select record_json"+filter,String.class,id));
            assertThat(captured.get("principalScope").asText()).isEqualTo("archived-collaborator");
            assertThat(captured.get("workspaceOwnerScope").asText()).isEqualTo(SOURCE_SCOPE);
            assertThat(captured.get("detachedWorkspace").asBoolean()).isFalse();
            var personal=new BackupScope.Repositories(Map.of(CENTRAL.repositoryId(),Set.of(WORKSPACE.workspaceId())));
            assertThat(capturedCurrentRows(fixture,personal,"publication-journal",null)).contains(id);
            var other=new BackupScope.Repositories(Map.of(CENTRAL.repositoryId(),Set.of()));
            assertThat(capturedCurrentRows(fixture,other,"publication-journal",null)).doesNotContain(id);
            assertThat(target.queryForObject("select count(*) from workspace_publication_operation",Long.class)).isZero();
        }
    }

    @Test
    void publicationsRestoreAsBoundHistoricalEvidenceWithoutExecutorAuthority() throws Exception {
        try (var f = fixture(SOURCE_SCOPE, null, false, false, true)) {
            var restored = f.restore(f.archive);
            var target = new JdbcTemplate(f.target);
            var activeId = "1".repeat(64); var detachedId = "2".repeat(64); var reboundId = "4".repeat(64);
            String query = " from restore_workspace_evidence where record_kind=? and record_key=?";
            assertThat(target.queryForObject("select classification" + query, String.class, "publication-journal", activeId))
                    .isEqualTo("INTERRUPTED_SOURCE_OPERATION");
            assertThat(target.queryForObject("select classification" + query, String.class, "publication-journal", detachedId))
                    .isEqualTo("DETACHED_SOURCE_QUARANTINE");
            assertThat(target.queryForObject("select classification" + query,String.class,"publication-journal",reboundId))
                    .isEqualTo("DETACHED_SOURCE_QUARANTINE");
            assertThat(target.queryForObject("select record_json" + query,String.class,"publication-journal",reboundId))
                    .contains("former-publishing-owner",WORKSPACE.workspaceId());
            var tombstone = com.taxonomy.exchange.backup.PortableRows.json().readTree(
                    target.queryForObject("select record_json" + query,String.class,"publication-journal",detachedId));
            assertThat(tombstone.get("redactedAt").asLong()).isEqualTo(91);
            assertThat(tombstone.get("archivedBy").asText()).isEqualTo("system:workspace-publication-retention");
            assertThat(tombstone.get("commits").properties()).allMatch(entry -> entry.getValue().isNull());
            assertThat(tombstone.get("centralPortfolioDigest").isNull()).isTrue();
            assertThat(tombstone.get("localPortfolioDigest").isNull()).isTrue();
            var initiator = f.principals.keySet().iterator().next().value().toString();
            String evidence = target.queryForObject("select record_json" + query, String.class, "publication-journal", activeId);
            assertThat(evidence).contains(initiator, "system:workspace-publication", "ARCHITECTURE_PUBLISH+CONTRIBUTOR")
                    .doesNotContain("private-binding-sentinel", "private-lease-sentinel", "bindingKey", "leaseId", "cleanupDueAt");
            var payload = com.taxonomy.exchange.backup.PortableRows.json().readTree(evidence);
            assertThat(payload.get("centralPortfolioDigest").asText()).isEqualTo("a".repeat(64));
            assertThat(payload.get("localPortfolioDigest").asText()).isEqualTo("b".repeat(64));
            assertThat(payload.get("sourceObservedAt").asLong()).isEqualTo(3);
            assertThat(payload.get("pinsReleasedAt").asLong()).isEqualTo(4);
            assertThat(payload.get("commits").size()).isEqualTo(9);
            assertThat(target.queryForObject("select count(*) from restore_workspace_evidence where record_kind='git-commit-references' and record_key like ?",
                    Long.class, "workspace.publication:" + activeId + ":%")).isEqualTo(9);
            String decisions = " from restore_workspace_evidence where record_kind='publication-decisions'";
            var events = target.queryForList("select record_json" + decisions,String.class);
            assertThat(events).hasSize(5).anySatisfy(event -> assertThat(event).contains(activeId,initiator,"system:workspace-publication"));
            assertThat(target.queryForObject("select count(*)" + decisions + " and classification='DETACHED_SOURCE_QUARANTINE'",Long.class)).isEqualTo(3);
            assertThat(target.queryForObject("select count(*) from workspace_publication_operation",Long.class)).isZero();
            assertThat(target.queryForObject("select count(*) from workspace_publication_decision",Long.class)).isZero();
            var personal = new BackupScope.Workspace(CENTRAL.repositoryId(),WORKSPACE.workspaceId());
            assertThat(capturedCurrentRows(f,personal,"publication-journal","former-publishing-owner")).contains(activeId).doesNotContain(detachedId,reboundId);
            assertThat(capturedCurrentRows(f,personal,"publication-decisions",null)).isEmpty();
            assertThat(capturedCurrentRows(f,new BackupScope.Repositories(Map.of(CENTRAL.repositoryId(),Set.of())),"publication-journal",null)).isEmpty();
            assertThat(capturedCurrentRows(f,new BackupScope.Installation(),"publication-journal","former-publishing-owner")).contains(activeId).doesNotContain(detachedId,reboundId);
            var actor = f.principals.keySet().iterator().next();
            try (var unknownParent = export(f.source,f.sourceFactory,actor,false,Set.of(CENTRAL,WORKSPACE),
                    (path,body) -> path.endsWith("/publication-decisions.ndjson") ? body.replace(activeId,"3".repeat(64)) : body)) {
                assertThatThrownBy(() -> new WorkspaceRestoreImporter().restore(unknownParent,f.generation,f.principals,restored.git(),CHECK))
                        .isInstanceOf(IOException.class).hasMessage("Invalid typed workspace history evidence");
            }
            var database = new JdbcTemplate(f.source);
            database.update("update workspace_publication_decision set original_actor=? where operation_id=? and decision_code='APPLIED'",
                    "different-actor", activeId);
            var principal = f.principals.keySet().iterator().next();
            assertThatThrownBy(() -> export(f.source,f.sourceFactory,principal,false)).isInstanceOf(IOException.class);
            target.update("insert into workspace_publication_decision(operation_id,original_actor,executor_id,phase_code,decision_code,decision_at) values(?,?,?,'PREPARE','ADMITTED',1)",
                    activeId,initiator,initiator);
            assertThatThrownBy(() -> new WorkspaceRestoreImporter().restore(f.archive,f.generation,f.principals,restored.git(),CHECK))
                    .isInstanceOf(IOException.class).hasMessage("Isolated target contains publication operations");
        }
    }

    @Test
    void centralAndTagReceiptsRestoreAsOriginalEvidenceWithoutRunnableGrants() throws Exception {
        try (var f = fixture(SOURCE_SCOPE,null,false,true)) {
            f.restore();
            var target = new JdbcTemplate(f.target);
            String sql = " from restore_workspace_evidence where record_kind='central-fetch-journal' and record_key=?";
            String liveId = "f-" + "a".repeat(62), deletedId = "f-" + "b".repeat(62);
            String initialId = initialTransferId();
            assertThat(target.queryForObject("select classification" + sql,String.class,initialId)).isEqualTo("INTERRUPTED_SOURCE_OPERATION");
            assertThat(target.queryForObject("select record_json" + sql,String.class,initialId)).contains("RUNNING", FORK.repositoryId()).doesNotContain("bindingKey","leaseId");
            assertThat(target.queryForObject("select classification" + sql,String.class,liveId))
                    .isEqualTo("INTERRUPTED_SOURCE_OPERATION");
            assertThat(target.queryForObject("select classification" + sql,String.class,deletedId))
                    .isEqualTo("DETACHED_SOURCE_QUARANTINE");
            assertThat(target.queryForObject("select record_json" + sql,String.class,liveId))
                    .contains("release/selected",FORK.repositoryId(),CENTRAL.repositoryId(),"ARCHITECTURE_WRITE+CONTRIBUTOR")
                    .doesNotContain("private-source-worker","leaseId","bindingKey");
            var both = new BackupScope.Repositories(Map.of(CENTRAL.repositoryId(),Set.of(),FORK.repositoryId(),Set.of()));
            String historicalActor = "removed-private-fork-actor";
            new JdbcTemplate(f.source).update("update central_fetch_operation set actor_scope=? where operation_id=?", historicalActor, deletedId);
            assertThat(capturedCentralFetches(f,both,historicalActor)).contains(liveId).doesNotContain(deletedId);
            assertThat(capturedCentralFetches(f,new BackupScope.Repositories(Map.of(FORK.repositoryId(),Set.of())),historicalActor)).isEmpty();
            assertThat(capturedCentralFetches(f,new BackupScope.Workspace(CENTRAL.repositoryId(),WORKSPACE.workspaceId()),historicalActor)).isEmpty();
            assertThat(capturedCentralFetches(f,new BackupScope.Installation(),historicalActor)).contains(liveId).doesNotContain(deletedId);
            assertThat(target.queryForObject("select count(*) from central_fetch_operation",Long.class)).isZero();
            assertThat(target.queryForObject("select count(*) from workspace_fetch_operation",Long.class)).isZero();
        }
    }

    private static String initialTransferId() {
        return "p-" + com.taxonomy.identity.StableIdentityHash.sha256("initial-central-fork:" + FORK.repositoryId()).substring(0,62);
    }

    private static String capturedCentralFetches(Fixture f, BackupScope scope, String historicalActor) throws Exception {
        return capturedCurrentRows(f,scope,"central-fetch-journal",historicalActor);
    }
    private static String capturedCurrentRows(Fixture f, BackupScope scope, String kind, String historicalActor) throws Exception {
        var profile = scope instanceof BackupScope.Installation ? BackupProfile.INSTALLATION_CURRENT : BackupProfile.CURRENT_STATE;
        var request = new BackupRequest(profile,scope,new BackupTime.Current(),GitRepresentation.NONE,SecretsSelection.EXCLUDE);
        var actor = f.principals.keySet().iterator().next();
        var captured = new HashMap<BackupRepositoryKey,SnapshotContext.RepositoryState>();
        for (var repository : f.archive.manifest().repositories()) {
            if (scope instanceof BackupScope.Installation || scope.selectedRepositories().contains(repository.id())) {
                captured.put(repository.id(),repository.captured());
            }
        }
        var snapshot = new SnapshotContext(BackupId.create(),f.authority.authorize(actor,request),
                Instant.EPOCH,Instant.EPOCH,1,captured,Map.of(WORKSPACE_OWNER,1));
        var journal = new ByteArrayOutputStream();
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        var contributor = new WorkspaceBackupContributor(f.source,value -> value);
        if (historicalActor != null) assertThat(contributor.principalScopes(snapshot,CHECK)).doesNotContain(historicalActor);
        if (f.principals.size()>1 && new JdbcTemplate(f.source).queryForObject(
                "select count(*) from workspace_publication_operation where workspace_owner_scope=?",Long.class,SOURCE_SCOPE)>0) {
            var actors=contributor.historyActorScopes(snapshot,CHECK);
            if (captured.containsKey(WORKSPACE)) assertThat(actors).contains("archived-collaborator");
            else assertThat(actors).doesNotContain("archived-collaborator");
            assertThat(contributor.principalScopes(snapshot,CHECK)).doesNotContain("archived-collaborator");
        }
        contributor.write(snapshot,(path,input) -> {
            byte[] bytes = input.readAllBytes();
            if (path.equals("data/workspace/" + kind + ".ndjson")) journal.write(bytes);
            return new BackupEntry(path,bytes.length,HexFormat.of().formatHex(digest.digest(bytes)));
        });
        return journal.toString(java.nio.charset.StandardCharsets.UTF_8).lines().skip(1).collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    void deletedFetchSurvivesOnlyAsQuarantinedEvidenceInAFullInstallationBackup() throws Exception {
        try (var f = fixture(SOURCE_SCOPE)) {
            var actor = f.principals.keySet().iterator().next();
            var selected = RepositoryContext.workspace(CENTRAL.repositoryId(), "removed-private-copy", "main", SOURCE_SCOPE);
            var db = new JdbcTemplate(f.source);
            var metadata = f.sourceFactory.fromTransaction(session -> session.createSelectionQuery(
                    "from SystemRepository r where r.repositoryId=:id", SystemRepository.class)
                    .setParameter("id", CENTRAL.repositoryId()).getSingleResult());
            var catalog = mock(SystemRepositoryService.class);
            when(catalog.getRepository(selected.repositoryId())).thenReturn(metadata);
            doCallRealMethod().when(catalog).readCurrentAuthorization(eq(selected.repositoryId()), any());
            try (var routed = new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(f.sourceFactory),catalog)) {
                String sourceHead = routed.getCentralRepository(selected.repositoryId()).getHeadCommit("main");
                routed.createWorkspaceRepository(selected.workspaceId(), selected.repositoryId(), "main").commitDsl("main", "private", SOURCE_SCOPE, "Local");
                db.update("""
                        insert into user_workspace(workspace_id,username,current_branch,created_at,provisioning_status,topology_mode,
                        source_repository_id,source_branch,base_commit,archived,is_default,shared) values(?,?,'main',CURRENT_TIMESTAMP,
                        'READY','INTERNAL_SHARED',?,'main',?,false,false,false)""", selected.workspaceId(), SOURCE_SCOPE, selected.repositoryId(), sourceHead);
                var em = SharedEntityManagerCreator.createSharedEntityManager(f.sourceFactory);
                var txManager = new JpaTransactionManager(f.sourceFactory);
                var repos = new JpaRepositoryFactory(em);
                var fragments = RepositoryFragments.just(new WorkspaceProvisioningFenceImpl(em));
                var rows = repos.getRepository(UserWorkspaceRepository.class, fragments);
                var memberships = mock(RepositoryMembershipService.class);
                when(memberships.canContribute(metadata,SOURCE_SCOPE)).thenReturn(true);
                var grant = new WorkspaceFetchAuthority.Grant(actor.value().toString(),"LOCAL","b".repeat(64));
                var authority = mock(WorkspaceFetchAuthority.class);
                when(authority.authorize(SOURCE_SCOPE,RepositoryScope.WORKSPACE)).thenReturn(grant);
                String deletingActor=UUID.randomUUID().toString();
                when(authority.currentActor(SOURCE_SCOPE)).thenReturn(deletingActor);
                var journal = new WorkspaceFetchJournal(em,txManager);
                var upstream = new WorkspaceUpstreamService(rows,catalog,memberships,routed);
                var fetches = new WorkspaceFetchRecovery(journal,upstream,authority,null);
                var receipt = fetches.fetch(selected,new WorkspaceUpstreamService.Request(sourceHead,null),"archive-contract",null);
                assertThat(receipt.result().trackedCommit()).isEqualTo(sourceHead);
                var progress = new WorkspaceSyncStateUpdatesImpl(em,txManager);
                var deletedScope = new WorkspaceSyncScope(SOURCE_SCOPE,selected.repositoryId(),selected.workspaceId(),"main");
                progress.recordSync(deletedScope,sourceHead,Instant.now(),false);
                var retained = journal.admit(selected,grant,upstream.capture(selected),
                        new WorkspaceUpstreamService.Request(sourceHead,sourceHead),"pending-at-deletion",null);
                var tx = new TransactionTemplate(txManager);
                var failingStorage = mock(DslGitRepositoryFactory.class);
                doThrow(new IllegalStateException("repository still open")).when(failingStorage).deleteWorkspaceRepository(selected.workspaceId());
                var failedDelete = new WorkspaceManager(rows,10,catalog,failingStorage,journal,authority);
                assertThatThrownBy(() -> tx.executeWithoutResult(status -> failedDelete.deleteWorkspace(selected.workspaceId(), SOURCE_SCOPE)))
                        .hasMessageContaining("repository still open");
                assertThat(journal.status(selected,grant.principalId(),retained.operationId()).phase()).isEqualTo("ADMITTED");
                assertThat(progress.readScope(deletedScope).getLastSyncedCommitId()).isEqualTo(sourceHead);
                var manager = new WorkspaceManager(rows,10,catalog,routed,journal,authority);
                tx.executeWithoutResult(status -> manager.deleteWorkspace(selected.workspaceId(), SOURCE_SCOPE));
                assertThat(rows.findByWorkspaceId(selected.workspaceId())).isEmpty();
                assertThat(progress.readScope(deletedScope).getLastSyncedCommitId()).isNull();
                var denied = journal.status(selected,grant.principalId(),retained.operationId());
                assertThat(denied.phase()).isEqualTo("ARCHIVED");
                assertThat(denied.outcome()).isEqualTo("DENIED");
                assertThat(denied.decisionCode()).isEqualTo("WORKSPACE_DELETED");
                assertThat(denied.originalActor()).isEqualTo(grant.principalId());
                assertThat(denied.archivedBy()).isEqualTo(deletingActor);
                try (var after = export(f.source, f.sourceFactory, actor, false)) {
                    f.restore(after);
                    var target = new JdbcTemplate(f.target);
                    String sql = "select classification from restore_workspace_evidence where record_kind='fetch-journal' and record_key=?";
                    for (String id : List.of(receipt.operationId(),retained.operationId())) {
                        assertThat(target.queryForObject(sql,String.class,id)).isEqualTo("DETACHED_SOURCE_QUARANTINE");
                    }
                    assertThat(target.queryForObject("select count(*) from workspace_fetch_operation", Long.class)).isZero();
                    assertThat(target.queryForObject(sql,String.class,"f".repeat(64))).isEqualTo("INTERRUPTED_SOURCE_OPERATION");
                    assertThat(db.queryForObject("select phase from workspace_fetch_operation where operation_id=?", String.class,
                            receipt.operationId())).isEqualTo("COMPLETED");
                }
                var currentRequest = new BackupRequest(BackupProfile.INSTALLATION_CURRENT,new BackupScope.Installation(),
                        new BackupTime.Current(),GitRepresentation.NONE,SecretsSelection.EXCLUDE);
                var current = new SnapshotContext(BackupId.create(),f.authority.authorize(actor,currentRequest),
                        Instant.EPOCH,Instant.EPOCH,1,Map.of(),Map.of(WORKSPACE_OWNER,1));
                var currentJournal = new ByteArrayOutputStream();
                var hash = java.security.MessageDigest.getInstance("SHA-256");
                new WorkspaceBackupContributor(f.source,document -> document).write(current,(path,input) -> {
                    byte[] content = input.readAllBytes();
                    if (path.equals("data/workspace/fetch-journal.ndjson")) currentJournal.write(content);
                    return new BackupEntry(path,content.length,HexFormat.of().formatHex(hash.digest(content)));
                });
                assertThat(currentJournal.toString(java.nio.charset.StandardCharsets.UTF_8))
                        .contains("f".repeat(64)).doesNotContain(receipt.operationId(),retained.operationId());
                db.update("update workspace_fetch_operation set actor_scope=? where operation_id=?","another-owner","e".repeat(64));
                assertThatThrownBy(() -> export(f.source,f.sourceFactory,actor,false))
                        .isInstanceOf(IOException.class).hasMessageContaining("ownership does not match");
            }
        }
    }

    @Test void unknownHistoricalActorMatchingTargetScopeQuarantinesEntireEditorInsteadOfGrantingPersonalUndo()throws Exception {
        try(var fixture=fixture(TARGET_SCOPE)) {
            fixture.restore();var rows=new JdbcTemplate(fixture.target);
            for(String table:List.of("editor_workspace","editor_operation","editor_checkpoint"))assertThat(rows.queryForObject("select count(*) from "+table,Long.class)).as(table).isZero();
            assertThat(rows.queryForList("select classification from restore_workspace_evidence where record_kind in ('working-states','operations','checkpoints')",String.class)).containsOnly("PRIVATE_QUARANTINE");
            assertThat(rows.queryForObject("select record_json from restore_workspace_evidence where record_kind='operations'",String.class)).contains("\"actor\":\""+TARGET_SCOPE+"\"");
            assertThat(new JdbcTemplate(fixture.source).queryForObject("select count(*) from editor_operation",Long.class)).isEqualTo(1);
        }
    }

    @Test void verifiedArchiveRestoresPersistedAnalysesThroughTheIsolatedGitIdentityAndWorkspaceOwners() throws Exception {
        var analysis = new AnalysisArchiveRoundTrip();
        try (var fixture = fixture(SOURCE_SCOPE,analysis)) {
            var owners = fixture.restore();
            analysis.verify(fixture.archive,fixture.generation,owners.identities(),owners.git(),WORKSPACE);
        }
    }

    private Fixture fixture(String actor)throws Exception { return fixture(actor,null); }

    private Fixture fixture(String actor,AnalysisArchiveRoundTrip analysis)throws Exception { return fixture(actor,analysis,false,false); }

    private Fixture fixture(String actor,AnalysisArchiveRoundTrip analysis,boolean archivedCollaborator)throws Exception {
        return fixture(actor,analysis,archivedCollaborator,false);
    }

    private Fixture fixture(String actor,AnalysisArchiveRoundTrip analysis,boolean archivedCollaborator,boolean includeFork)throws Exception {
        return fixture(actor,analysis,archivedCollaborator,includeFork,false);
    }
    private Fixture fixture(String actor,AnalysisArchiveRoundTrip analysis,boolean archivedCollaborator,boolean includeFork,boolean includePublications)throws Exception {
        var source=database();var target=database();RestoreSchemaMigration.bootstrap(database(),source,CHECK);
        var sourceFactory=RestoreSchemaMigration.openValidatedFactory(database(),source,model(),CHECK);
        var sourceRepositories=new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(sourceFactory));
        var sourceContext=RepositoryContext.workspace(CENTRAL.repositoryId(),WORKSPACE.workspaceId(),"main",actor);
        var principal=PrincipalId.create();var rows=new JdbcTemplate(source);var created=java.sql.Timestamp.from(Instant.now());
        rows.update("insert into app_principal(principal_id,scope_key,enabled) values(?,?,1)",principal.value().toString(),SOURCE_SCOPE);
        rows.update("insert into system_repository(repository_id,storage_repository_name,slug,display_name,visibility,lifecycle_state,owner_type,owner_id,topology_mode,default_branch,primary_repo,created_at,version) values(?,?,'editor-source','Editor source','PRIVATE','ACTIVE','USER',?,'INTERNAL_SHARED','main',false,?,0)",CENTRAL.repositoryId(),"editor-source-central-storage",SOURCE_SCOPE,created);
        var origin = sourceRepositories.createCentralRepository(CENTRAL.repositoryId(),"editor-source-central-storage");
        String centralBaseline = origin.commitDsl("main","",SOURCE_SCOPE,"Original central baseline");
        String baseline=sourceRepositories.resolveRepository(sourceContext).commitDsl("main","",SOURCE_SCOPE,"Original workspace baseline");
        rows.update("insert into user_workspace(workspace_id,username,display_name,current_branch,base_branch,shared,created_at,provisioning_status,topology_mode,source_repository_id,source_branch,relationship_type,base_commit,current_commit,archived,is_default) values(?,?,'Editor draft','main','main',false,?,'READY','INTERNAL_SHARED',?,'main','WORKING_COPY',?,?,false,false)",WORKSPACE.workspaceId(),SOURCE_SCOPE,created,CENTRAL.repositoryId(),baseline,baseline);
        var sourceProgress = new WorkspaceSyncStateUpdatesImpl(SharedEntityManagerCreator.createSharedEntityManager(sourceFactory),
                new JpaTransactionManager(sourceFactory));
        var mainProgress = new WorkspaceSyncScope(SOURCE_SCOPE, WORKSPACE.repositoryId(), WORKSPACE.workspaceId(), "main");
        var otherProgress = new WorkspaceSyncScope(SOURCE_SCOPE, WORKSPACE.repositoryId(), WORKSPACE.workspaceId(), "review");
        var centralProgress = new WorkspaceSyncScope(SOURCE_SCOPE, CENTRAL.repositoryId(), null, "main");
        sourceProgress.recordSync(mainProgress, baseline, created.toInstant(), true);
        sourceProgress.recordSync(otherProgress, baseline, created.toInstant(), false);
        sourceProgress.recordLocalChanges(otherProgress, 3, 0, created.toInstant().plusSeconds(1));
        sourceProgress.recordSync(centralProgress, centralBaseline, created.toInstant(), true);
        var principals = new LinkedHashMap<PrincipalId, RestorePrincipalAllocation>();
        principals.put(principal, new RestorePrincipalAllocation(PrincipalId.create(),TARGET_SCOPE,RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET));
        if (archivedCollaborator) {
            var collaborator = PrincipalId.create();
            rows.update("insert into app_principal(principal_id,scope_key,enabled) values(?,?,1)",collaborator.value().toString(),"archived-collaborator");
            var historical = new WorkspaceSyncScope("archived-collaborator",CENTRAL.repositoryId(),null,"main");
            if (!includePublications) sourceProgress.recordSync(historical,centralBaseline,created.toInstant(),true);
            principals.put(collaborator,new RestorePrincipalAllocation(PrincipalId.create(),"quarantined-collaborator",
                    RestorePrincipalAllocation.Disposition.QUARANTINE));
        }
        var sourceJournal=new EditorJournal(sourceFactory,new JpaTransactionManager(sourceFactory));
        var service=new ArchitectureEditorService(sourceRepositories,sourceJournal,new ArchitectureCheckpointWriter());
        var accepted=service.execute(sourceContext,new Command(Context.of(sourceContext,baseline,0),metadata(EDIT),new SemanticCommand(new CreateArchitectureElement("restored-editor-element","System",Map.of("title","Restored editor draft")))));
        sourceJournal.locked(sourceContext,sourceJournal.read(sourceContext).state(),session->session.prepare(metadata(SAVE),actor,"a".repeat(64)));
        assertThat(accepted.context().revision()).isEqualTo(1);
        assertThat(sourceJournal.read(sourceContext).state().pendingCheckpoint()).isEqualTo(SAVE);
        for (boolean legacy : List.of(false, true)) {
            rows.update("""
                    insert into workspace_fetch_operation(operation_id,fingerprint,cause_id,actor_id,actor_scope,
                    repository_id,workspace_id,local_branch,source_branch,requested_commit,observed_commit,phase,attempts,
                    created_at,last_attempt_at,next_attempt_at,lease_id,executor_id,login_kind,binding_key,policy_code)
                    values(?,?,?,?,?,?,?,?,?,?,?,'RUNNING',1,10,11,80000,?,?,?,?,'ANALYSIS_RUN+CONTRIBUTOR')""",
                    (legacy ? "e" : "f").repeat(64), "a".repeat(64), "b".repeat(64),
                    legacy ? "d".repeat(64) : principal.value().toString(), SOURCE_SCOPE, CENTRAL.repositoryId(),
                    WORKSPACE.workspaceId(), "main", "main", baseline, baseline, UUID.randomUUID().toString(),
                    "private-source-worker", legacy ? "LEGACY_OIDC" : "LOCAL", "c".repeat(64));
        }
        if (includeFork) {
            String forkPoint = origin.getHeadCommit("main");
            var targetFork = sourceRepositories.createCentralRepository(FORK.repositoryId(),"source-fork-storage");
            GitHistoryTransfer.copyCommitClosure(origin,targetFork,forkPoint,List.of());
            targetFork.createBranchAtVerifiedCommit("main",forkPoint);
            rows.update("""
                    insert into system_repository(repository_id,storage_repository_name,slug,display_name,visibility,
                    lifecycle_state,owner_type,owner_id,topology_mode,default_branch,primary_repo,created_at,version,
                    upstream_repository_id,upstream_branch,fork_point_commit)
                    values(?,'source-fork-storage','editor-fork','Private fork','PRIVATE','ACTIVE','USER',?,'INTERNAL_SHARED',
                    'main',false,?,0,?,'main',?)""",FORK.repositoryId(),SOURCE_SCOPE,created,CENTRAL.repositoryId(),forkPoint);
            for (boolean detached : List.of(false,true)) {
                rows.update("""
                        insert into central_fetch_operation(operation_id,fingerprint,cause_id,actor_id,actor_scope,
                        target_repository_id,source_repository_id,local_branch,source_branch,fork_point,requested_commit,
                        expected_commit,observed_commit,selected_tag,requested_tag_object,phase,attempts,created_at,
                        last_attempt_at,next_attempt_at,lease_id,executor_id,login_kind,binding_key,policy_code)
                        values(?,?,?,?,?,?,?,'main','main',?,?,?,?, 'release/selected',?,'RUNNING',1,10,11,80000,?,?,
                        'LOCAL',?,'ARCHITECTURE_WRITE+CONTRIBUTOR')""",
                        "f-" + (detached ? "b" : "a").repeat(62),"a".repeat(64),"b".repeat(64),principal.value().toString(),
                        SOURCE_SCOPE,detached ? "removed-private-fork" : FORK.repositoryId(),CENTRAL.repositoryId(),
                        forkPoint,forkPoint,forkPoint,forkPoint,forkPoint,UUID.randomUUID().toString(),"private-source-worker","c".repeat(64));
            }
            rows.update("""
                    insert into central_fetch_operation(operation_id,fingerprint,cause_id,actor_id,actor_scope,target_repository_id,
                    source_repository_id,local_branch,source_branch,fork_point,requested_commit,observed_commit,phase,attempts,
                    created_at,login_kind,binding_key,policy_code)
                    values(?,?,?,?,?,?,?,'main','main',?,?,?,'RUNNING',1,10,'LOCAL',?,'ARCHITECTURE_WRITE+CONTRIBUTOR')""",
                    initialTransferId(),"a".repeat(64),initialTransferId(),principal.value().toString(),SOURCE_SCOPE,FORK.repositoryId(),
                    CENTRAL.repositoryId(),forkPoint,forkPoint,forkPoint,"c".repeat(64));
        }
        if (includePublications) {
            var operation = """
                    insert into workspace_publication_operation(operation_id,causation_id,actor_id,actor_scope,login_kind,binding_key,policy_code,
                    repository_id,workspace_id,local_branch,source_branch,keep_workspace,fork_point,input_source,input_local,
                    phase,operation_step,attempts,created_at,executor_id,lease_id) values(?,?,?,?, 'LOCAL','private-binding-sentinel',
                    'ARCHITECTURE_PUBLISH+CONTRIBUTOR',?,?,'main','main',true,?,?,?,'RUNNING','CANDIDATES',1,1,'system:workspace-publication','private-lease-sentinel')""";
            if (archivedCollaborator) {
                String collaborator=rows.queryForObject("select principal_id from app_principal where scope_key='archived-collaborator'",String.class);
                String id="5".repeat(64);
                rows.update(operation,id,id,collaborator,"archived-collaborator",CENTRAL.repositoryId(),WORKSPACE.workspaceId(),
                        baseline,centralBaseline,baseline);
                rows.update("update workspace_publication_operation set workspace_owner_scope=? where operation_id=?",SOURCE_SCOPE,id);
            }
            for (var pair : Map.of("1".repeat(64),WORKSPACE.workspaceId(),"2".repeat(64),"removed-publishing-workspace").entrySet()) {
                rows.update(operation, pair.getKey(),pair.getKey(),principal.value().toString(),SOURCE_SCOPE,CENTRAL.repositoryId(),pair.getValue(),baseline,centralBaseline,baseline);
                rows.update("insert into workspace_publication_decision(operation_id,original_actor,executor_id,phase_code,decision_code,decision_at) values(?,?,?,'PREPARE','ADMITTED',1)",
                        pair.getKey(),principal.value().toString(),principal.value().toString());
            }
            rows.update("""
                    update workspace_publication_operation set input_tracking=?,expected_local=?,expected_tracking=?,prepared_source=?,
                    prepared_local=?,prepared_tracking=?,central_portfolio_digest=?,local_portfolio_digest=?,
                    source_observed_at=3,pins_released_at=4,cleanup_due_at=81 where operation_id=?
                    """,baseline,baseline,baseline,centralBaseline,baseline,baseline,"a".repeat(64),"b".repeat(64),"1".repeat(64));
            rows.update("""
                    update workspace_publication_operation set phase='DENIED',completed_at=2,archived_at=4,archived_by='system:workspace-publication-retention',
                    pins_released_at=3,redacted_at=91,fork_point=null,input_local=null,input_source=null where operation_id=?
                    ""","2".repeat(64));
            rows.update("insert into workspace_publication_decision(operation_id,original_actor,executor_id,phase_code,decision_code,decision_at) values(?,?,'system:workspace-publication-retention','CANDIDATES','GIT_DETAILS_REDACTED',91)",
                    "2".repeat(64),principal.value().toString());
            String rebound = "4".repeat(64), previousActor = UUID.randomUUID().toString();
            rows.update(operation,rebound,rebound,previousActor,"former-publishing-owner",CENTRAL.repositoryId(),WORKSPACE.workspaceId(),
                    baseline,centralBaseline,baseline);
            rows.update("update workspace_publication_operation set phase='COMPLETED',completed_at=2,archived_at=4,archived_by=? where operation_id=?",
                    previousActor,rebound);
            rows.update("insert into workspace_publication_decision(operation_id,original_actor,executor_id,phase_code,decision_code,decision_at) values(?,?,?,'COMPLETE','ARCHIVED',4)",
                    rebound,previousActor,previousActor);
            rows.update("insert into workspace_publication_decision(operation_id,original_actor,executor_id,phase_code,decision_code,decision_at) values(?,?,'system:workspace-publication','CANDIDATES','APPLIED',2)",
                    "1".repeat(64),principal.value().toString());
        }
        if (analysis != null) analysis.seed(sourceFactory,principal,sourceContext,baseline);
        var archive=export(source,sourceFactory,principal,analysis != null,includeFork ? Set.of(CENTRAL,WORKSPACE,FORK) : Set.of(CENTRAL,WORKSPACE));
        RestoreSchemaMigration.bootstrap(source,target,CHECK);
        var targetFactory=RestoreSchemaMigration.openValidatedFactory(source,target,model(),CHECK);
        var allocations=new HashMap<>(Map.of(CENTRAL,new RestoreRepositoryAllocation(new BackupRepositoryKey("editor-target-repository",null),"editor-target-central-storage"),
                WORKSPACE,new RestoreRepositoryAllocation(new BackupRepositoryKey("editor-target-repository","editor-target-workspace"),DslGitRepositoryFactory.workspaceRepositoryName("editor-target-workspace"))));
        if (includeFork) allocations.put(FORK,new RestoreRepositoryAllocation(new BackupRepositoryKey("editor-target-fork",null),"target-fork-storage"));
        var generation=RestoreTargetGeneration.verified(UUID.randomUUID(),source,target,targetFactory,allocations,CHECK);
        return new Fixture(source,target,sourceFactory,targetFactory,sourceRepositories,sourceJournal,sourceContext,archive,generation,principals,authority(principal));
    }

    private VerifiedBackup export(JDBCDataSource source,org.hibernate.SessionFactory sourceFactory,PrincipalId principal,boolean includeAnalysis)throws Exception {
        return export(source,sourceFactory,principal,includeAnalysis,Set.of(CENTRAL,WORKSPACE));
    }

    private VerifiedBackup export(JDBCDataSource source,org.hibernate.SessionFactory sourceFactory,PrincipalId principal,
                                  boolean includeAnalysis,Set<BackupRepositoryKey> selected)throws Exception {
        return export(source,sourceFactory,principal,includeAnalysis,selected,null);
    }

    private VerifiedBackup export(JDBCDataSource source,org.hibernate.SessionFactory sourceFactory,PrincipalId principal,
                                  boolean includeAnalysis,Set<BackupRepositoryKey> selected,
                                  java.util.function.BiFunction<String,String,String> editEvidence) throws Exception {
        BackupMaintenanceLease.initialize(source);var prerequisites=BackupFeaturePrerequisites.discover();
        var existing=new ExistingGitBackupRepositories(source,sourceFactory);
        var history=new GitHistoryBackupContributor(existing,new GitStandBackupSource(source,existing,document->document),new WorkspaceGitHistoryReferences(source,existing),GitHistoryCapture.Limits.defaults(),
                new GitStandBackupSource.Limits(new GitTreeCapture.Limits(100,1_000_000,2_000_000),100_000));
        var authorization=authority(principal);var request=new BackupRequest(BackupProfile.INSTALLATION_FULL,new BackupScope.Installation(),new BackupTime.History(),GitRepresentation.BUNDLE,SecretsSelection.EXCLUDE);
        var components=new HashMap<>(Map.of(APPLICATION,new BackupSnapshotCoordinator.CaptureComponent(1,BackupCompleteness.COMPLETE,Set.of()),WORKSPACE_OWNER,new BackupSnapshotCoordinator.CaptureComponent(1,BackupCompleteness.COMPLETE,Set.of(APPLICATION))));
        // Coordinator accepts one contributor per component. This fixture tests real owner adapters,
        // not production inventory/readiness registration for the installation as a whole.
        var data=new WorkspaceBackupContributor(source,document->document);
        var workspace=new BackupContributor(){
            public BackupComponentId componentId(){return WORKSPACE_OWNER;}
            public int schemaVersion(){return 1;}
            public void write(SnapshotContext snapshot,ComponentSink sink)throws IOException {
                ComponentSink records = editEvidence==null ? sink : (path,input) -> {
                    var charset = java.nio.charset.StandardCharsets.UTF_8;
                    String original = new String(input.readAllBytes(),charset);
                    return sink.write(path,new ByteArrayInputStream(editEvidence.apply(path,original).getBytes(charset)));
                };
                data.write(snapshot,records);history.write(snapshot,sink);
            }
        };
        var contributors = new ArrayList<BackupContributor>(List.of(new IdentityBackupContributor(source,(snapshot,checkpoint)->Set.of(principal)),workspace));
        var versions = new HashMap<>(Map.of(APPLICATION,1,WORKSPACE_OWNER,1,new BackupComponentId("capture-proof"),1));
        if (includeAnalysis) {
            var owner = new com.taxonomy.analysis.backup.AnalysisBackupContributor(source,SOURCE_SCOPE);
            components.put(owner.componentId(),new BackupSnapshotCoordinator.CaptureComponent(1,BackupCompleteness.COMPLETE,Set.of(APPLICATION,WORKSPACE_OWNER)));
            versions.put(owner.componentId(),owner.schemaVersion());
            contributors.add(owner);
        }
        var coordinator=new BackupSnapshotCoordinator(new BackupMaintenanceLease(source,Duration.ofSeconds(30)),authorization,
                auth->new BackupSnapshotCoordinator.CapturePlan(VERSION,"editor-owner-runtime-fixture",new JdbcTemplate(source).queryForObject("select installation_id from principal_installation",String.class),history.inspect(auth,selected,CHECK),components,List.of(),List.of(),new SourceDatabaseMetadata(1,SourceDatabaseFamily.HSQLDB)),
                contributors,temporary.resolve(UUID.randomUUID()+"-capture"),BackupSnapshotCoordinator.CaptureLimits.defaults(),Clock.systemUTC(),prerequisites);
        Path output=temporary.resolve(UUID.randomUUID()+".taxbackup");try(var captured=coordinator.capture(authorization.authorize(principal,request))){new BackupArchiveWriter(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),prerequisites).write(captured,output);}
        return new BackupArchiveReader(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),VERSION,versions,prerequisites).verify(output);
    }

    private static BackupAuthorizationService authority(PrincipalId principal) {
        return new BackupAuthorizationService(new BackupAccessPolicy(){
            public boolean isEnabled(PrincipalId id){return principal.equals(id);}
            public boolean hasCapability(PrincipalId id,BackupCapability capability,BackupScope scope){return principal.equals(id);}
            public boolean canRead(PrincipalId id,BackupRepositoryKey repository){return principal.equals(id);}
            public boolean canReadVersion(PrincipalId id,BackupRepositoryKey repository,String commit){return principal.equals(id);}
        },Clock.systemUTC());
    }
    private static Metadata metadata(String command){return new Metadata(command,command,command,"Editor restore acceptance");}
    private static JDBCDataSource database(){var database=new JDBCDataSource();database.setUrl("jdbc:hsqldb:mem:editor-restore-"+UUID.randomUUID()+";hsqldb.tx=mvcc");database.setUser("sa");return database;}
    private static List<Class<?>> model()throws Exception {try(var input=WorkspaceEditorRestoreRuntimeTest.class.getResourceAsStream("/db/restore/hsqldb/approved-business-model.txt")){var model=new ArrayList<Class<?>>();for(String name:new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).lines().toList())if(!name.isBlank()&&!name.startsWith("#"))model.add(Class.forName(name));return model;}}
    private record RestoredOwners(GitRestoreImporter.Result git,RestoreIdentityImporter.Result identities) {}
    private final class Fixture implements AutoCloseable {
        final JDBCDataSource source,target;final org.hibernate.SessionFactory sourceFactory,targetFactory;final DslGitRepositoryFactory sourceRepositories;
        final EditorJournal sourceJournal;final RepositoryContext sourceContext;final VerifiedBackup archive;final RestoreTargetGeneration generation;
        final Map<PrincipalId,RestorePrincipalAllocation> principals;final BackupAuthorizationService authority;
        Fixture(JDBCDataSource source,JDBCDataSource target,org.hibernate.SessionFactory sourceFactory,org.hibernate.SessionFactory targetFactory,DslGitRepositoryFactory sourceRepositories,EditorJournal sourceJournal,RepositoryContext sourceContext,VerifiedBackup archive,RestoreTargetGeneration generation,Map<PrincipalId,RestorePrincipalAllocation> principals,BackupAuthorizationService authority){
            this.source=source;this.target=target;this.sourceFactory=sourceFactory;this.targetFactory=targetFactory;this.sourceRepositories=sourceRepositories;this.sourceJournal=sourceJournal;this.sourceContext=sourceContext;this.archive=archive;this.generation=generation;this.principals=principals;this.authority=authority;
        }
        RestoredOwners restore()throws IOException { return restore(archive); }
        RestoredOwners restore(VerifiedBackup archive)throws IOException {
            var git=new ApplicationGitHistoryRestoreImporter(()->principals.keySet().iterator().next(),authority,temporary.resolve("restore-private"),GitHistoryCapture.Limits.defaults());
            Files.createDirectories(temporary.resolve("restore-private"));
            var proof=git.importGit(archive,generation.allocations(),generation,CHECK);
            var identities=new RestoreIdentityImporter().restore(archive,generation,principals,CHECK);
            new WorkspaceRestoreImporter().restore(archive,generation,principals,proof,CHECK);
            return new RestoredOwners(proof,identities);
        }
        public void close()throws IOException{archive.close();sourceRepositories.close();sourceFactory.close();targetFactory.close();}
    }
}
