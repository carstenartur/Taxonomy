package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.cluster.*;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.dispatch.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.Topic;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real database and broker redelivery with an independently connected observation pod. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ArtemisCoordinatorNotificationRecoveryTest {
    private static final TaxonomyShardRoot CP = TaxonomyShardRoot.of("CP");
    private static final String TEXT = "Notification recovery fixture requirement";
    private static final WorkspaceContext SCOPE = new WorkspaceContext("alice", "workspace", "draft", "repo");
    private final AnalysisDestinations destinations = new AnalysisDestinations(AnalysisDestinations.DEFAULT_PREFIX);
    private final AnalysisMessageCodec codec = new AnalysisMessageCodec();
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    @TempDir Path data;
    private EmbeddedActiveMQ broker;
    private int port;

    @BeforeEach void startBroker() throws Exception {
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var configuration = new ConfigurationImpl();
        configuration.setPersistenceEnabled(true);
        configuration.setSecurityEnabled(false);
        configuration.setJournalType(JournalType.NIO);
        configuration.setJournalDirectory(data.resolve("journal").toString());
        configuration.setBindingsDirectory(data.resolve("bindings").toString());
        configuration.setLargeMessagesDirectory(data.resolve("large").toString());
        configuration.setPagingDirectory(data.resolve("paging").toString());
        configuration.addAcceptorConfiguration("tcp", "tcp://127.0.0.1:" + port);
        configuration.addAddressSetting("#", new AddressSettings().setMaxDeliveryAttempts(3).setRedeliveryDelay(100)
                .setDeadLetterAddress(SimpleString.of(destinations.deadLetter()))
                .setExpiryAddress(SimpleString.of(destinations.expiry())));
        // Production retains failed deliveries in these queues for recoverable coordinator work.
        for (String name : List.of(destinations.deadLetter(), destinations.expiry())) {
            configuration.addAddressSetting(name, new AddressSettings().setMaxDeliveryAttempts(-1).setRedeliveryDelay(100));
            configuration.addQueueConfiguration(QueueConfiguration.of(name).setRoutingType(RoutingType.ANYCAST));
        }
        broker = new EmbeddedActiveMQ();
        broker.setConfiguration(configuration);
        broker.start();
    }

    @AfterEach void stop() throws Exception {
        while (!resources.isEmpty()) resources.pop().close();
        if (broker != null) broker.stop();
    }

    @Test void coordinatorRedeliveryRepairsTheCommitToFanoutGapWithoutReconnectingTheObserver() throws Exception {
        var db = database();
        var context = context("commit-fanout-gap");
        var completion = db.prepare(context);
        var observed = observe(db, context);
        var suppressed = new AtomicInteger();
        db.store.eventPublisher(event -> {
            if (event.phase() == AnalysisProgressPhase.OPERATION_COMPLETED) suppressed.incrementAndGet();
            // Simulate the coordinator dying before its after-commit live publication.
        });
        var coordinator = coordinator(db, connect());
        var finalized = new AtomicInteger();
        coordinator.finalizer(ignored -> {
            if (finalized.getAndIncrement() == 0) {
                assertThat(db.store.snapshot(context).state()).isEqualTo(ClusterAnalysisState.COMPLETED);
                throw new IllegalStateException("simulated coordinator failure after database commit");
            }
        });
        send(completion);
        await(() -> finalized.get() == 2 && messages(destinations.completion()) == 0, "completion redelivery and acknowledgement");
        assertThat(coordinator.redeliveries()).isEqualTo(1);
        assertThat(coordinator.accepted()).isEqualTo(1);
        assertThat(suppressed).hasValue(1);
        assertRecovered(db, context, observed);
    }

    @Test void failedNotificationSendRollsBackTheCompletionAndRetriesTheStoredRevision() throws Exception {
        var db = database();
        var context = context("transient-notification-failure");
        var completion = db.prepare(context);
        db.store.accept(completion);
        var observed = observe(db, context);
        var failures = new AtomicInteger();
        var coordinator = coordinator(db, failProgressSends(() -> failures.getAndIncrement() == 0));
        send(completion);
        await(() -> coordinator.redeliveries() == 1 && messages(destinations.completion()) == 0,
                "retry after notification send failure");
        assertThat(failures.get()).isEqualTo(2);
        assertThat(coordinator.accepted()).isZero();
        assertRecovered(db, context, observed);
    }

    @Test void persistentNotificationFailureRetainsTheDeliveryUntilAHealthyCoordinatorCanObserveIt() throws Exception {
        var db = database();
        var context = context("persistent-notification-failure");
        var completion = db.prepare(context);
        db.store.accept(completion);
        var observed = observe(db, context);
        var attempts = new AtomicInteger();
        var failing = coordinator(db, failProgressSends(() -> { attempts.incrementAndGet(); return true; }));
        send(completion);
        await(() -> attempts.get() >= 3, "broker-driven notification redelivery");
        failing.close();
        // Closing the JMS sessions does not wait for the broker's asynchronous
        // journal commit to finish moving the third failed delivery to the DLQ.
        var retained = broker.getActiveMQServer().locateQueue(SimpleString.of(destinations.deadLetter()));
        await(() -> messages(destinations.completion()) == 0 && retained.getMessageCount() == 1
                        && retained.getDeliveringCount() == 0 && retained.getScheduledCount() == 0,
                "one failed completion retained after the broker's dead-letter handoff");
        assertThat(failing.redeliveries()).isGreaterThanOrEqualTo(3);
        assertThat(observed.events).isEmpty();
        assertThat(retained.getMessageCount()).isEqualTo(1);
        assertThat(db.store.snapshot(context).state()).isEqualTo(ClusterAnalysisState.COMPLETED);
        coordinator(db, connect());
        assertRecovered(db, context, observed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"repository", "correlation"})
    void foreignCompletionDoesNotPublishAnExistingOperationsRevision(String changed) throws Exception {
        var db = database();
        var context = context("foreign-completion-" + changed);
        db.store.accept(db.prepare(context));
        var observed = observe(db, context);
        var foreign = new AnalysisOperationContext(context.operationId(), changed.equals("repository")
                ? new AnalysisSourceAuthority("foreign-repo", SCOPE.workspaceId(), SCOPE.currentBranch(), "source-commit")
                : context.authority(), context.requirement(), changed.equals("correlation") ? "foreign-correlation" : context.correlationId());
        var factory = new AnalysisMessageFactory(foreign, Clock.systemUTC());
        var task = (SubtaxonomyAnalysisTask) factory.task(AnalysisTaskGraph.plan(context.operationId(), List.of(CP), false).tasks().getFirst());
        var coordinator = coordinator(db, connect());
        send(factory.completed(task, AnalysisTaskOutcome.COMPLETED, 73, 1, null));
        await(() -> coordinator.failures() == 1 && messages(destinations.completion()) == 0, "rejected foreign completion");
        assertThat(observed.events).isEmpty();
        assertThat(coordinator.redeliveries()).isZero();
        assertThat(db.store.snapshot(context).result().getRawScores()).containsEntry("CP", 73);
    }

    private void assertRecovered(Database db, AnalysisOperationContext context, Observed observed) throws Exception {
        await(() -> !observed.events.isEmpty(), "terminal notification on the still-connected observation pod");
        var saved = db.store.snapshot(context);
        assertThat(observed.events).allSatisfy(event -> {
            assertThat(ClusterAnalysisStore.context(event.envelope())).isEqualTo(context);
            assertThat(event.sequence()).isEqualTo(saved.revision());
            assertThat(event.phase()).isEqualTo(AnalysisProgressPhase.OPERATION_COMPLETED);
            assertThat(event.completedTasks()).isEqualTo(1);
            assertThat(event.totalTasks()).isEqualTo(1);
        });
        assertThat(saved.state()).isEqualTo(ClusterAnalysisState.COMPLETED);
        assertThat(saved.result().getRawScores()).containsEntry("CP", 73);
        assertThat(db.store.events(context, 0, 100)).hasSize((int) saved.revision());
        assertThat(observed.reconnects).hasValue(0);
        assertThat(observed.foreignWakes).hasValue(0);
        assertThat(db.sent).hasSize(1);
    }

    private record Observed(List<AnalysisProgressEvent> events, AtomicInteger reconnects, AtomicInteger foreignWakes) { }

    private Observed observe(Database db, AnalysisOperationContext context) {
        var signals = new ClusterAnalysisSignals();
        var events = new CopyOnWriteArrayList<AnalysisProgressEvent>();
        var reconnects = new AtomicInteger();
        var foreignWakes = new AtomicInteger();
        resources.push(signals.listen(context, events::add));
        resources.push(signals.listen(new AnalysisOperationContext(context.operationId(), context.authority(),
                context.requirement(), "other-observer-correlation"), ignored -> foreignWakes.incrementAndGet()));
        var connection = connect();
        connection.addListener(reconnect -> { if (reconnect) reconnects.incrementAndGet(); });
        var observer = new ArtemisClusterCoordinator(connection, destinations, codec, db.store, db.ledger, signals, false);
        resources.push(observer);
        observer.start();
        return new Observed(events, reconnects, foreignWakes);
    }

    private ArtemisClusterCoordinator coordinator(Database db, ArtemisAnalysisConnection connection) {
        var coordinator = new ArtemisClusterCoordinator(connection, destinations, codec, db.store, db.ledger, new ClusterAnalysisSignals(), true);
        resources.push(coordinator);
        coordinator.start();
        return coordinator;
    }

    private ArtemisAnalysisConnection connect() {
        var connection = new ArtemisAnalysisConnection(ArtemisAnalysisTransportConfiguration.connectionFactory(
                new ArtemisAnalysisSettings("tcp://127.0.0.1:" + port, false, 100, 2_000), null, null));
        resources.push(connection);
        connection.start();
        try { await(connection::connected, "broker connection"); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        return connection;
    }

    /** Only the outbound progress send fails; all sessions, transactions and redeliveries remain real. */
    private ArtemisAnalysisConnection failProgressSends(BooleanSupplier fail) {
        var real = connect();
        var boundary = mock(ArtemisAnalysisConnection.class);
        when(boundary.createSession(anyBoolean())).thenAnswer(invocation -> {
            Session session = real.createSession(invocation.getArgument(0));
            return Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class}, (proxy, method, args) -> {
                Object value = invoke(session, method, args);
                if (!method.getName().equals("createProducer")) return value;
                var producer = (MessageProducer) value;
                return Proxy.newProxyInstance(MessageProducer.class.getClassLoader(), new Class<?>[] {MessageProducer.class}, (p, send, arguments) -> {
                    if (send.getName().equals("send") && arguments[0] instanceof Topic topic
                            && destinations.progress().equals(topic.getTopicName()) && fail.getAsBoolean()) {
                        throw new JMSException("injected progress publication failure");
                    }
                    return invoke(producer, send, arguments);
                });
            });
        });
        return boundary;
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private void send(AnalysisCompletionMessage completion) throws Exception {
        try (Session session = connect().createSession(false); MessageProducer producer = session.createProducer(null)) {
            producer.send(session.createQueue(destinations.completion()), ArtemisAnalysisMessages.encode(session, codec, completion));
        }
    }

    private long messages(String name) {
        var queue = broker.getActiveMQServer().locateQueue(SimpleString.of(name));
        return queue == null ? 0 : queue.getMessageCount();
    }

    private static AnalysisOperationContext context(String operation) {
        return new AnalysisOperationContext(operation, new AnalysisSourceAuthority("repo", "workspace", "draft", "source-commit"),
                RequirementReference.adHoc(TEXT), "correlation-" + operation);
    }

    private Database database() {
        var emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(new DriverManagerDataSource("jdbc:hsqldb:mem:notification-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", ""));
        emf.setManagedTypes(PersistenceManagedTypes.of(ClusterAnalysisRun.class.getName(), ClusterAnalysisWork.class.getName(),
                ClusterAnalysisInput.class.getName(), ClusterAnalysisEvent.class.getName(), AnalysisDispatchIntent.class.getName(), AnalysisTaskCompletionRecord.class.getName()));
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.search.enabled", "false"));
        emf.afterPropertiesSet();
        resources.push(emf::destroy);
        var transactions = new JpaTransactionManager(emf.getObject());
        var em = SharedEntityManagerCreator.createSharedEntityManager(emf.getObject());
        var sent = new ArrayList<AnalysisTaskMessage>();
        var store = new ClusterAnalysisStore(em, transactions, new ObjectMapper(), new AnalysisDispatchService(
                new AnalysisDispatchStore(em, transactions), sent::add, 10, 100));
        return new Database(store, new JpaAnalysisTaskCompletionStore(em, transactions), sent);
    }

    private record Database(ClusterAnalysisStore store, JpaAnalysisTaskCompletionStore ledger, List<AnalysisTaskMessage> sent) {
        AnalysisCompletionMessage prepare(AnalysisOperationContext context) {
            store.admit(context, new AnalyzeRequirementCommand(TEXT, false, 20, "MOCK", "alice", SCOPE, null,
                    new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY)), null, Map.of(CP, "{}"));
            var task = (SubtaxonomyAnalysisTask) sent.getFirst();
            store.start(task);
            var result = new AnalysisResult(Map.of("CP", 73), List.of());
            result.setStatus("SUCCESS");
            var completed = new AnalysisMessageFactory(context, Clock.systemUTC()).completed(task, AnalysisTaskOutcome.COMPLETED, 73, 1, null);
            return ledger.commit(new PreparedAnalysisCompletion<>(completed, () -> store.persistResult(task, result)));
        }
    }

    private static void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }
}
