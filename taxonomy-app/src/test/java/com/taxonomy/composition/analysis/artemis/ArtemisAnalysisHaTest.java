package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.ProviderConcurrencyPermits;
import com.taxonomy.analysis.service.ProviderRateQuotas;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.dispatch.AnalysisDispatchIntent;
import com.taxonomy.analysis.dispatch.AnalysisTaskCompletionRecord;
import com.taxonomy.analysis.dispatch.JpaAnalysisTaskCompletionStore;
import jakarta.jms.*;
import org.apache.activemq.artemis.api.core.*;
import org.apache.activemq.artemis.core.config.ClusterConnectionConfiguration;
import org.apache.activemq.artemis.core.config.ha.SharedStoreBackupPolicyConfiguration;
import org.apache.activemq.artemis.core.config.ha.SharedStorePrimaryPolicyConfiguration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyConnectorFactory;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQSession;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;

/** Same production client across real primary/backup activation; no paid provider or mocked JMS. */
@Timeout(90)
class ArtemisAnalysisHaTest {
    private static final String GROUP = "credential-a";
    private static final String PREFIX = "taxonomy.analysis.provider-quotas";
    private static final String QUEUE = PREFIX + "." + GROUP;
    private final Deque<AutoCloseable> clients = new ArrayDeque<>();
    private final AtomicLong clock = new AtomicLong(100_000);
    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();
    @TempDir Path data;
    EmbeddedActiveMQ primary, backup;
    int primaryPort, backupPort;

    @BeforeEach void startPair() throws Exception {
        primaryPort = freePort(); backupPort = freePort();
        primary = new EmbeddedActiveMQ().setConfiguration(configuration(primaryPort, backupPort, "primary", "backup")
                .setHAPolicyConfiguration(new SharedStorePrimaryPolicyConfiguration().setFailoverOnServerShutdown(true)));
        backup = new EmbeddedActiveMQ().setConfiguration(configuration(backupPort, primaryPort, "backup", "primary")
                .setHAPolicyConfiguration(new SharedStoreBackupPolicyConfiguration().setAllowFailBack(false).setRestartBackup(false)));
        primary.start(); backup.start();
        assertThat(primary.getActiveMQServer().isActive()).isTrue();
        assertThat(backup.getActiveMQServer().isActive()).isFalse();
    }

    @AfterEach void close() throws Exception {
        while (!clients.isEmpty()) try { clients.pop().close(); } catch (Exception ignored) { }
        if (backup != null) backup.stop();
        if (primary != null) primary.stop();
    }

    @Test void sameProductionClientRetainsDebitCooldownAndUnknownUsageAcrossBackupPromotion() throws Exception {
        var policy = new ProviderQuotaLedger.Policy(1, 150, 2, 80, 20, 1000, 16);
        provision(policy);
        var connection = connect();
        var port = new ArtemisProviderRateQuotas(connection, settings(policy), json, clock::get);
        var budget = port.requiredBudget(LlmProvider.OPENAI.id());
        var receipt = port.tryReserve(LlmProvider.OPENAI.id(), UUID.randomUUID().toString(), budget, () -> {}).reservation();
        receipt.beforeTransport(); port.defer(LlmProvider.OPENAI.id(), 5000);
        promote(connection);

        var saved = readState(connection);
        assertThat(saved.debits()).hasSize(1).allSatisfy(debit -> {
            assertThat(debit.id()).isEqualTo(receipt.id()); assertThat(debit.started()).isTrue();
            assertThat(debit.settled()).isFalse(); assertThat(debit.reservedTokens()).isEqualTo(100);
        });
        assertThat(saved.cooldownUntil()).isEqualTo(107_000);
        assertThat(port.tryReserve(LlmProvider.OPENAI.id(), UUID.randomUUID().toString(), budget, () -> {}).granted()).isFalse();
        clock.set(162_000);
        assertThat(port.tryReserve(LlmProvider.OPENAI.id(), UUID.randomUUID().toString(), budget, () -> {}).granted()).isTrue();
        assertThat(port.tryReserve(LlmProvider.OPENAI.id(), UUID.randomUUID().toString(), budget, () -> {}).granted()).isFalse();
    }

