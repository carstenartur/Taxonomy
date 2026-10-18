package com.taxonomy.composition.analysis.artemis;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.ProviderConcurrencyPermits;
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

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real persistent broker and real independent worker JVM/loopback HTTP; no paid inference. */
@Timeout(45)
class ArtemisProviderPermitQuarantineTest {
    private static final String PREFIX = "taxonomy.analysis.provider-permits";
    private static final String GROUP = "quarantine-fixture";
    @TempDir Path data;
    private final Deque<ArtemisAnalysisConnection> connections = new ArrayDeque<>();
    private EmbeddedActiveMQ broker;
    private int port;

    @BeforeEach
    void start() throws Exception {
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        startBroker();
        try (var factory = factory()) { ArtemisProviderPermitProvisioner.provision(factory, PREFIX, GROUP, 1); }
    }

    private void startBroker() throws Exception {
        var config = new ConfigurationImpl().setPersistenceEnabled(true).setSecurityEnabled(false)
                .setJournalSyncTransactional(true).setJournalType(JournalType.NIO)
                .setThreadPoolMaxSize(4).setScheduledThreadPoolMaxSize(2)
                .setJournalDirectory(data.resolve("journal").toString())
                .setBindingsDirectory(data.resolve("bindings").toString())
                .setLargeMessagesDirectory(data.resolve("large").toString())
                .setPagingDirectory(data.resolve("paging").toString());
        config.addAcceptorConfiguration("tcp", brokerUrl());
        config.addAddressSetting(PREFIX + ".#", new AddressSettings().setMaxDeliveryAttempts(-1)
                .setRedeliveryDelay(0).setExpiryDelay(-1L).setAutoCreateQueues(false).setAutoDeleteQueues(false));
        broker = new EmbeddedActiveMQ().setConfiguration(config);
        broker.start();
    }

    @AfterEach
    void stop() throws Exception {
        while (!connections.isEmpty()) connections.pop().close();
        if (broker != null) broker.stop();
    }

    @Test
    void unknownOutcomeSurvivesBrokerRestartWithoutRecreatingCapacity() throws Exception {
        try (var held = permits().acquire(LlmProvider.OPENAI, () -> {})) { held.beforeTransport(); }
        while (!connections.isEmpty()) connections.pop().close();
        broker.stop(); startBroker();
        assertThrows(ProviderConcurrencyPermits.UnavailableException.class,
                () -> permits().acquire(LlmProvider.OPENAI, () -> {}));
        try (var factory = factory()) {
            var inspection = ArtemisProviderPermitProvisioner.inspect(factory, PREFIX, GROUP, 10);
            assertEquals(1, inspection.receipts().size()); assertFalse(inspection.truncated());
            assertThrows(IllegalStateException.class, () -> ArtemisProviderPermitProvisioner.provision(factory, PREFIX, GROUP, 1));
        }
    }

    @Test
    void completedResponseReturnsExactlyOneSlot() throws Exception {
        try (var held = permits().acquire(LlmProvider.OPENAI, () -> {})) {
            held.beforeTransport(); held.responseReceived(200);
        }
        var permits = permits();
        try (var held = permits.acquire(LlmProvider.OPENAI, () -> {})) {
            assertThrows(ProviderConcurrencyPermits.UnavailableException.class,
                    () -> permits.acquire(LlmProvider.OPENAI, () -> {}));
        }
    }

    @Test
    void reconciliationRequiresExactReceiptAndExplicitUpstreamConfirmation() throws Exception {
        try (var held = permits().acquire(LlmProvider.OPENAI, () -> {})) { held.beforeTransport(); }
        try (var factory = factory()) {
            var receipt = ArtemisProviderPermitProvisioner.inspect(factory, PREFIX, GROUP, 10).receipts().getFirst();
            assertThrows(IllegalArgumentException.class, () -> ArtemisProviderPermitProvisioner.reconcile(factory,
                    PREFIX, GROUP, receipt.permitId(), receipt.attemptId(), false, true));
            assertThrows(IllegalStateException.class, () -> ArtemisProviderPermitProvisioner.reconcile(factory,
                    PREFIX, GROUP, receipt.permitId(), UUID.randomUUID().toString(), true, true));
            ArtemisProviderPermitProvisioner.reconcile(factory, PREFIX, GROUP, receipt.permitId(), receipt.attemptId(), true, true);
            assertThrows(IllegalStateException.class, () -> ArtemisProviderPermitProvisioner.reconcile(factory,
                    PREFIX, GROUP, receipt.permitId(), receipt.attemptId(), true, true));
            assertTrue(ArtemisProviderPermitProvisioner.inspect(factory, PREFIX, GROUP, 10).receipts().isEmpty());
        }
        var permits = permits();
        try (var held = permits.acquire(LlmProvider.OPENAI, () -> {})) {
            assertThrows(ProviderConcurrencyPermits.UnavailableException.class,
                    () -> permits.acquire(LlmProvider.OPENAI, () -> {}));
        }
    }

