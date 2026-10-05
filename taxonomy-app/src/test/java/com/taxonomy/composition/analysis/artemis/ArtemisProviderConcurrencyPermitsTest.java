package com.taxonomy.composition.analysis.artemis;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.analysis.service.AnalysisStoppedException;
import com.taxonomy.analysis.service.AnalysisRunControl;
import com.taxonomy.analysis.service.LlmGateway;
import com.taxonomy.analysis.service.LlmGatewayRegistry;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.service.ProviderConcurrencyPermits;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Persistent real TCP broker and local HTTP server; no mocked JMS or paid provider. */
@Timeout(60)
class ArtemisProviderConcurrencyPermitsTest {
    private static final String PREFIX = "taxonomy.analysis.provider-permits";
    private static final String GROUP = "shared-account";
    private static final String QUEUE = PREFIX + "." + GROUP;
    @TempDir Path data;
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private EmbeddedActiveMQ broker;
    private int port;

    @BeforeEach
    void start() throws Exception {
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        startBroker();
    }

    private void startBroker() throws Exception {
        var config = new ConfigurationImpl().setPersistenceEnabled(true).setSecurityEnabled(false)
                .setJournalType(JournalType.NIO).setThreadPoolMaxSize(4).setScheduledThreadPoolMaxSize(2)
                .setJournalDirectory(data.resolve("journal").toString())
                .setBindingsDirectory(data.resolve("bindings").toString())
                .setLargeMessagesDirectory(data.resolve("large").toString())
                .setPagingDirectory(data.resolve("paging").toString());
        config.addAcceptorConfiguration("tcp", "tcp://127.0.0.1:" + port);
        config.addAddressSetting(PREFIX + ".#", new AddressSettings().setMaxDeliveryAttempts(-1)
                .setRedeliveryDelay(0).setExpiryDelay(-1L).setAutoCreateQueues(false).setAutoDeleteQueues(false));
        broker = new EmbeddedActiveMQ().setConfiguration(config);
        broker.start();
    }

    @AfterEach
    void close() throws Exception {
        while (!resources.isEmpty()) {
            try { resources.pop().close(); } catch (Exception ignored) { }
        }
        if (broker != null) broker.stop();
    }

