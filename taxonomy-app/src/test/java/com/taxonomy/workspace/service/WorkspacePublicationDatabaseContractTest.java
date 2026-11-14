package com.taxonomy.workspace.service;

import com.taxonomy.backup.restore.RestoreSchemaMigration;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.repository.WorkspaceProvisioningFenceImpl;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

public final class WorkspacePublicationDatabaseContractTest {
    @Test
    void isolatedHsqlUsesTheSameReceiptsAndMonotonicCatalogueAsTheRunningServerDatabases() throws Exception {
        var source=new DriverManagerDataSource("jdbc:hsqldb:mem:publication-"+UUID.randomUUID()+";hsqldb.tx=mvcc","sa","");
        var active=new DriverManagerDataSource("jdbc:hsqldb:mem:active-"+UUID.randomUUID(),"sa","");
        RestoreSchemaMigration.bootstrap(active,source,() -> {});
        verify(source);
    }

    public static void verify(DataSource database) throws Exception {
        String repository="publication-"+UUID.randomUUID();
        String workspace=UUID.randomUUID().toString();
        var selected=RepositoryContext.workspace(repository,workspace,"main","publication-owner");
        var neighbor=RepositoryContext.workspace(repository,"other-"+workspace,"main",selected.username());
        String initiator=UUID.randomUUID().toString();
        String deletingActor=UUID.randomUUID().toString();
        var grant=new WorkspaceFetchAuthority.Grant(initiator,"LOCAL","a".repeat(64));
        var snapshot=new WorkspacePublicationJournal.Source("main",null,null,null,null);
        var instant=Instant.parse("2026-01-04T12:00:00Z");
        // Live application schedulers must never pick up this synthetic operation.
        var future=Clock.fixed(Instant.now().plus(Duration.ofDays(2)), ZoneOffset.UTC);
        try(var first=factory(database); var second=factory(database)) {
            var em=SharedEntityManagerCreator.createSharedEntityManager(first);
            var transactions=new JpaTransactionManager(first);
            var tx=new TransactionTemplate(transactions);
            var repos=new JpaRepositoryFactory(em);
            var catalogue=repos.getRepository(SystemRepositoryRepository.class);
            var rows=repos.getRepository(UserWorkspaceRepository.class,RepositoryFragments.just(new WorkspaceProvisioningFenceImpl(em)));
            var journal=new WorkspacePublicationJournal(em,transactions,future);
            var restarted=new WorkspacePublicationJournal(SharedEntityManagerCreator.createSharedEntityManager(second),
                    new JpaTransactionManager(second),Clock.offset(future,Duration.ofMinutes(6)));
            var source=new SystemRepository();
            source.setRepositoryId(repository);
            source.setTopologyMode(com.taxonomy.workspace.model.RepositoryTopologyMode.INTERNAL_SHARED);
            source.setCreatedAt(instant);
            source.setUpdatedAt(instant.plusSeconds(5));
            var parent=new UserWorkspace();
            parent.setWorkspaceId(workspace);
            parent.setSourceRepositoryId(repository);
            parent.setUsername(selected.username());
            parent.setCreatedAt(instant);
            tx.executeWithoutResult(status -> { em.persist(source); em.persist(parent); });
            var operation=journal.admit(selected,grant,false,"published-scope",null,() -> snapshot);
            var other=journal.admit(neighbor,grant,false,"published-scope",null,() -> snapshot);
            var pull=journal.admit(selected,grant,false,"published-scope",null,() -> snapshot,true);
            var keyless=journal.admit(neighbor,grant,true,null,null,() -> snapshot,true);
            var contributor=RepositoryContext.workspace(repository,workspace,"main","member-"+workspace);
            String contributorId=UUID.randomUUID().toString();
            var contributorGrant=new WorkspaceFetchAuthority.Grant(contributorId,"LOCAL","c".repeat(64));
            var shared=journal.admit(contributor,contributorGrant,false,"contributor-publication",null,
                    () -> new WorkspacePublicationJournal.Source("main",null,null,null,null,selected.username()));
            try {
                assertThat(pull.operationId()).isNotEqualTo(operation.operationId());
                assertThat(pull.pull()).isTrue();
                var replay=restarted.admit(neighbor,grant,true,null,null,() -> {
                    throw new AssertionError("Repeating a legacy pull must not recapture a later source");
                },true);
                assertThat(replay.operationId()).isEqualTo(keyless.operationId());
                var stalePull=journal.claim(pull.operationId(),"original-pull-worker",false);
                var newPull=restarted.claim(pull.operationId(),"resumed-pull-worker",false);
                assertThat(newPull).isNotNull();
                assertThatThrownBy(() -> journal.step(stalePull,(row,lease) -> {
                    throw new AssertionError("The previous pull lease must not access Git");
                })).isInstanceOf(ResponseStatusException.class);
                tx.executeWithoutResult(status -> {
                    catalogue.recordLastPush(repository,instant.plusSeconds(2));
                    catalogue.recordLastPush(repository,instant);
                });
                var recorded=catalogue.findByRepositoryId(repository).orElseThrow();
                assertThat(recorded.getUpdatedAt()).isEqualTo(instant.plusSeconds(5));
                assertThat(recorded.getLastPushAt()).isEqualTo(instant.plusSeconds(2));

                var old=journal.claim(operation.operationId(),"first-worker",false);
                var replacement=restarted.claim(operation.operationId(),"second-worker",false);
                assertThat(replacement).isNotNull();
                assertThatThrownBy(() -> journal.step(old,(row,check) -> {throw new AssertionError("Stale worker entered Git");}))
                        .isInstanceOf(ResponseStatusException.class);
                var access=mock(WorkspaceFetchAuthority.class);
                when(access.currentActor(selected.username())).thenReturn(deletingActor);
                var storage=mock(DslGitRepositoryFactory.class);
                var manager=new WorkspaceManager(rows,10,mock(SystemRepositoryService.class),storage,mock(WorkspaceFetchJournal.class),access);
                manager.configurePublicationJournal(journal);
                when(storage.deleteWorkspaceRepository(workspace)).thenThrow(new IllegalStateException("storage unavailable")).thenReturn(null);
                assertThatThrownBy(() -> tx.executeWithoutResult(status -> manager.deleteWorkspace(workspace,selected.username())))
                        .hasMessageContaining("storage unavailable");
                assertThat(restarted.get(selected,initiator,operation.operationId()).status()).isEqualTo("RUNNING");
                tx.executeWithoutResult(status -> manager.deleteWorkspace(workspace,selected.username()));
                assertThat(rows.findByWorkspaceId(workspace)).isEmpty();
                assertThat(restarted.get(selected,initiator,pull.operationId()).status()).isEqualTo("ARCHIVED");
                assertThat(restarted.get(neighbor,initiator,keyless.operationId()).status()).isEqualTo("ADMITTED");
                var archived=journal.get(selected,initiator,operation.operationId());
                assertThat(archived.status()).isEqualTo("ARCHIVED");
                var sharedEvidence=restarted.get(contributor,contributorId,shared.operationId());
                assertThat(sharedEvidence.status()).isEqualTo("ARCHIVED");
                assertThat(sharedEvidence.originalActor()).isEqualTo(contributorId);
                assertThat(sharedEvidence.decisionCode()).isEqualTo("WORKSPACE_DELETED");
                assertThatThrownBy(() -> restarted.get(selected,initiator,shared.operationId()))
                        .isInstanceOf(ResponseStatusException.class);
                assertThat(archived.decisionCode()).isEqualTo("WORKSPACE_DELETED");
                assertThat(archived.originalActor()).isEqualTo(initiator);
                assertThat(archived.lastExecutor()).isEqualTo("second-worker");
                assertThat(restarted.get(neighbor,initiator,other.operationId()).status()).isEqualTo("ADMITTED");
                assertThatThrownBy(() -> restarted.step(replacement,(row,check) -> {throw new AssertionError("Deleted workspace entered Git");}))
                        .isInstanceOf(ResponseStatusException.class);
                try(var session=second.openSession()) {
                    var audit=session.createQuery("from WorkspacePublicationDecision d where d.operation=:id order by d.id",WorkspacePublicationDecision.class)
                            .setParameter("id",operation.operationId()).getResultList();
                    assertThat(audit.stream().map(d -> d.decision)).containsExactly("ADMITTED","WORKSPACE_DELETED","ARCHIVED");
                    assertThat(audit.stream().skip(1)).allMatch(d -> initiator.equals(d.actor) && deletingActor.equals(d.executor));
                }
            } finally {
                first.inTransaction(session -> {
                    var ids=List.of(operation.operationId(),other.operationId(),pull.operationId(),keyless.operationId(),shared.operationId());
                    session.createMutationQuery("delete from WorkspacePublicationDecision d where d.operation in :ids").setParameter("ids",ids).executeUpdate();
                    session.createMutationQuery("delete from WorkspacePublicationOperation p where p.id in :ids").setParameter("ids",ids).executeUpdate();
                    session.createMutationQuery("delete from UserWorkspace w where w.workspaceId=:id").setParameter("id",workspace).executeUpdate();
                    session.createMutationQuery("delete from SystemRepository r where r.repositoryId=:id").setParameter("id",repository).executeUpdate();
                });
            }
        }
    }

    private static SessionFactory factory(DataSource source) {
        var configuration=new Configuration().addAnnotatedClass(WorkspacePublicationOperation.class).addAnnotatedClass(WorkspacePublicationDecision.class)
                .addAnnotatedClass(SystemRepository.class).addAnnotatedClass(UserWorkspace.class)
                .setProperty("hibernate.hbm2ddl.auto","validate").setProperty("hibernate.search.enabled","false");
        configuration.getProperties().put("hibernate.connection.datasource",source);
        return configuration.buildSessionFactory();
    }
}
