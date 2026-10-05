package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.dag.AnalysisCompletionMessage;
import com.taxonomy.analysis.dag.AnalysisMessage;
import com.taxonomy.analysis.dag.AnalysisMessageFactory;
import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.analysis.dag.AnalysisSourceAuthority;
import com.taxonomy.analysis.dag.AnalysisTaskCompletionStore;
import com.taxonomy.analysis.dag.AnalysisTaskGraph;
import com.taxonomy.analysis.dag.AnalysisTaskHandlers;
import com.taxonomy.analysis.dag.AnalysisTaskId;
import com.taxonomy.analysis.dag.AnalysisTaskMessage;
import com.taxonomy.analysis.dag.AnalysisTaskOutcome;
import com.taxonomy.analysis.dag.AnalysisTaskIdentity;
import com.taxonomy.analysis.dag.PreparedAnalysisCompletion;
import com.taxonomy.analysis.dispatch.AnalysisDispatchIntent;
import com.taxonomy.analysis.dispatch.AnalysisTaskCompletionRecord;
import com.taxonomy.analysis.dispatch.JpaAnalysisTaskCompletionStore;
import com.taxonomy.analysis.dag.AnalysisTaskType;
import com.taxonomy.analysis.dag.AnalysisTransportUnavailableException;
import com.taxonomy.analysis.dag.AnalysisWorkerShards;
import com.taxonomy.analysis.dag.RelationAnalysisTask;
import com.taxonomy.analysis.dag.RequirementReference;
import com.taxonomy.analysis.dag.SubtaxonomyAnalysisTask;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.cluster.*;
import com.taxonomy.analysis.dispatch.AnalysisDispatchService;
import com.taxonomy.analysis.dispatch.AnalysisDispatchStore;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-broker tests for the opt-in Artemis analysis transport (#1161 P02). Each
 * test runs a disposable, persistent Artemis broker on a loopback TCP port; no
 * external service, Docker or model provider is involved.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ArtemisAnalysisTransportTest {

    private static final String DLQ = "taxonomy.analysis.dlq";
    private static final TaxonomyShardRoot CP = TaxonomyShardRoot.of("CP");

    @TempDir
    Path brokerData;

    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private final AnalysisDestinations destinations = new AnalysisDestinations(AnalysisDestinations.DEFAULT_PREFIX);
    private final AnalysisMessageCodec codec = new AnalysisMessageCodec();
    private EmbeddedActiveMQ broker;
    private int port;

    @BeforeEach
    void startBroker() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        broker = newBroker();
        broker.start();
    }

    @AfterEach
    void stopAll() throws Exception {
        while (!resources.isEmpty()) {
            try {
                resources.pop().close();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        if (broker != null) broker.stop();
    }

    private EmbeddedActiveMQ newBroker() throws Exception {
        ConfigurationImpl config = new ConfigurationImpl();
        config.setPersistenceEnabled(true);
        config.setSecurityEnabled(false);
        config.setJournalType(JournalType.NIO);
        config.setJournalDirectory(brokerData.resolve("journal").toString());
        config.setBindingsDirectory(brokerData.resolve("bindings").toString());
        config.setLargeMessagesDirectory(brokerData.resolve("large").toString());
        config.setPagingDirectory(brokerData.resolve("paging").toString());
        config.setMessageExpiryScanPeriod(20);
        config.addAcceptorConfiguration("tcp", "tcp://127.0.0.1:" + port);
        config.addAddressSetting("#", new AddressSettings()
                .setMaxDeliveryAttempts(3)
                .setRedeliveryDelay(0)
                .setExpiryAddress(SimpleString.of("taxonomy.analysis.expiry"))
                .setDeadLetterAddress(SimpleString.of(DLQ)));
        config.addQueueConfiguration(QueueConfiguration.of(DLQ).setRoutingType(RoutingType.ANYCAST));
        config.addQueueConfiguration(QueueConfiguration.of("taxonomy.analysis.expiry").setRoutingType(RoutingType.ANYCAST));
        EmbeddedActiveMQ embedded = new EmbeddedActiveMQ();
        embedded.setConfiguration(config);
        return embedded;
    }

    // --- harness ---------------------------------------------------------------------------

    private ArtemisAnalysisConnection connect() {
        var settings = new ArtemisAnalysisSettings("tcp://127.0.0.1:" + port, false, 100, 2_000);
        var connection = new ArtemisAnalysisConnection(
                ArtemisAnalysisTransportConfiguration.connectionFactory(settings, null, null));
        resources.push(connection);
        connection.start();
        await(connection::connected, "broker connection");
        return connection;
    }

    private ArtemisAnalysisTaskPublisher publisher(ArtemisAnalysisConnection connection) {
        var publisher = new ArtemisAnalysisTaskPublisher(connection, destinations, codec);
        resources.push(publisher::close);
        return publisher;
    }

    private ArtemisAnalysisWorker worker(AnalysisTaskHandlers handlers, AnalysisTaskCompletionStore store,
                                         String shards) {
        var worker = new ArtemisAnalysisWorker(connect(), destinations, AnalysisWorkerShards.parse(shards), 1,
                handlers, store, codec);
        resources.push(worker);
        worker.start();
        return worker;
    }

    private static AnalysisTaskHandlers subtaxonomyHandler(AtomicInteger effects, Set<AnalysisTaskId> poison) {
        return new AnalysisTaskHandlers(task -> {
            if (poison.contains(task.taskId())) throw new IllegalStateException("poison");
            return new PreparedAnalysisCompletion<>(factory(task.envelope().operationId())
                    .completed(task, AnalysisTaskOutcome.COMPLETED, 50, 3, null), effects::incrementAndGet);
        }, null);
    }

    private static AnalysisMessageFactory factory(String operationId) {
        return new AnalysisMessageFactory(new AnalysisOperationContext(operationId,
                new AnalysisSourceAuthority("repo", "ws", "draft", "c1"),
                RequirementReference.adHoc("requirement"), null), Clock.systemUTC());
    }

    private static SubtaxonomyAnalysisTask subtaxonomyTask(TaxonomyShardRoot root) {
        String operationId = "op-" + UUID.randomUUID();
        var graph = AnalysisTaskGraph.plan(operationId, List.of(root), false);
        return (SubtaxonomyAnalysisTask) factory(operationId).task(graph.tasks().get(0));
    }

    private static RelationAnalysisTask relationTask(TaxonomyShardRoot root) {
        String operationId = "op-" + UUID.randomUUID();
        var graph = AnalysisTaskGraph.plan(operationId, List.of(root), true);
        return (RelationAnalysisTask) factory(operationId)
                .task(graph.tasksOf(AnalysisTaskType.RELATION_ANALYSIS).get(0));
    }

    private Connection rawConnection() throws Exception {
        var factory = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + port);
        Connection connection = factory.createConnection();
        resources.push(factory::close);
        resources.push(connection::close);
        connection.start();
        return connection;
    }

    private List<Message> drain(String queue, int expected, Duration timeout) throws Exception {
        Session session = rawConnection().createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
        List<Message> received = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (received.size() < expected && System.nanoTime() < deadline) {
            Message message = consumer.receive(200);
            if (message != null) received.add(message);
        }
        // Anything beyond the expectation is a duplicate and must fail the test.
        Message extra = consumer.receive(300);
        if (extra != null) received.add(extra);
        consumer.close();
        return received;
    }

    private AnalysisMessage decode(Message message) throws Exception {
        return codec.decode(ArtemisAnalysisMessages.body(message));
    }

    private AnalysisTaskId completedTaskId(Message message) {
        try {
            return ((AnalysisCompletionMessage) decode(message)).taskId();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String rejection(Message message) {
        try {
            return message.getStringProperty(ArtemisAnalysisMessages.REJECTION);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for " + what);
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for " + what);
            }
        }
    }

    /** In-memory transport-test double; the concurrent durable-effect test below uses actual JPA. */
    static final class InMemoryCompletionStore implements AnalysisTaskCompletionStore {
        final Map<AnalysisTaskId, AnalysisCompletionMessage> completions = new ConcurrentHashMap<>();

        @Override
        public Optional<AnalysisCompletionMessage> find(AnalysisTaskId taskId) {
            return Optional.ofNullable(completions.get(taskId));
        }

        @Override
        public AnalysisCompletionMessage commit(PreparedAnalysisCompletion<?> prepared) {
            AnalysisCompletionMessage requested = prepared.completion();
            AnalysisCompletionMessage winner = completions.computeIfAbsent(requested.taskId(), id -> {
                prepared.persistEffect().run();
                return requested;
            });
            AnalysisTaskIdentity.requireSameSource(requested.envelope(), winner.envelope());
            return winner;
        }
    }

    // --- tests -----------------------------------------------------------------------------

    @Test
    void competingWorkersOnOneShardQueueApplyEachTaskOnce() throws Exception {
        var store = new InMemoryCompletionStore();
        var effects = new AtomicInteger();
        var first = worker(subtaxonomyHandler(effects, Set.of()), store, "CP");
        var second = worker(subtaxonomyHandler(effects, Set.of()), store, "CP");
        var publisher = publisher(connect());

        List<SubtaxonomyAnalysisTask> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) tasks.add(subtaxonomyTask(CP));
        tasks.forEach(publisher::publish);

        await(() -> store.completions.size() == 20, "20 completions");
        List<Message> completions = drain(destinations.completion(), 20, Duration.ofSeconds(20));

        assertThat(completions).hasSize(20);
        assertThat(completions.stream().map(this::completedTaskId).toList())
                .containsExactlyInAnyOrderElementsOf(tasks.stream().map(AnalysisTaskMessage::taskId).toList());
        assertThat(effects.get()).isEqualTo(20);
        assertThat(first.executions() + second.executions()).isEqualTo(20);
        assertThat(first.executions()).as("both competing consumers take work").isPositive();
        assertThat(second.executions()).as("both competing consumers take work").isPositive();
    }

    @Test
    void simultaneousDuplicateDeliveriesCommitOneActualDatabaseEffect() throws Exception {
        // Two real worker instances and two real JPA store instances share only the
        // database. Deliberately omit broker duplicate-detection headers, so the
        // broker cannot hide a broken result-commit contract from this test.
        var dataSource = new DriverManagerDataSource(
                "jdbc:hsqldb:mem:broker-effects-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", "");
        var emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource);
        emf.setManagedTypes(PersistenceManagedTypes.of(AnalysisDispatchIntent.class.getName(),
                AnalysisTaskCompletionRecord.class.getName()));
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.search.enabled", "false"));
        emf.afterPropertiesSet();
        resources.push(emf::destroy);
        var transactions = new JpaTransactionManager(emf.getObject());
        var firstStore = new JpaAnalysisTaskCompletionStore(
                SharedEntityManagerCreator.createSharedEntityManager(emf.getObject()), transactions);
        var secondStore = new JpaAnalysisTaskCompletionStore(
                SharedEntityManagerCreator.createSharedEntityManager(emf.getObject()), transactions);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table qa_worker_effect(id varchar(36) primary key)");
        var preparedTogether = new CountDownLatch(2);
        var preparations = new AtomicInteger();
        var effects = new AtomicInteger();
        var handlers = new AnalysisTaskHandlers(task -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("provider computation must not hold the result transaction").isFalse();
            preparations.incrementAndGet();
            preparedTogether.countDown();
            try {
                if (!preparedTogether.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Duplicate deliveries did not overlap");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted preparing task", interrupted);
            }
            return new PreparedAnalysisCompletion<>(factory(task.envelope().operationId())
                    .completed(task, AnalysisTaskOutcome.COMPLETED, 50, 3, null), () -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                effects.incrementAndGet();
                jdbc.update("insert into qa_worker_effect(id) values (?)", UUID.randomUUID().toString());
            });
        }, null);
        var first = worker(handlers, firstStore, "CP");
        var second = worker(handlers, secondStore, "CP");
        var task = subtaxonomyTask(CP);
        Session session = rawConnection().createSession(false, Session.AUTO_ACKNOWLEDGE);
        resources.push(session::close);
        MessageProducer producer = session.createProducer(session.createQueue(destinations.subtaxonomy(CP)));
        for (int i = 0; i < 2; i++) {
            BytesMessage duplicate = session.createBytesMessage();
            duplicate.writeBytes(codec.encode(task));
            producer.send(duplicate);
        }
        List<Message> messages = drain(destinations.completion(), 2, Duration.ofSeconds(20));
        assertThat(messages).hasSize(2);
        assertThat(decode(messages.get(0))).isEqualTo(decode(messages.get(1)));
        assertThat(preparations.get()).as("both deliveries actually reached preparation").isEqualTo(2);
        assertThat(first.executions() + second.executions()).isEqualTo(2);
        assertThat(first.rolledBack() + second.rolledBack()).isZero();
        assertThat(effects.get()).as("only the winning transaction invokes the effect").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from qa_worker_effect", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from analysis_task_completion", Integer.class)).isEqualTo(1);
        assertThat(firstStore.find(task.taskId())).isEqualTo(secondStore.find(task.taskId()));
    }

    @Test
    void crashAfterDurableRecordBeforeAcknowledgeIsRedeliveredWithoutRepeatingTheEffect() throws Exception {
        var store = new InMemoryCompletionStore();
        var effects = new AtomicInteger();
        var worker = worker(subtaxonomyHandler(effects, Set.of()), store, "CP");
        var crashes = new AtomicInteger();
        worker.acknowledgementHook(task -> {
            if (crashes.getAndIncrement() == 0) throw new IllegalStateException("simulated crash before ack");
        });
        SubtaxonomyAnalysisTask task = subtaxonomyTask(CP);

        publisher(connect()).publish(task);

        await(() -> worker.idempotentReplays() == 1, "redelivery replay");
        List<Message> completions = drain(destinations.completion(), 1, Duration.ofSeconds(10));
        assertThat(completions).as("the rolled-back completion send is not visible").hasSize(1);
        assertThat(completedTaskId(completions.get(0))).isEqualTo(task.taskId());
        assertThat(effects.get()).isEqualTo(1);
        assertThat(worker.executions()).isEqualTo(1);
        assertThat(worker.rolledBack()).isEqualTo(1);
    }

    @Test
    void brokerRestartKeepsQueuedTasksAndReportsReconnect() throws Exception {
        var publisherConnection = connect();
        var reconnected = new CountDownLatch(1);
        publisherConnection.addListener(reconnect -> {
            if (reconnect) reconnected.countDown();
        });
        var publisher = publisher(publisherConnection);
        List<SubtaxonomyAnalysisTask> tasks = List.of(subtaxonomyTask(CP), subtaxonomyTask(CP), subtaxonomyTask(CP));
        tasks.forEach(publisher::publish);

        broker.stop();
        await(() -> !publisherConnection.connected(), "connection loss");
        assertThatThrownBy(() -> publisher.publish(subtaxonomyTask(CP)))
                .isInstanceOf(AnalysisTransportUnavailableException.class);

        broker = newBroker();
        broker.start();
        assertThat(reconnected.await(60, TimeUnit.SECONDS)).as("reconnect event drives recovery").isTrue();

        var store = new InMemoryCompletionStore();
        var effects = new AtomicInteger();
        worker(subtaxonomyHandler(effects, Set.of()), store, "CP");
        await(() -> store.completions.size() == 3, "persisted tasks consumed after restart");
        assertThat(store.completions.keySet())
                .containsExactlyInAnyOrderElementsOf(tasks.stream().map(AnalysisTaskMessage::taskId).toList());

        publisher.publish(subtaxonomyTask(CP));
        await(() -> store.completions.size() == 4, "publishing works again after reconnect");
    }

    @Test
    void malformedUnknownSchemaAndMisroutedMessagesAreRejectedWithoutReachingAHandler() throws Exception {
        var store = new InMemoryCompletionStore();
        var effects = new AtomicInteger();
        var worker = worker(subtaxonomyHandler(effects, Set.of()), store, "CP");

        Session session = rawConnection().createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageProducer producer = session.createProducer(session.createQueue(destinations.subtaxonomy(CP)));
        BytesMessage malformed = session.createBytesMessage();
        malformed.writeBytes("not json".getBytes(StandardCharsets.UTF_8));
        producer.send(malformed);
        String futureSchema = new String(codec.encode(subtaxonomyTask(CP)), StandardCharsets.UTF_8)
                .replace("\"schemaVersion\":1", "\"schemaVersion\":99");
        assertThat(futureSchema).contains("\"schemaVersion\":99");
        BytesMessage future = session.createBytesMessage();
        future.writeBytes(futureSchema.getBytes(StandardCharsets.UTF_8));
        producer.send(future);
        producer.send(ArtemisAnalysisMessages.encode(session, codec, relationTask(CP)));
        producer.send(ArtemisAnalysisMessages.encode(session, codec, subtaxonomyTask(TaxonomyShardRoot.of("IP"))));
        producer.send(session.createTextMessage("{}"));

        List<Message> rejected = drain(destinations.rejected(), 5, Duration.ofSeconds(20));

        assertThat(rejected).hasSize(5);
        assertThat(rejected).allSatisfy(message -> assertThat(
                message.getStringProperty(ArtemisAnalysisMessages.ORIGIN)).isEqualTo(destinations.subtaxonomy(CP)));
        assertThat(rejected.stream().map(ArtemisAnalysisTransportTest::rejection).toList())
                .contains("MALFORMED", "UNSUPPORTED_SCHEMA", "INVALID_CONTRACT")
                .doesNotContainNull();
        assertThat(effects.get()).isZero();
        assertThat(store.completions).isEmpty();
        assertThat(worker.rejected()).isEqualTo(5);
    }

    @Test
    void poisonTaskReachesTheDeadLetterQueueWithoutBlockingOtherWork() throws Exception {
        var store = new InMemoryCompletionStore();
        var effects = new AtomicInteger();
        SubtaxonomyAnalysisTask poison = subtaxonomyTask(CP);
        SubtaxonomyAnalysisTask good = subtaxonomyTask(CP);
        var worker = worker(subtaxonomyHandler(effects, Set.of(poison.taskId())), store, "CP");

        var publisher = publisher(connect());
        publisher.publish(poison);
        publisher.publish(good);

        List<Message> dead = drain(DLQ, 1, Duration.ofSeconds(30));
        await(() -> store.find(good.taskId()).isPresent(), "good task completion");

        assertThat(dead).hasSize(1);
        assertThat(((AnalysisTaskMessage) decode(dead.get(0))).taskId()).isEqualTo(poison.taskId());
        assertThat(store.find(poison.taskId())).isEmpty();
        assertThat(worker.rolledBack()).isEqualTo(3);
        assertThat(effects.get()).isEqualTo(1);
    }

    @Test
    void workersSubscribeOnlyToConfiguredShardsAndFamilies() {
        var store = new InMemoryCompletionStore();
        var shardWorker = worker(subtaxonomyHandler(new AtomicInteger(), Set.of()), store, "CP,IP");
        assertThat(shardWorker.consumerCount()).isEqualTo(2);

        var idle = worker(AnalysisTaskHandlers.NONE, store, "");
        assertThat(idle.consumerCount()).as("no handler bound, no consumer").isZero();

        var full = worker(new AnalysisTaskHandlers(null, task -> PreparedAnalysisCompletion.withoutEffects(
                factory(task.envelope().operationId()).completed(task, AnalysisTaskOutcome.COMPLETED, 0, null))), store, "");
        assertThat(full.consumerCount()).as("8 root relation queues + the general relation queue").isEqualTo(9);
    }

    @Test void coordinatorSubscribesToSharedPreparationWhileWorkersOwnOnlyConfiguredRootQueues() {
        var handlers = new AnalysisTaskHandlers(null, task -> PreparedAnalysisCompletion.withoutEffects(
                factory(task.envelope().operationId()).completed(task, AnalysisTaskOutcome.COMPLETED, 0, null)));
        var shared = new ArtemisAnalysisWorker(connect(), destinations, AnalysisWorkerShards.parse("CP"), 1, handlers, new InMemoryCompletionStore(), codec);
        resources.push(shared); shared.start(false, true);
        assertThat(shared.consumerCount()).isEqualTo(1);
        var shards = new ArtemisAnalysisWorker(connect(), destinations, AnalysisWorkerShards.parse(""), 1, handlers, new InMemoryCompletionStore(), codec);
        resources.push(shards); shards.start(true, false);
        assertThat(shards.consumerCount()).isEqualTo(8);
    }

    private record ClusterDatabase(ClusterAnalysisStore store, JpaAnalysisTaskCompletionStore ledger, List<AnalysisTaskMessage> sent) { }

    private ClusterDatabase clusteredDatabase(boolean publish) {
        var emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(new DriverManagerDataSource("jdbc:hsqldb:mem:cluster-broker-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", ""));
        emf.setManagedTypes(PersistenceManagedTypes.of(ClusterAnalysisRun.class.getName(), ClusterAnalysisWork.class.getName(),
                ClusterAnalysisInput.class.getName(), ClusterAnalysisEvent.class.getName(), AnalysisDispatchIntent.class.getName(), AnalysisTaskCompletionRecord.class.getName()));
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.search.enabled", "false"));
        emf.afterPropertiesSet(); resources.push(emf::destroy);
        var transactions = new JpaTransactionManager(emf.getObject());
        var em = SharedEntityManagerCreator.createSharedEntityManager(emf.getObject());
        var sent = new java.util.concurrent.CopyOnWriteArrayList<AnalysisTaskMessage>();
        var publisher = publisher(connect());
        var dispatch = new AnalysisDispatchService(new AnalysisDispatchStore(em, transactions), task -> {
            sent.add(task); if (publish) publisher.publish(task);
        }, 100, 1000);
        var store = new ClusterAnalysisStore(em, transactions, new tools.jackson.databind.ObjectMapper(), dispatch);
        store.eventPublisher(publisher::progress);
        return new ClusterDatabase(store, new JpaAnalysisTaskCompletionStore(em, transactions), sent);
    }

    private ArtemisClusterCoordinator coordinator(ClusterDatabase db, ClusterAnalysisSignals signals, boolean coordinator) {
        var instance = new ArtemisClusterCoordinator(connect(), destinations, codec, db.store(), db.ledger(), signals, coordinator);
        resources.push(instance); instance.start(); return instance;
    }

    private void admit(ClusterDatabase db, AnalysisOperationContext context, Set<String> selected) {
        var command = new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", "alice",
                new WorkspaceContext("alice", "ws", "draft", "repo"), null, new AnalysisScope(selected, AnalysisMode.TAXONOMIES_ONLY));
        var shards = new java.util.LinkedHashMap<TaxonomyShardRoot, String>();
        selected.stream().map(TaxonomyShardRoot::of).forEach(r -> shards.put(r, "{\"root\":\"" + r.code() + "\"}"));
        db.store().admit(context, command, null, shards);
    }

    @Test void eightRootWorkersOverlapAndCoordinatorReplaysPersistedCompletionsAfterRestart() throws Exception {
        var db = clusteredDatabase(true);
        var releaseFinalAcceptance = new CountDownLatch(1);
        var progressPublisher = publisher(connect());
        db.store().eventPublisher(event -> {
            progressPublisher.progress(event);
            if (event.phase() == com.taxonomy.analysis.dag.AnalysisProgressPhase.OPERATION_COMPLETED) {
                // The durable terminal state and live event precede accept() returning
                // to the coordinator, which then updates its local accepted counter.
                try {
                    if (!releaseFinalAcceptance.await(30, TimeUnit.SECONDS))
                        throw new IllegalStateException("Final acceptance was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        });
        var entered = new CountDownLatch(8); var computations = new AtomicInteger();
        for (var root : TaxonomyShardRoot.DEFAULT_ROOTS) {
            var service = new ClusterAnalysisService(db.store(), (input, task, cancelled) -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                computations.incrementAndGet(); entered.countDown();
                try { if (!entered.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Root work serialized"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                var result = new AnalysisResult(Map.of(task.root().code(), 61), List.of()); result.setStatus("SUCCESS"); return result;
            }, new ClusterAnalysisSignals());
            var worker = worker(new AnalysisTaskHandlers(service::prepare, null), db.ledger(), root.code());
            if (root.equals(CP)) {
                var first = new AtomicInteger();
                worker.acknowledgementHook(task -> { if (first.getAndIncrement() == 0) throw new IllegalStateException("after-db-before-ack"); });
            }
        }
        var context = factory("eight-roots").operation();
        admit(db, context, TaxonomyShardRoot.DEFAULT_ROOTS.stream().map(TaxonomyShardRoot::code).collect(java.util.stream.Collectors.toSet()));
        await(() -> db.sent().stream().allMatch(t -> db.ledger().find(t).isPresent()), "eight persisted worker results");
        assertThat(db.store().snapshot(context).completedRoots()).isZero();
        var observed = new ClusterAnalysisSignals();
        var complete = new CountDownLatch(1);
        try (var listen = observed.listen(context, e -> { if (e.phase() == com.taxonomy.analysis.dag.AnalysisProgressPhase.OPERATION_COMPLETED) complete.countDown(); })) {
            coordinator(db, observed, false);
            var first = coordinator(db, new ClusterAnalysisSignals(), true);
            var second = coordinator(db, new ClusterAnalysisSignals(), true);
            assertThat(complete.await(20, TimeUnit.SECONDS)).as("other pod receives live completion").isTrue();
            await(() -> db.store().snapshot(context).state().terminal(), "durable aggregate");
            await(() -> first.accepted() + second.accepted() == 7,
                    "seven coordinator counters before the final after-commit callback returns");
            assertThat(db.store().snapshot(context).completedRoots()).isEqualTo(8);
            releaseFinalAcceptance.countDown();
            await(() -> first.accepted() + second.accepted() == 8,
                    "eight accepted coordinator completions");
            assertThat(first.accepted() + second.accepted()).isEqualTo(8);
        } finally {
            releaseFinalAcceptance.countDown();
        }
        var snapshot = db.store().snapshot(context);
        assertThat(snapshot.completedRoots()).isEqualTo(8);
        assertThat(snapshot.result().getRawScores()).hasSize(8).allSatisfy((root, score) -> assertThat(score).isEqualTo(61));
        assertThat(computations).hasValue(8);
    }

    @Test void deadLetterAndActualExpirySettleExplicitPartialOutcomes() throws Exception {
        var db = clusteredDatabase(false);
        coordinator(db, new ClusterAnalysisSignals(), true);
        worker(new AnalysisTaskHandlers(task -> { throw new IllegalStateException("poison"); }, null), db.ledger(), "CP");
        var poison = factory("poison-root").operation(); admit(db, poison, Set.of("CP"));
        var expired = factory("expired-root").operation(); admit(db, expired, Set.of("IP"));
        var session = rawConnection().createSession(false, Session.AUTO_ACKNOWLEDGE);
        var producer = session.createProducer(null);
        for (var task : db.sent()) producer.send(session.createQueue(destinations.queueFor(task)),
                ArtemisAnalysisMessages.encode(session, codec, task), jakarta.jms.DeliveryMode.PERSISTENT, 4, task.routingRoot().equals(CP) ? 0 : 80);
        await(() -> db.store().snapshot(poison).state().terminal() && db.store().snapshot(expired).state().terminal(), "broker failure settlement");
        for (var context : List.of(poison, expired)) {
            var result = db.store().snapshot(context).result();
            assertThat(result.getStatus()).isEqualTo("PARTIAL"); assertThat(result.getRawScores()).isEmpty();
            assertThat(result.getWarnings()).anyMatch(w -> w.contains(context == poison ? "BROKER_DEAD_LETTER" : "BROKER_EXPIRED"));
        }
        var diagnostics = drain(destinations.failed(), 2, Duration.ofSeconds(5));
        assertThat(diagnostics).hasSize(2);
        for (var message : diagnostics) assertThat(message.getStringProperty("operationId")).isIn("poison-root", "expired-root");
    }

    @Test void cancellationFanoutReachesWorkerPodAndLateSuccessCannotReplaceDurableStop() throws Exception {
        var db = clusteredDatabase(true); var remote = new ClusterAnalysisSignals();
        coordinator(db, remote, false); coordinator(db, new ClusterAnalysisSignals(), true);
        var entered = new CountDownLatch(1); var stopped = new CountDownLatch(1);
        var service = new ClusterAnalysisService(db.store(), (input, task, cancelled) -> {
            entered.countDown(); await(cancelled, "cross-pod cancellation"); stopped.countDown();
            var result = new AnalysisResult(Map.of("CP", 100), List.of()); result.setStatus("SUCCESS"); return result;
        }, remote);
        worker(new AnalysisTaskHandlers(service::prepare, null), db.ledger(), "CP");
        var context = factory("cancel-broadcast").operation(); admit(db, context, Set.of("CP"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        db.store().cancel(context); publisher(connect()).cancellation(factory(context.operationId()).cancellation("CANCELLED"));
        assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
        await(() -> db.ledger().find(db.sent().getFirst()).isPresent(), "late completion ledger");
        assertThat(db.store().snapshot(context).state()).isEqualTo(ClusterAnalysisState.CANCELLED);
        assertThat(db.store().snapshot(context).result().getRawScores()).isEmpty();
    }
}