    @Test
    void twoIndependentClientsAndExplicitAliasesCapActualHttpAttempts() throws Exception {
        provision(2);
        var first = permits(connect(), 5000);
        var second = permits(connect(), 5000);
        var entered = new AtomicInteger();
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var firstWave = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/v1/chat/completions", exchange -> {
            int concurrent = active.incrementAndGet();
            peak.accumulateAndGet(concurrent, Math::max);
            entered.incrementAndGet();
            firstWave.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("HTTP wave not released");
                byte[] response = "{\"choices\":[{\"message\":{\"content\":\"fixture\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
                exchange.close();
            }
        });
        server.start();
        try (var executor = Executors.newFixedThreadPool(8)) {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
            var gateway1 = gateway(LlmProvider.OPENAI, url, first);
            var gateway2 = gateway(LlmProvider.CUSTOM_OPENAI, url, second);
            var calls = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 8; i++) {
                var gateway = i % 2 == 0 ? gateway1 : gateway2;
                calls.add(executor.submit(() -> gateway.sendHttpRequest("fixture", "")));
            }
            try {
                assertThat(firstWave.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(entered.get()).isEqualTo(2);
                assertThat(queue().getDeliveringCount()).isEqualTo(2);
            } finally { release.countDown(); }
            for (var call : calls) assertThat(call.get(10, TimeUnit.SECONDS)).contains("fixture");
            assertThat(entered.get()).isEqualTo(8);
            assertThat(peak.get()).isEqualTo(2);
            await(() -> queue().getMessageCount() == 2 && queue().getDeliveringCount() == 0);
        } finally {
            release.countDown();
            server.stop(0);
            serverThreads.shutdownNow();
        }
    }

    @Test
    void cancellationWhileWaitingRestoresCapacityAndCloseIsIdempotent() throws Exception {
        provision(1);
        var holder = permits(connect(), 5000).acquire(LlmProvider.OPENAI, () -> {});
        var waiting = permits(connect(), 5000);
        var cancelled = new AtomicBoolean();
        var checked = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> waiting.acquire(LlmProvider.CUSTOM_OPENAI, () -> {
                checked.countDown();
                if (cancelled.get()) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
            }));
            assertThat(checked.await(3, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AnalysisStoppedException.class);
        } finally { holder.close(); }
        holder.close();
        try (var next = waiting.acquire(LlmProvider.OPENAI, () -> {})) {
            assertThat(queue().getDeliveringCount()).isOne();
        }
        await(() -> queue().getMessageCount() == 1 && queue().getDeliveringCount() == 0);
    }

    @Test
    void awaitReturnsAfterObservingATransientCondition() {
        var observations = new AtomicInteger();

        await(() -> observations.incrementAndGet() == 2);

        assertThat(observations.get()).isEqualTo(2);
    }

    @Test
    void interruptedReceiverPreservesCooperativeCancellationAndInterruptFlag() throws Exception {
        provision(1);
        var holder = permits(connect(), 5000).acquire(LlmProvider.OPENAI, () -> {});
        var waiting = permits(connect(), 5000);
        var entered = new CountDownLatch(2);
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        var thread = Thread.ofPlatform().start(() -> {
            try (var ignored = waiting.acquire(LlmProvider.OPENAI, () -> {
                AnalysisRunControl.checkpoint();
                entered.countDown();
            })) {
                failure.set(new AssertionError("Expected cooperative stop"));
            } catch (Throwable stopped) {
                failure.set(stopped);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            await(() -> thread.getState() == Thread.State.TIMED_WAITING);
            thread.interrupt();
            thread.join(3000);
            assertThat(thread.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(AnalysisStoppedException.class);
            assertThat(interrupted.get()).isTrue();
        } finally {
            thread.interrupt();
            thread.join(3000);
            holder.close();
        }
    }

    @Test
    void lossOfHolderRedeliversExactlyOnePermitAndBrokerRestartPreservesIt() throws Exception {
        provision(1);
        var lostConnection = connect();
        var lostPermit = permits(lostConnection, 5000).acquire(LlmProvider.OPENAI, () -> {});
        lostConnection.close();
        var survivor = permits(connect(), 5000);
        try (var recovered = survivor.acquire(LlmProvider.OPENAI, () -> {})) {
            assertThatThrownBy(lostPermit::close).isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class);
            assertThat(queue().getDeliveringCount()).isOne();
        }
        while (!resources.isEmpty()) resources.pop().close();
        broker.stop();
        startBroker();
        assertThatThrownBy(() -> provision(1)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
        var restored = permits(connect(), 200);
        try (var one = restored.acquire(LlmProvider.OPENAI, () -> {})) {
            assertThatThrownBy(() -> restored.acquire(LlmProvider.OPENAI, () -> {}))
                    .isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class);
        }
        await(() -> queue().getMessageCount() == 1 && queue().getDeliveringCount() == 0);
    }

    @Test
    void restartWhileHeldCannotCommitAnExtraReplacementToken() throws Exception {
        provision(1);
        var connection = connect();
        var held = permits(connection, 5000).acquire(LlmProvider.OPENAI, () -> {});
        broker.stop();
        await(() -> !connection.connected());
        startBroker();
        await(connection::connected);
        assertThatThrownBy(held::close).isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class);
        var recovered = permits(connect(), 200);
        try (var one = recovered.acquire(LlmProvider.OPENAI, () -> {})) {
            assertThatThrownBy(() -> recovered.acquire(LlmProvider.OPENAI, () -> {}))
                    .isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class);
        }
        await(() -> queue().getMessageCount() == 1 && queue().getDeliveringCount() == 0);
    }

    @Test
    void cancelledImmediatelyAfterDeliveryRollsBackTheToken() {
        provision(1);
        var permits = permits(connect(), 5000);
        assertThatThrownBy(() -> permits.acquire(LlmProvider.OPENAI, () -> {
            if (queue().getDeliveringCount() > 0) {
                throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
            }
        })).isInstanceOf(AnalysisStoppedException.class);
        try (var next = permits.acquire(LlmProvider.OPENAI, () -> {})) {
            assertThat(queue().getDeliveringCount()).isOne();
        }
    }

    @Test
    void failedProvisioningGapDoesNotReplenishAnExistingEmptyQueue() throws Exception {
        broker.getActiveMQServer().createQueue(QueueConfiguration.of(QUEUE).setAddress(QUEUE)
                .setRoutingType(RoutingType.ANYCAST).setDurable(true));
        assertThatThrownBy(() -> provision(2)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
        assertThatThrownBy(() -> permits(connect(), 100).acquire(LlmProvider.OPENAI, () -> {}))
                .isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class);
        assertThat(queue().getMessageCount()).isZero();
    }

    @Test
    void simultaneousAndRepeatedProvisioningNeverAddsCapacity() throws Exception {
        var start = new CountDownLatch(1);
        var successes = new AtomicInteger();
        var rejected = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var runs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) runs.add(executor.submit(() -> {
                start.await();
                try { provision(3); successes.incrementAndGet(); }
                catch (IllegalStateException exists) { rejected.incrementAndGet(); }
                return null;
            }));
            start.countDown();
            for (var run : runs) run.get(10, TimeUnit.SECONDS);
        }
        assertThat(successes.get()).isOne();
        assertThat(rejected.get()).isOne();
        assertThatThrownBy(() -> provision(3)).isInstanceOf(IllegalStateException.class);
        await(() -> queue().getMessageCount() == 3);
    }

    @Test
    void tokensAreDurableMetadataOnlyAndRuntimeNeverCreatesCapacity() throws Exception {
        provision(2);
        var connection = connect();
        try (var session = connection.createSession(true);
             var consumer = session.createConsumer(session.createQueue(QUEUE))) {
            Message token = consumer.receive(2000);
            assertThat(token).isNotNull();
            assertThat(token.getJMSDeliveryMode()).isEqualTo(DeliveryMode.PERSISTENT);
            assertThat(token.getJMSExpiration()).isZero();
            assertThat(token).isExactlyInstanceOf(ActiveMQMessage.class);
            assertThat(((ActiveMQMessage) token).getCoreMessage().getBodySize()).isZero();
            assertThat(Collections.list(token.getPropertyNames()))
                    .contains("schemaVersion", "quotaGroup", "permitId")
                    .allSatisfy(name -> assertThat(name.toString())
                            .isIn("schemaVersion", "quotaGroup", "permitId", "JMSXDeliveryCount"));
            session.rollback();
        }
        var unconfigured = new ArtemisProviderConcurrencyPermits(connection,
                new ArtemisProviderPermitSettings(PREFIX, Map.of(LlmProvider.OPENAI, "absent"), 100));
        assertThatThrownBy(() -> unconfigured.acquire(LlmProvider.OPENAI, () -> {}))
                .isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class);
        assertThatThrownBy(() -> unconfigured.acquire(LlmProvider.GEMINI, () -> {}))
                .isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class);
        assertThat(broker.getActiveMQServer().locateQueue(SimpleString.of(PREFIX + ".absent"))).isNull();
        assertThat(queue().getMessageCount()).isEqualTo(2);
    }

    private ActiveMQConnectionFactory factory() {
        return ArtemisAnalysisTransportConfiguration.connectionFactory(
                new ArtemisAnalysisSettings("tcp://127.0.0.1:" + port, false, 100, 2000), null, null);
    }

    private void provision(int capacity) {
        try (var factory = factory()) {
            ArtemisProviderPermitProvisioner.provision(factory, PREFIX, GROUP, capacity);
        }
    }

    private ArtemisAnalysisConnection connect() {
        var connection = new ArtemisAnalysisConnection(factory());
        resources.push(connection);
        connection.start();
        await(connection::connected);
        return connection;
    }

    private ArtemisProviderConcurrencyPermits permits(ArtemisAnalysisConnection connection, long maximumWait) {
        return new ArtemisProviderConcurrencyPermits(connection, new ArtemisProviderPermitSettings(PREFIX,
                Map.of(LlmProvider.OPENAI, GROUP, LlmProvider.CUSTOM_OPENAI, GROUP), maximumWait));
    }

    private LlmGateway gateway(LlmProvider provider, String url, ProviderConcurrencyPermits permits) {
        var json = JsonMapper.builder().build();
        var config = new LlmProviderConfig(null) {
            @Override public String getOpenAiCompatibleUrl(LlmProvider selected) { return url; }
            @Override public String getOpenAiCompatibleModel(LlmProvider selected) { return "fixture-model"; }
        };
        var httpFactory = new SimpleClientHttpRequestFactory();
        // The registry's fixed upstream URLs are redirected only by this test HTTP factory.
        var http = new RestTemplate((uri, method) -> httpFactory.createRequest(URI.create(url), method));
        var registry = new LlmGatewayRegistry(config, http, json, null, null, null, null);
        registry.configureProviderPermits(permits);
        return registry.getGateway(provider);
    }

    private org.apache.activemq.artemis.core.server.Queue queue() {
        return broker.getActiveMQServer().locateQueue(SimpleString.of(QUEUE));
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        boolean observed = condition.getAsBoolean();
        while (!observed && System.nanoTime() < deadline) {
            try { Thread.sleep(10); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            observed = condition.getAsBoolean();
        }
        assertThat(observed).isTrue();
    }
}
