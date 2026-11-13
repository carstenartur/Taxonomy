package com.taxonomy.versioning.service;

import com.taxonomy.versioning.model.ArchitectureCommitIndex;
import com.taxonomy.versioning.repository.ArchitectureCommitIndexRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceFetchAuthority.Grant;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

public class HistoryIndexWriterContractTest {
    @Test void scopedHibernateWriterAndItsReceiptCommitOrRollBackTogether() {
        var db = new DriverManagerDataSource("jdbc:hsqldb:mem:purge-writer-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", "");
        verify(db, true);
    }

    /** Run through the production Hibernate writer and Spring-managed JPA/JDBC transaction on this database. */
    public static void verify(DataSource db, boolean createSchema) {
        var cfg = new Configuration().setProperty("hibernate.hbm2ddl.auto", createSchema ? "create-drop" : "validate")
                .setProperty("hibernate.search.enabled", "false")
                .addAnnotatedClass(ArchitectureCommitIndex.class)
                .addAnnotatedClass(HistoryIndexOperationRow.class).addAnnotatedClass(HistoryIndexPurgeTarget.class)
                .addAnnotatedClass(HistoryIndexDecisionRow.class);
        cfg.getProperties().put("hibernate.connection.datasource", db);
        try (var factory = cfg.buildSessionFactory()) {
            var selected = RepositoryContext.workspace("purge-" + UUID.randomUUID(), "__shared__", "draft", "owner");
            String repo = selected.repositoryId();
            var otherRepo = "other-" + repo;
            var first = add(factory, repo, "__shared__", "a");
            var gone = add(factory, repo, "__shared__", "b");
            var central = add(factory, repo, null, "c");
            var neighbour = add(factory, otherRepo, "__shared__", "d");
            var grant = new Grant("verified-owner", "LOCAL", "current-binding");
            var jdbc = new JdbcTemplate(db);
            var manager = new JpaTransactionManager(factory);
            var journal = new HistoryIndexJournal(jdbc, manager, Clock.systemUTC());
            var receipt = journal.admit(selected, selected.branch(), HistoryIndexJournal.Kind.PURGE, grant,
                    "purge-key", null, () -> new HistoryIndexJournal.Capture(null, "owner"));
            String id = receipt.receipt().operationId();
            try {
                factory.inTransaction(session -> session.remove(session.find(ArchitectureCommitIndex.class, gone)));
                var replacement = add(factory, repo, "__shared__", "b");
                var writer = new CommitIndexService(mock(DslGitRepositoryFactory.class), mock(ArchitectureCommitIndexRepository.class));
                ReflectionTestUtils.setField(writer, "entityManager", SharedEntityManagerCreator.createSharedEntityManager(factory));
                var original = journal.claim(id, "first", false);
                assertThatThrownBy(() -> journal.execute(original, row -> {
                    assertThat(writer.purgeCaptured(selected, id)).isEqualTo(1);
                    throw new org.springframework.security.access.AccessDeniedException("permission changed before commit");
                })).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                assertThat(factory.fromTransaction(session -> session.find(ArchitectureCommitIndex.class, first)) != null).isTrue();
                journal.failed(original, "RETRYABLE", "TRANSIENT_TEST_CHECKPOINT");
                var retry = journal.claim(id, "next", true);
                assertThat(journal.execute(retry, row -> writer.purgeCaptured(selected, id)).count()).isEqualTo(1);
                factory.inTransaction(session -> {
                    assertThat(session.find(ArchitectureCommitIndex.class, first)).isNull();
                    assertThat(session.find(ArchitectureCommitIndex.class, gone)).isNull();
                    for (var untouched : new Long[]{replacement, central, neighbour})
                        assertThat(session.find(ArchitectureCommitIndex.class, untouched)).isNotNull();
                });
            } finally {
                factory.inTransaction(session -> session.createMutationQuery(
                        "delete from ArchitectureCommitIndex p where p.repositoryId in (:repos)").setParameterList("repos", java.util.List.of(repo, otherRepo)).executeUpdate());
                jdbc.update("delete from history_index_purge_target where operation_id=?", id);
                jdbc.update("delete from history_index_decision where operation_id=?", id);
                jdbc.update("delete from history_index_operation where operation_id=?", id);
            }
        }
    }

    private static Long add(SessionFactory db, String repo, String workspace, String commit) {
        return db.fromTransaction(session -> {
            var row = new ArchitectureCommitIndex();
            row.setRepositoryId(repo);
            row.setWorkspaceId(workspace);
            row.setBranch("draft");
            row.setCommitId(commit.repeat(40));
            row.setCommitTimestamp(Instant.now());
            session.persist(row);
            return row.getId();
        });
    }
}