    @Test void livePhysicalPermitHolderCannotCreateExtraCapacityWhenItsClientAutomaticallyFailsOver() throws Exception {
        try (var factory = factory()) {
            ArtemisProviderPermitProvisioner.provision(factory, ArtemisProviderPermitSettings.DEFAULT_PREFIX, GROUP, 1);
        }
        var settings = new ArtemisProviderPermitSettings(ArtemisProviderPermitSettings.DEFAULT_PREFIX,
                Map.of(LlmProvider.OPENAI.id(), GROUP), 250);
        var connection = connect();
        var permits = new ArtemisProviderConcurrencyPermits(connection, settings);
        var stillRunningHttp = permits.acquire(LlmProvider.OPENAI.id(), () -> {});
        // The provider operation is still physically running in this process. Broker failover is
        // not evidence of its completion and must not authorize a second physical operation.
        promote(connection);
        assertThatThrownBy(() -> permits.acquire(LlmProvider.OPENAI.id(), () -> {}))
                .as("No additional physical request while the original holder has not completed")
                .isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class)
                .hasMessage("Cluster provider permit wait expired");
        var independent = new ArtemisProviderConcurrencyPermits(connect(), settings);
        assertThatThrownBy(() -> independent.acquire(LlmProvider.OPENAI.id(), () -> {}))
                .as("Another production connection must not acquire the still-occupied slot")
                .isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class)
                .hasMessage("Cluster provider permit wait expired");
        assertThatThrownBy(stillRunningHttp::close)
                .as("Failover makes the old transaction rollback-only; return cannot pretend confirmation")
                .isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class)
                .hasMessage("Cluster provider permit return was not confirmed");
        try (var recovered = permits.acquire(LlmProvider.OPENAI.id(), () -> {})) {
            assertThat(recovered).isNotNull();
            assertThatThrownBy(() -> permits.acquire(LlmProvider.OPENAI.id(), () -> {}))
                    .isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class)
                    .hasMessage("Cluster provider permit wait expired");
        }
    }

    @Test void sameWorkerRedeliversAfterDatabaseCommitAcrossAutomaticBackupPromotionWithoutRepeatingTheEffect() throws Exception {
        var datasource = new DriverManagerDataSource("jdbc:hsqldb:mem:ha-effects-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", "");
        var emf = new LocalContainerEntityManagerFactoryBean(); emf.setDataSource(datasource);
        emf.setManagedTypes(PersistenceManagedTypes.of(AnalysisDispatchIntent.class.getName(), AnalysisTaskCompletionRecord.class.getName()));
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.search.enabled", "false"));
        emf.afterPropertiesSet(); clients.push(emf::destroy);
        var store = new JpaAnalysisTaskCompletionStore(SharedEntityManagerCreator.createSharedEntityManager(emf.getObject()),
                new JpaTransactionManager(emf.getObject()));
        var jdbc = new JdbcTemplate(datasource); jdbc.execute("create table qa_ha_effect(id varchar(36) primary key)");
        var effects = new AtomicInteger(); var prepared = new AtomicInteger();
        var root = TaxonomyShardRoot.of("CP"); var operation = "ha-task-" + UUID.randomUUID();
        var messages = new AnalysisMessageFactory(new AnalysisOperationContext(operation,
                new AnalysisSourceAuthority("repo", "ws", "draft", "immutable-source"),
                RequirementReference.adHoc("requirement"), null), Clock.systemUTC());
        var task = (SubtaxonomyAnalysisTask) messages.task(AnalysisTaskGraph.plan(operation, List.of(root), false).tasks().getFirst());
        var handlers = new AnalysisTaskHandlers(value -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); prepared.incrementAndGet();
            return new PreparedAnalysisCompletion<>(messages.completed(value, AnalysisTaskOutcome.COMPLETED, 50, 3, null), () -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                jdbc.update("insert into qa_ha_effect(id) values (?)", UUID.randomUUID().toString()); effects.incrementAndGet();
            });
        }, null);
        var connection = connect(); var destinations = new AnalysisDestinations(AnalysisDestinations.DEFAULT_PREFIX);
        var codec = new AnalysisMessageCodec();
        var worker = new ArtemisAnalysisWorker(connection, destinations, AnalysisWorkerShards.parse("CP"), 1, handlers, store, codec);
        clients.push(worker); var committed = new CountDownLatch(1); var acknowledge = new CountDownLatch(1);
        worker.acknowledgementHook(value -> {
            if (committed.getCount() == 0) return;
            assertThat(jdbc.queryForObject("select count(*) from analysis_task_completion", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from qa_ha_effect", Integer.class)).isEqualTo(1);
            committed.countDown();
            try { if (!acknowledge.await(20, TimeUnit.SECONDS)) throw new java.lang.IllegalStateException("HA acknowledgement fixture expired"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.lang.IllegalStateException(interrupted); }
        }); worker.start();
        var publisher = new ArtemisAnalysisTaskPublisher(connection, destinations, codec);
        try {
            publisher.publish(task);
            try {
                assertThat(committed.await(15, TimeUnit.SECONDS)).as("durable database commit before JMS acknowledgement").isTrue();
                promote(connection);
            } finally { acknowledge.countDown(); }
            await(() -> worker.idempotentReplays() == 1, "same restored worker replays durable completion");
            try (var session = connection.createSession(false);
                 var consumer = session.createConsumer(session.createQueue(destinations.completion()))) {
                var completion = consumer.receive(5000); assertThat(completion).isNotNull();
                assertThat(((AnalysisCompletionMessage) codec.decode(ArtemisAnalysisMessages.body(completion))).taskId()).isEqualTo(task.taskId());
                assertThat(consumer.receive(250)).as("rolled-back pre-failover completion send is not visible").isNull();
            }
        }
        finally { publisher.close(); }
        assertThat(prepared.get()).isEqualTo(1); assertThat(effects.get()).isEqualTo(1);
        assertThat(worker.executions()).isEqualTo(1); assertThat(worker.rolledBack()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from analysis_task_completion", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from qa_ha_effect", Integer.class)).isEqualTo(1);
    }

    ConfigurationImpl configuration(int ownPort, int peerPort, String own, String peer) throws Exception {
        var config = new ConfigurationImpl().setPersistenceEnabled(true).setSecurityEnabled(false)
                .setJournalType(JournalType.NIO).setThreadPoolMaxSize(4).setScheduledThreadPoolMaxSize(2)
                .setJournalDirectory(data.resolve("journal").toString()).setBindingsDirectory(data.resolve("bindings").toString())
                .setLargeMessagesDirectory(data.resolve("large").toString()).setPagingDirectory(data.resolve("paging").toString());
        config.addAcceptorConfiguration("tcp", "tcp://127.0.0.1:" + ownPort + "?nioRemotingThreads=2");
        config.addConnectorConfiguration(own, new TransportConfiguration(NettyConnectorFactory.class.getName(),
                Map.of("host", "127.0.0.1", "port", ownPort), own));
        config.addConnectorConfiguration(peer, new TransportConfiguration(NettyConnectorFactory.class.getName(),
                Map.of("host", "127.0.0.1", "port", peerPort), peer));
        config.addClusterConfiguration(new ClusterConnectionConfiguration().setName("ha-pair").setAddress("#")
                .setConnectorName(own).setStaticConnectors(List.of(peer)).setRetryInterval(100)
                .setClientFailureCheckPeriod(100).setConnectionTTL(1000));
        config.addAddressSetting("#", new AddressSettings().setMaxDeliveryAttempts(-1).setRedeliveryDelay(0)
                .setExpiryDelay(-1L).setAutoDeleteQueues(false));
        return config;
    }

    ActiveMQConnectionFactory factory() {
        var settings = new ArtemisAnalysisSettings("(tcp://127.0.0.1:" + primaryPort
                + ",tcp://127.0.0.1:" + backupPort + ")?ha=true", false, 100, 3000);
        var factory = ArtemisAnalysisTransportConfiguration.connectionFactory(settings, "", "");
        factory.setClientFailureCheckPeriod(100); factory.setConnectionTTL(1000);
        return factory;
    }

    ArtemisAnalysisConnection connect() throws Exception {
        var connection = new ArtemisAnalysisConnection(factory()); clients.push(connection); connection.start();
        await(connection::connected, "initial production connection");
        return connection;
    }

    void promote(ArtemisAnalysisConnection connection) throws Exception {
        var reconnect = new CountDownLatch(1);
        connection.addListener(again -> { if (again) reconnect.countDown(); });
        primary.stop();
        await(() -> backup.getActiveMQServer().isActive(), "backup promotion");
        assertThat(reconnect.await(15, TimeUnit.SECONDS)).as("same production client automatic failover event").isTrue();
        assertThat(connection.connected()).isTrue();
    }

    ArtemisProviderQuotaSettings settings(ProviderQuotaLedger.Policy policy) {
        return new ArtemisProviderQuotaSettings(PREFIX, Map.of(LlmProvider.OPENAI.id(), GROUP), Map.of(GROUP, policy),
                Map.of(GROUP, new ProviderRateQuotas.TokenBudget("fixture-text-budget", 80, 20)), 3000);
    }

    void provision(ProviderQuotaLedger.Policy policy) throws Exception {
        try (var factory = factory(); var connection = factory.createConnection();
             var session = connection.createSession(true, Session.SESSION_TRANSACTED)) {
            ((ActiveMQSession) session).getCoreSession().createQueue(QueueConfiguration.of(QUEUE).setAddress(QUEUE)
                    .setRoutingType(RoutingType.ANYCAST).setDurable(true).setExclusive(true).setAutoDelete(false).setAutoCreated(false));
            try (var producer = session.createProducer(session.createQueue(QUEUE + "::" + QUEUE))) {
                var message = session.createTextMessage(json.writeValueAsString(ProviderQuotaLedger.empty(policy)));
                message.setIntProperty("schemaVersion", 1); message.setStringProperty("quotaGroup", GROUP);
                message.setStringProperty("policyFingerprint", policy.fingerprint()); message.setStringProperty("tokenAdapter", "fixture-text-budget");
                producer.send(message, DeliveryMode.PERSISTENT, jakarta.jms.Message.DEFAULT_PRIORITY, 0); session.commit();
            }
        }
    }

    ProviderQuotaLedger.State readState(ArtemisAnalysisConnection connection) throws Exception {
        try (var session = connection.createSession(false); var browser = session.createBrowser(session.createQueue(QUEUE + "::" + QUEUE))) {
            var values = browser.getEnumeration(); assertThat(values.hasMoreElements()).isTrue();
            var message = (TextMessage) values.nextElement(); assertThat(values.hasMoreElements()).isFalse();
            return json.readValue(message.getText(), ProviderQuotaLedger.State.class);
        }
    }

    static int freePort() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    static void await(BooleanSupplier ready, String phase) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(ready.getAsBoolean()).as(phase).isTrue();
    }
}
