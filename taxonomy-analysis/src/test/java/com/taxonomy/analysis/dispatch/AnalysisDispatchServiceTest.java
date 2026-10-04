package com.taxonomy.analysis.dispatch;

import com.taxonomy.analysis.dag.AnalysisCompletionMessage;
import com.taxonomy.analysis.dag.AnalysisMessageFactory;
import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.analysis.dag.AnalysisSourceAuthority;
import com.taxonomy.analysis.dag.AnalysisTaskGraph;
import com.taxonomy.analysis.dag.AnalysisTaskId;
import com.taxonomy.analysis.dag.AnalysisTaskMessage;
import com.taxonomy.analysis.dag.AnalysisTaskOutcome;
import com.taxonomy.analysis.dag.AnalysisTaskPublisher;
import com.taxonomy.analysis.dag.AnalysisTransportUnavailableException;
import com.taxonomy.analysis.dag.RequirementReference;
import com.taxonomy.analysis.dag.SubtaxonomyAnalysisCompleted;
import com.taxonomy.analysis.dag.SubtaxonomyAnalysisTask;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Hibernate/HSQLDB transactions with crash injection at every step of
 * commit → send → acknowledgement. No broker is required: the publisher port is
 * the boundary under test.
 */
@SpringJUnitConfig(AnalysisDispatchServiceTest.Config.class)
class AnalysisDispatchServiceTest {

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:hsqldb:mem:dispatch-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", "");
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setManagedTypes(org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes.of(
                    AnalysisDispatchIntent.class.getName(), AnalysisTaskCompletionRecord.class.getName()));
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    "hibernate.search.enabled", "false"));
            return factory;
        }

        @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean AnalysisDispatchStore dispatchStore(EntityManagerFactory factory,
                                                  PlatformTransactionManager transactions) {
            return new AnalysisDispatchStore(SharedEntityManagerCreator.createSharedEntityManager(factory),
                    transactions, Clock.systemUTC());
        }

        @Bean JpaAnalysisTaskCompletionStore completionStore(EntityManagerFactory factory,
                                                             PlatformTransactionManager transactions) {
            return new JpaAnalysisTaskCompletionStore(SharedEntityManagerCreator.createSharedEntityManager(factory),
                    transactions);
        }
    }

    /** Simulates process death: an {@link Error} is not handled by the dispatch path. */
    static final class ProcessCrash extends Error {
        ProcessCrash(String where) { super(where); }
    }

    /** Broker stand-in: records accepted sends, optionally failing. */
    static final class Broker implements AnalysisTaskPublisher {
        final List<AnalysisTaskId> accepted = new CopyOnWriteArrayList<>();
        volatile boolean available = true;
        volatile boolean crashBeforeSend;
        volatile boolean crashAfterSend;

        @Override public void publish(AnalysisTaskMessage task) {
            if (crashBeforeSend) throw new ProcessCrash("before send");
            if (!available) throw new AnalysisTransportUnavailableException("broker unavailable", null);
            accepted.add(task.taskId());
            if (crashAfterSend) throw new ProcessCrash("after send, before acknowledgement");
        }
    }

    @Autowired AnalysisDispatchStore store;
    @Autowired JpaAnalysisTaskCompletionStore completions;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;

    private TransactionTemplate tx;
    private String operationId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactions);
        operationId = "op-" + UUID.randomUUID();
        new JdbcTemplate(dataSource).update("delete from analysis_dispatch_intent");
        new JdbcTemplate(dataSource).update("delete from analysis_task_completion");
    }

    private AnalysisMessageFactory factory() {
        return new AnalysisMessageFactory(new AnalysisOperationContext(operationId,
                new AnalysisSourceAuthority("repo", "ws", "draft", "c1"),
                RequirementReference.adHoc("requirement"), null), Clock.systemUTC());
    }

    private List<AnalysisTaskMessage> tasks(String... roots) {
        var graph = AnalysisTaskGraph.plan(operationId,
                java.util.Arrays.stream(roots).map(TaxonomyShardRoot::of).toList(), false);
        var factory = factory();
        return graph.tasks().stream().map(factory::task).toList();
    }

    private AnalysisDispatchService service(Broker broker) {
        return new AnalysisDispatchService(store, broker, 2, 100);
    }

    @Test
    void publishesOnlyAfterCommitAndAcknowledgesEveryIntent() {
        var broker = new Broker();
        var service = service(broker);
        var tasks = tasks("BP", "CP", "IP");

        tx.executeWithoutResult(status -> {
            service.dispatch(tasks);
            assertThat(broker.accepted).as("no send before the operation commits").isEmpty();
            assertThat(store.count(AnalysisDispatchStatus.DISPATCH_PENDING))
                    .as("intent is not visible outside the uncommitted transaction").isZero();
        });

        assertThat(broker.accepted).containsExactlyElementsOf(tasks.stream().map(AnalysisTaskMessage::taskId).toList());
        assertThat(store.count(AnalysisDispatchStatus.DISPATCHED)).isEqualTo(3);
        assertThat(store.count(AnalysisDispatchStatus.DISPATCH_PENDING)).isZero();

        // Re-dispatching the same graph is idempotent: nothing is sent again.
        tx.executeWithoutResult(status -> service.dispatch(tasks));
        assertThat(broker.accepted).hasSize(3);
        assertThat(service.recover(AnalysisDispatchRecoveryTrigger.STARTUP).scanned()).isZero();
    }

    @Test
    void crashBeforeCommitLeavesNeitherIntentNorMessage() {
        var broker = new Broker();
        var service = service(broker);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.dispatch(tasks("BP", "CP"));
            throw new ProcessCrash("before commit");
        })).isInstanceOf(ProcessCrash.class);

        assertThat(broker.accepted).isEmpty();
        assertThat(service.recover(AnalysisDispatchRecoveryTrigger.STARTUP).scanned()).isZero();
    }

    @Test
    void crashAfterCommitBeforeSendIsRecoveredOnStartup() {
        var crashed = new Broker();
        crashed.crashBeforeSend = true;
        var tasks = tasks("BP", "CP", "CR");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service(crashed).dispatch(tasks)))
                .isInstanceOf(ProcessCrash.class);
        assertThat(store.count(AnalysisDispatchStatus.DISPATCH_PENDING)).isEqualTo(3);

        // "Restart": a new process with a working broker runs the startup trigger.
        var broker = new Broker();
        var report = service(broker).recover(AnalysisDispatchRecoveryTrigger.STARTUP);

        assertThat(report.dispatched()).isEqualTo(3);
        assertThat(broker.accepted).containsExactlyInAnyOrderElementsOf(
                tasks.stream().map(AnalysisTaskMessage::taskId).toList());
        assertThat(store.count(AnalysisDispatchStatus.DISPATCHED)).isEqualTo(3);
    }

    @Test
    void crashAfterSendBeforeAcknowledgementResendsWithOneDurableEffect() {
        var crashed = new Broker();
        crashed.crashAfterSend = true;
        var task = (SubtaxonomyAnalysisTask) tasks("CP").get(0);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service(crashed).dispatch(List.of(task))))
                .isInstanceOf(ProcessCrash.class);
        assertThat(crashed.accepted).containsExactly(task.taskId());
        assertThat(store.count(AnalysisDispatchStatus.DISPATCH_PENDING)).isEqualTo(1);

        var broker = new Broker();
        service(broker).recover(AnalysisDispatchRecoveryTrigger.STARTUP);
        assertThat(broker.accepted).containsExactly(task.taskId());
        assertThat(store.count(AnalysisDispatchStatus.DISPATCHED)).isEqualTo(1);

        // Both deliveries reach the worker; the idempotent ledger keeps exactly one effect.
        var factory = factory();
        var executions = new ArrayList<AnalysisCompletionMessage>();
        for (var delivery : List.of(task, task.atAttempt(2))) {
            if (completions.find(delivery.taskId()).isPresent()) continue;
            var completion = factory.completed((SubtaxonomyAnalysisTask) delivery, AnalysisTaskOutcome.COMPLETED,
                    80, 3, null);
            executions.add(completion);
            completions.recordIfAbsent(completion);
        }
        assertThat(executions).hasSize(1);
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "select count(*) from analysis_task_completion", Integer.class)).isEqualTo(1);
    }

    @Test
    void unavailableBrokerWaitsForReconnectInsteadOfPolling() {
        var broker = new Broker();
        broker.available = false;
        var service = service(broker);

        tx.executeWithoutResult(status -> service.dispatch(tasks("BP", "BR", "CP")));

        // The first failure stops the burst; nothing retries on its own.
        assertThat(store.count(AnalysisDispatchStatus.WAITING_FOR_BROKER)).isEqualTo(1);
        assertThat(store.count(AnalysisDispatchStatus.DISPATCH_PENDING)).isEqualTo(2);
        var stillDown = service.recover(AnalysisDispatchRecoveryTrigger.EXPLICIT_REPAIR);
        assertThat(stillDown.brokerUnavailable()).isTrue();
        assertThat(stillDown.scanned()).isEqualTo(1);

        broker.available = true;
        var report = service.recover(AnalysisDispatchRecoveryTrigger.BROKER_RECONNECT);
        assertThat(report.dispatched()).isEqualTo(3);
        assertThat(report.brokerUnavailable()).isFalse();
        assertThat(store.count(AnalysisDispatchStatus.DISPATCHED)).isEqualTo(3);
        assertThat(store.recoverable(AnalysisDispatchStore.Cursor.START, 10)).isEmpty();
    }

    @Test
    void recoveryScanIsBoundedAndResumable() {
        var crashed = new Broker();
        crashed.crashBeforeSend = true;
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                service(crashed).dispatch(tasks("BP", "BR", "CP", "CI", "CO")))).isInstanceOf(ProcessCrash.class);

        var broker = new Broker();
        var bounded = new AnalysisDispatchService(store, broker, 2, 3);
        var first = bounded.recover(AnalysisDispatchRecoveryTrigger.STARTUP);
        assertThat(first.scanned()).isEqualTo(3);
        assertThat(first.truncated()).isTrue();
        assertThat(broker.accepted).hasSize(3);

        var second = bounded.repair();
        assertThat(second.dispatched()).isEqualTo(2);
        assertThat(second.truncated()).isFalse();
        assertThat(broker.accepted).doesNotHaveDuplicates().hasSize(5);
    }

    @Test
    void corruptIntentBecomesVisibleDispatchFailureNotARetryLoop() {
        var crashed = new Broker();
        crashed.crashBeforeSend = true;
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service(crashed).dispatch(tasks("UA"))))
                .isInstanceOf(ProcessCrash.class);
        new JdbcTemplate(dataSource).update("update analysis_dispatch_intent set message_json = ?", "{\"envelope\":{}}");

        var broker = new Broker();
        var report = service(broker).recover(AnalysisDispatchRecoveryTrigger.STARTUP);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(broker.accepted).isEmpty();
        assertThat(store.count(AnalysisDispatchStatus.DISPATCH_FAILED)).isEqualTo(1);
        assertThat(service(broker).recover(AnalysisDispatchRecoveryTrigger.STARTUP).scanned()).isZero();
    }

    @Test
    void dispatchWithoutSurroundingTransactionCommitsThenPublishes() {
        var broker = new Broker();
        service(broker).dispatch(tasks("IP"));
        assertThat(broker.accepted).hasSize(1);
        assertThat(store.count(AnalysisDispatchStatus.DISPATCHED)).isEqualTo(1);
    }

    @Test
    void recordingIntentsRequiresTheCallersTransaction() {
        assertThatThrownBy(() -> store.recordIntents(tasks("BP")))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void concurrentCompletionsOfOneTaskKeepTheFirstDurableEffect() throws Exception {
        var task = (SubtaxonomyAnalysisTask) tasks("CO").get(0);
        var factory = factory();
        SubtaxonomyAnalysisCompleted a = factory.completed(task, AnalysisTaskOutcome.COMPLETED, 10, 1, null);
        SubtaxonomyAnalysisCompleted b = factory.completed(task, AnalysisTaskOutcome.COMPLETED, 90, 9, null);
        var start = new CountDownLatch(1);
        var winners = Collections.synchronizedList(new ArrayList<AnalysisCompletionMessage>());
        var threads = new ArrayList<Thread>();
        for (var completion : List.of(a, b, a, b)) {
            var thread = Thread.ofPlatform().daemon().start(() -> {
                try { start.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { return; }
                winners.add(completions.recordIfAbsent(completion));
            });
            threads.add(thread);
        }
        start.countDown();
        for (var thread : threads) thread.join(TimeUnit.SECONDS.toMillis(30));

        assertThat(winners).hasSize(4);
        assertThat(winners.stream().distinct()).hasSize(1);
        assertThat(completions.find(task.taskId())).contains(winners.get(0));
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "select count(*) from analysis_task_completion", Integer.class)).isEqualTo(1);
    }
}
