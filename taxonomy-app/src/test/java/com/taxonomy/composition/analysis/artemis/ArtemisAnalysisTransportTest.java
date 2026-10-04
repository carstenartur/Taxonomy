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
import com.taxonomy.analysis.dag.AnalysisTaskType;
import com.taxonomy.analysis.dag.AnalysisTransportUnavailableException;
import com.taxonomy.analysis.dag.AnalysisWorkerShards;
import com.taxonomy.analysis.dag.RelationAnalysisTask;
import com.taxonomy.analysis.dag.RequirementReference;
import com.taxonomy.analysis.dag.SubtaxonomyAnalysisTask;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
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
        config.addAcceptorConfiguration("tcp", "tcp://127.0.0.1:" + port);
        config.addAddressSetting("#", new AddressSettings()
                .setMaxDeliveryAttempts(3)
                .setRedeliveryDelay(0)
                .setDeadLetterAddress(SimpleString.of(DLQ)));
        config.addQueueConfiguration(QueueConfiguration.of(DLQ).setRoutingType(RoutingType.ANYCAST));
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
            effects.incrementAndGet();
            return factory(task.envelope().operationId())
                    .completed(task, AnalysisTaskOutcome.COMPLETED, 50, 3, null);
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

    /** Durable ledger double with the same first-writer-wins contract as the JPA store. */
    static final class InMemoryCompletionStore implements AnalysisTaskCompletionStore {
        final Map<AnalysisTaskId, AnalysisCompletionMessage> completions = new ConcurrentHashMap<>();

        @Override
        public Optional<AnalysisCompletionMessage> find(AnalysisTaskId taskId) {
            return Optional.ofNullable(completions.get(taskId));
        }

        @Override
        public AnalysisCompletionMessage recordIfAbsent(AnalysisCompletionMessage completion) {
            AnalysisCompletionMessage winner = completions.putIfAbsent(completion.taskId(), completion);
            return winner == null ? completion : winner;
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

        var full = worker(new AnalysisTaskHandlers(null, task -> factory(task.envelope().operationId())
                .completed(task, AnalysisTaskOutcome.COMPLETED, 0, null)), store, "");
        assertThat(full.consumerCount()).as("8 root relation queues + the general relation queue").isEqualTo(9);
    }
}