    @Test
    void killedWorkerCannotOverlapItsStillRunningHttpRequest() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var finished = new CountDownLatch(1);
        var active = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var serverThreads = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
        server.setExecutor(serverThreads);
        server.createContext("/provider", exchange -> {
            active.incrementAndGet(); entered.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Provider was not released");
                byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException disconnectedWorker) { /* The remote work still reached its controlled end. */ }
            finally { active.decrementAndGet(); exchange.close(); finished.countDown(); }
        });
        server.start();
        Process worker = null;
        try {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            worker = new ProcessBuilder(java, "-Xmx256m", "-cp", classpath, Worker.class.getName(), brokerUrl(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/provider")
                    .redirectErrorStream(true).redirectOutput(data.resolve("worker.log").toFile()).start();
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Independent worker must enter actual provider HTTP");
            worker.destroyForcibly(); assertTrue(worker.waitFor(5, TimeUnit.SECONDS));
            // Wait until the broker actually rolls back the dead worker delivery:
            // rejection while its old delivery is still held would not prove quarantine.
            await(() -> {
                var queue = broker.getActiveMQServer().locateQueue(
                        org.apache.activemq.artemis.api.core.SimpleString.of(PREFIX + "." + GROUP));
                return queue.getDeliveringCount() == 0 && queue.getMessageCount() == 1;
            });
            assertEquals(1, active.get(), "Remote request is still running after worker death");
            assertThrows(ProviderConcurrencyPermits.UnavailableException.class,
                    () -> permits().acquire(LlmProvider.OPENAI, () -> {}));
            assertEquals(1, active.get(), "Replacement admission must not overlap remote work");
            release.countDown(); assertTrue(finished.await(5, TimeUnit.SECONDS));
            try (var factory = factory()) {
                await(() -> !ArtemisProviderPermitProvisioner.inspect(factory, PREFIX, GROUP, 10).receipts().isEmpty());
                var receipt = ArtemisProviderPermitProvisioner.inspect(factory, PREFIX, GROUP, 10).receipts().getFirst();
                ArtemisProviderPermitProvisioner.reconcile(factory, PREFIX, GROUP,
                        receipt.permitId(), receipt.attemptId(), true, true);
            }
            try (var restored = permits().acquire(LlmProvider.OPENAI, () -> {})) { assertEquals(0, active.get()); }
        } finally {
            release.countDown();
            if (worker != null && worker.isAlive()) { worker.destroyForcibly(); worker.waitFor(5, TimeUnit.SECONDS); }
            server.stop(0); serverThreads.shutdownNow();
        }
    }

    private String brokerUrl() { return "tcp://127.0.0.1:" + port; }
    private ActiveMQConnectionFactory factory() {
        var factory = ArtemisAnalysisTransportConfiguration.connectionFactory(
                new ArtemisAnalysisSettings(brokerUrl(), false, 100, 2000), null, null);
        factory.setConnectionTTL(1000);
        factory.setClientFailureCheckPeriod(250);
        return factory;
    }
    private ArtemisProviderConcurrencyPermits permits() throws Exception {
        var connection = new ArtemisAnalysisConnection(factory()); connections.push(connection);
        connection.start(); await(connection::connected);
        return new ArtemisProviderConcurrencyPermits(connection,
                new ArtemisProviderPermitSettings(PREFIX, Map.of(LlmProvider.OPENAI.id(), GROUP), 250));
    }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Condition was not observed");
            Thread.sleep(20);
        }
    }

    /** Disposable worker fixture; forcibly killed by its owning JUnit test. */
    public static final class Worker {
        public static void main(String[] args) throws Exception {
            var factory = ArtemisAnalysisTransportConfiguration.connectionFactory(
                    new ArtemisAnalysisSettings(args[0], false, 100, 2000), null, null);
            factory.setConnectionTTL(1000);
            factory.setClientFailureCheckPeriod(250);
            var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            try (var connection = new ArtemisAnalysisConnection(factory); var http = HttpClient.newHttpClient()) {
                connection.start(); await(connection::connected);
                var settings = new ArtemisProviderPermitSettings(PREFIX, Map.of(LlmProvider.OPENAI.id(), GROUP), 5000);
                var permits = new ArtemisAnalysisMetrics(registry).permits(
                        new ArtemisProviderConcurrencyPermits(connection, settings), settings);
                try (var permit = permits.acquire(LlmProvider.OPENAI, () -> {})) {
                    permit.beforeTransport();
                    var response = http.send(HttpRequest.newBuilder(URI.create(args[1])).timeout(Duration.ofSeconds(20))
                            .POST(HttpRequest.BodyPublishers.ofString("fixture")).build(), HttpResponse.BodyHandlers.ofString());
                    permit.responseReceived(response.statusCode());
                }
            } finally { registry.close(); }
        }
    }
}
