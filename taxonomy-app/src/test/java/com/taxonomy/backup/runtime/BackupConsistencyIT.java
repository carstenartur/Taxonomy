package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.analysis.recovery.AnalysisContinuationRun;
import com.taxonomy.analysis.recovery.AnalysisContinuationStore;
import com.taxonomy.analysis.recovery.AnalysisQuestionCheckpoint;
import com.taxonomy.backup.snapshot.BackupMaintenanceLease;
import com.taxonomy.backup.snapshot.GuardedBackupDataSource;
import com.taxonomy.dsl.command.ArchitectureCommand.UpdateArchitectureElement;
import com.taxonomy.dto.AnalysisRequest;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.editor.ArchitectureCheckpointWriter;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.editor.persistence.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.config.CoreEntities;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real editor, Hibernate-backed Git and analysis completion, on two independent nodes. */
class BackupConsistencyIT {
    private static final RepositoryContext SCOPE = RepositoryContext.workspace("repo-a", "workspace-a1", "draft", "alice");

    @Test void captureDrainsTheWholeCheckpointAndFencesAnArrivingAnalysisCompletion() throws Exception {
        var database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:consistent-" + UUID.randomUUID() + ";hsqldb.tx=mvcc");
        database.setUser("sa");
        BackupMaintenanceLease.initialize(database);
        try (var schema = factory(database, "update")) { /* bootstrap only */ }
        var published = new CountDownLatch(1);
        var finishJournal = new CountDownLatch(1);
        var writer = spy(new ArchitectureCheckpointWriter());
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            published.countDown();
            if (!finishJournal.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Checkpoint test timed out");
            return result;
        }).when(writer).write(any(), anyString(), anyString(), any());
        var executor = Executors.newFixedThreadPool(2);
        var acquired = new AtomicReference<BackupMaintenanceLease.Maintenance>();
        try (var first = new Node(database, writer); var second = new Node(database, new ArchitectureCheckpointWriter())) {
            var git = first.repositories.resolveRepository(SCOPE);
            git.commitDsl("draft", "// original\nelement arch-existing type System {\n title: \"Original\";\n}\n", "alice", "Initial version");
            first.editor.execute(SCOPE, new Command(first.editor.read(SCOPE, null).context(), metadata(),
                    new SemanticCommand(new UpdateArchitectureElement("arch-existing", null, Map.of("title", "Accepted edit")))));
            var request = new AnalysisRequest(); request.setContinuationId(UUID.randomUUID().toString());
            request.setBusinessText("Assess the architecture");
            var claim = first.transaction(em -> new AnalysisContinuationStore(em, JsonMapper.builder().build()).begin(request,
                    new WorkspaceContext("alice", SCOPE.workspaceId(), "draft", SCOPE.repositoryId()), "input-signature", List.of()));
            var checkpoint = new CreateCheckpointCommand(first.editor.read(SCOPE, null).context(), metadata());
            var checkpointFuture = executor.submit(() -> first.editor.checkpoint(SCOPE, checkpoint));
            assertThat(published.await(10, TimeUnit.SECONDS)).isTrue();
            var capture = executor.submit(() -> {
                var lease = second.barrier.acquire(new BackupScope.Workspace(SCOPE.repositoryId(), SCOPE.workspaceId()), Duration.ofSeconds(5));
                acquired.set(lease); return lease;
            });
            try {
                assertThatThrownBy(() -> capture.get(150, TimeUnit.MILLISECONDS))
                        .as("capture must not expose a moved Git ref with an unfinished journal")
                        .isInstanceOf(TimeoutException.class);
                assertThatThrownBy(() -> completeAnalysis(second, claim)).hasStackTraceContaining("maintenance");
                finishJournal.countDown();
                var completed = checkpointFuture.get(10, TimeUnit.SECONDS);
                try (var snapshot = capture.get(10, TimeUnit.SECONDS)) {
                    snapshot.checkValid();
                    var state = second.journal.read(SCOPE).state();
                    assertThat(state.pendingCheckpoint()).isNull();
                    assertThat(state.checkpointRevision()).isEqualTo(state.revision()).isEqualTo(1);
                    assertThat(state.checkpointCommit()).isEqualTo(completed.commitId());
                    var capturedGit = second.repositories.resolveRepository(SCOPE);
                    assertThat(capturedGit.getDslAtCommit(state.checkpointCommit())).isEqualTo(state.dsl());
                    assertThat(capturedGit.getHeadCommit("draft")).isEqualTo(state.checkpointCommit());
                    assertThat(capturedGit.getCommitCount("draft")).isEqualTo(2); // No backup commit in the source.
                    assertThat(new JdbcTemplate(database).queryForObject("select state from analysis_continuation where id=?", String.class, claim.id()))
                            .isEqualTo("RUNNING");
                }
                completeAnalysis(second, claim);
                assertThat(new JdbcTemplate(database).queryForObject("select state from analysis_continuation where id=?", String.class, claim.id()))
                        .isEqualTo("COMPLETED");
            } finally { finishJournal.countDown(); }
        } finally {
            finishJournal.countDown();
            if (acquired.get() != null) acquired.get().close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void completeAnalysis(Node node, AnalysisContinuationStore.Claim claim) {
        node.transaction(em -> {
            var result = new AnalysisResult(Map.of(), List.of()); result.setStatus("SUCCESS");
            return new AnalysisContinuationStore(em, JsonMapper.builder().build()).complete(claim, result, List.of());
        });
    }

    private static Metadata metadata() {
        String id = UUID.randomUUID().toString(); return new Metadata(id, id, id, "Checkpoint consistency test");
    }

    private static SessionFactory factory(DataSource database, String ddl) {
        var configuration = new Configuration().setProperty("hibernate.hbm2ddl.auto", ddl)
                .setProperty("hibernate.search.enabled", "false");
        configuration.getProperties().put("hibernate.connection.datasource", database);
        CoreEntities.annotatedClasses().forEach(configuration::addAnnotatedClass);
        configuration.addAnnotatedClass(EditorWorkspace.class).addAnnotatedClass(EditorOperation.class).addAnnotatedClass(EditorCheckpoint.class)
                .addAnnotatedClass(AnalysisContinuationRun.class).addAnnotatedClass(AnalysisQuestionCheckpoint.class);
        return configuration.buildSessionFactory();
    }

    private static final class Node implements AutoCloseable {
        final BackupMaintenanceLease barrier;
        final SessionFactory factory;
        final DslGitRepositoryFactory repositories;
        final EditorJournal journal;
        final ArchitectureEditorService editor;
        final AnnotationConfigApplicationContext application = new AnnotationConfigApplicationContext();

        Node(DataSource database, ArchitectureCheckpointWriter writer) {
            barrier = new BackupMaintenanceLease(database, Duration.ofSeconds(30));
            factory = factory(new GuardedBackupDataSource(database, barrier), "validate");
            repositories = new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(factory));
            journal = new EditorJournal(factory, new JpaTransactionManager(factory));
            application.registerBean(BackupWriteBarrier.class, () -> barrier);
            application.registerBean(ArchitectureEditorService.class, () -> new ArchitectureEditorService(repositories, journal, writer));
            application.refresh(); editor = application.getBean(ArchitectureEditorService.class);
        }
        <T> T transaction(Function<EntityManager, T> action) {
            try (var em = factory.createEntityManager()) {
                var tx = em.getTransaction(); tx.begin();
                try { T result = action.apply(em); tx.commit(); return result; }
                catch (RuntimeException failure) { if (tx.isActive()) tx.rollback(); throw failure; }
            }
        }
        @Override public void close() { application.close(); repositories.close(); factory.close(); }
    }
}
