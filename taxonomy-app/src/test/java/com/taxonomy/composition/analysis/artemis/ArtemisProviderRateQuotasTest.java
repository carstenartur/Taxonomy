package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.ProviderRateQuotas;
import jakarta.jms.*;
import org.apache.activemq.artemis.api.core.*;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.jms.client.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual persistent TCP broker; physical admission callback is never a paid provider. */
@Timeout(60)
class ArtemisProviderRateQuotasTest {
    static final String PREFIX = "taxonomy.analysis.provider-quotas", GROUP = "credential-a", QUEUE = PREFIX + "." + GROUP;
    @TempDir Path data;
    EmbeddedActiveMQ broker; int port;
    final Deque<AutoCloseable> resources = new ArrayDeque<>();
    final AtomicLong clock = new AtomicLong(100_000);
    final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();
    @BeforeEach void start() throws Exception { try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); } startBroker(); }
    void startBroker() throws Exception {
        broker = new EmbeddedActiveMQ().setConfiguration(configuration(port));broker.start();
    }
    ConfigurationImpl configuration(int listeningPort) throws Exception {
        var config = new ConfigurationImpl().setPersistenceEnabled(true).setSecurityEnabled(false)
                .setJournalType(JournalType.NIO).setThreadPoolMaxSize(4).setScheduledThreadPoolMaxSize(2)
                .setJournalDirectory(data.resolve("journal").toString()).setBindingsDirectory(data.resolve("bindings").toString())
                .setLargeMessagesDirectory(data.resolve("large").toString()).setPagingDirectory(data.resolve("paging").toString());
        config.addAcceptorConfiguration("tcp", "tcp://127.0.0.1:" + listeningPort);
        config.addAddressSetting(PREFIX + ".#", new AddressSettings().setMaxDeliveryAttempts(-1).setRedeliveryDelay(0)
                .setExpiryDelay(-1L).setAutoCreateQueues(false).setAutoDeleteQueues(false));
        return config;
    }
    @AfterEach void close() throws Exception { while (!resources.isEmpty()) resources.pop().close(); if (broker != null) broker.stop(); }

    @Test void twoConnectionsAndAliasesDebitOneSharedMinuteAndDayState() throws Exception {
        var policy = new ProviderQuotaLedger.Policy(2, 0, 3, 0, 0, 1000, 16); provision(policy);
        var first = quotas(connect(), policy); var second = quotas(connect(), policy);
        var physical = new AtomicInteger();
        assertThat(send(first, LlmProvider.OPENAI, physical)).isTrue();
        assertThat(send(second, LlmProvider.CUSTOM_OPENAI, physical)).isTrue();
        assertThat(send(first, LlmProvider.OPENAI, physical)).isFalse();
        assertThat(physical.get()).isEqualTo(2);
        clock.set(162_000);
        assertThat(send(second, LlmProvider.CUSTOM_OPENAI, physical)).isTrue();
        assertThat(send(first, LlmProvider.OPENAI, physical)).isFalse();
        assertThat(physical.get()).isEqualTo(3);
    }

    @Test void committedDebitSurvivesLostClientAndPersistentBrokerRestart() throws Exception {
        var policy = new ProviderQuotaLedger.Policy(1, 0, 1, 0, 0, 1000, 16); provision(policy);
        var connection = connect(); var first = quotas(connection, policy); var physical = new AtomicInteger();
        assertThat(send(first, LlmProvider.OPENAI, physical)).isTrue();
        connection.close();
        while (!resources.isEmpty()) resources.pop().close(); broker.stop(); startBroker();
        var survivor = quotas(connect(), policy);
        assertThat(send(survivor, LlmProvider.OPENAI, physical)).isFalse();
        assertThat(physical.get()).isOne();
    }

    @Test void lostCommitAcknowledgementAdmitsZeroHttpButTheDebitRemainsCommitted() throws Exception {
        var policy = new ProviderQuotaLedger.Policy(1, 0, 1, 0, 0, 1000, 16); provision(policy);
        var connection = connect();
        var opened = new ArrayList<org.apache.activemq.artemis.api.core.client.ClientSession>();
        var lostAck = new ArtemisProviderRateQuotas(() -> {
            Session real = connection.createSession(true);
            opened.add(((ActiveMQSession)real).getCoreSession());
            Session wrapped = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class[]{Session.class}, (proxy, method, args) -> {
                try { var result = method.invoke(real, args);
                    if (method.getName().equals("commit")) throw new JMSException("fixture lost commit acknowledgement");
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            return new ArtemisProviderRateQuotas.SessionResource(wrapped, ((ActiveMQSession)real).getCoreSession());
        }, settings(policy), json, clock::get);
        var physical = new AtomicInteger();
        assertThatThrownBy(() -> send(lostAck, LlmProvider.OPENAI, physical)).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        assertThat(physical.get()).isZero();
        assertThat(opened).isNotEmpty().allSatisfy(session->assertThat(session.isClosed()).isTrue());
        assertThat(broker.getActiveMQServer().locateQueue(SimpleString.of(QUEUE)).getConsumerCount()).isZero();
        assertThat(send(quotas(connect(), policy), LlmProvider.OPENAI, physical)).isFalse();
        assertThat(physical.get()).isZero();
    }

    @Test void runtimeCannotCreateAMissingQuotaLedger() {
        var policy = new ProviderQuotaLedger.Policy(1, 0, 1, 0, 0, 1000, 16); var physical = new AtomicInteger();
        assertThatThrownBy(() -> send(quotas(connect(), policy), LlmProvider.OPENAI, physical))
                .isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        assertThat(physical.get()).isZero();
        assertThat(broker.getActiveMQServer().locateQueue(SimpleString.of(QUEUE))).isNull();
    }
    @Test void aMissingSafetyFieldCannotResetTheDurableQuotaState() throws Exception {
        var policy = new ProviderQuotaLedger.Policy(1, 0, 1, 0, 0, 1000, 16);
        String body = json.writeValueAsString(ProviderQuotaLedger.empty(policy));
        assertThat(body).contains("\"unsafeTokenBound\":false,");
        provision(policy, body.replace("\"unsafeTokenBound\":false,", ""));
        var physical = new AtomicInteger();
        assertThatThrownBy(() -> send(quotas(connect(), policy), LlmProvider.OPENAI, physical))
                .isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        assertThat(physical.get()).isZero();
    }
    @Test void simultaneousIndependentSessionsNeverCreateAnExtraAdmission() throws Exception {
        var policy = new ProviderQuotaLedger.Policy(2, 0, 3, 0, 0, 1000, 16); provision(policy);
        var first = quotas(connect(), policy); var second = quotas(connect(), policy); var physical = new AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 8; i++) { final int index = i;
                futures.add(executor.submit(() -> { gate.await(); return send(index % 2 == 0 ? first : second,
                        index % 2 == 0 ? LlmProvider.OPENAI : LlmProvider.CUSTOM_OPENAI, physical); })); }
            gate.countDown(); int granted = 0;
            for (var future : futures) if (future.get(10, java.util.concurrent.TimeUnit.SECONDS)) granted++;
            assertThat(granted).isEqualTo(2); assertThat(physical.get()).isEqualTo(2);
        }
    }
    @Test void anotherClientSeesTheLongestCommittedCooldown() throws Exception {
        var policy = new ProviderQuotaLedger.Policy(2, 0, 3, 0, 0, 1000, 16); provision(policy);
        var first = quotas(connect(), policy); var second = quotas(connect(), policy);
        first.defer(LlmProvider.OPENAI.id(), 5000); clock.set(101_000); second.defer(LlmProvider.CUSTOM_OPENAI.id(), 1000);
        clock.set(103_000); var waiting = second.tryReserve(LlmProvider.CUSTOM_OPENAI.id(), UUID.randomUUID().toString(), null, () -> {});
        assertThat(waiting.granted()).isFalse();assertThat(waiting.waitMillis()).isEqualTo(4000);
        clock.set(107_000);assertThat(first.tryReserve(LlmProvider.OPENAI.id(), UUID.randomUUID().toString(), null, () -> {}).granted()).isTrue();
    }
    @Test void uncertainUsageIsRetainedAndKnownUsageReducesExactlyOneCommittedReservation() throws Exception {
        var policy = new ProviderQuotaLedger.Policy(0, 150, 0, 80, 20, 1000, 16);provision(policy);
        var first = quotas(connect(), policy);var second = quotas(connect(), policy);var budget = first.requiredBudget(LlmProvider.OPENAI.id());
        var admitted = first.tryReserve(LlmProvider.OPENAI.id(), UUID.randomUUID().toString(), budget, () -> {});
        admitted.reservation().beforeTransport();
        admitted.reservation().settle(new com.taxonomy.analysis.service.LlmTransportMeter.Usage(null,null,null,null,null,false));
        assertThat(second.tryReserve(LlmProvider.CUSTOM_OPENAI.id(), UUID.randomUUID().toString(), budget, () -> {}).granted()).isFalse();
        admitted.reservation().settle(new com.taxonomy.analysis.service.LlmTransportMeter.Usage(10L,2L,12L,null,null,false));
        assertThat(second.tryReserve(LlmProvider.CUSTOM_OPENAI.id(), UUID.randomUUID().toString(), budget, () -> {}).granted()).isTrue();
        admitted.reservation().settle(new com.taxonomy.analysis.service.LlmTransportMeter.Usage(0L,0L,0L,null,null,false));
        assertThat(first.tryReserve(LlmProvider.OPENAI.id(), UUID.randomUUID().toString(), budget, () -> {}).granted()).isFalse();
    }
    @Test void lateCoherentUsageBeyondBoundDurablyBlocksTheSharedGroup() throws Exception {
        var policy=new ProviderQuotaLedger.Policy(0,100,5,80,20,1000,16);provision(policy);
        var first=quotas(connect(),policy);var independent=quotas(connect(),policy);
        var budget=first.requiredBudget(LlmProvider.OPENAI.id());
        var receipt=first.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),budget,()->{}).reservation();
        receipt.beforeTransport();clock.set(162_001);
        receipt.settle(new com.taxonomy.analysis.service.LlmTransportMeter.Usage(81L,1L,82L,null,null,false));
        assertThat(readState(connect()).unsafeTokenBound()).isTrue();
        assertThatThrownBy(()->independent.tryReserve(LlmProvider.CUSTOM_OPENAI.id(),UUID.randomUUID().toString(),budget,()->{}))
                .isInstanceOf(ProviderRateQuotas.UnavailableException.class);
    }
    @Test void confirmedReceiptStillDisprovesBoundsAfterIndependentPruningWithoutRefundingNewDebit() throws Exception {
        var policy=new ProviderQuotaLedger.Policy(0,100,0,80,20,1000,16);provision(policy);
        var first=quotas(connect(),policy);var connection=connect();var independent=quotas(connection,policy);
        var budget=first.requiredBudget(LlmProvider.OPENAI.id());
        var receipt=first.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),budget,()->{}).reservation();
        receipt.beforeTransport();clock.set(162_001);
        assertThat(independent.tryReserve(LlmProvider.CUSTOM_OPENAI.id(),UUID.randomUUID().toString(),budget,()->{}).granted()).isTrue();
        var pruned=readState(connection);
        assertThat(pruned.debits()).hasSize(1).noneMatch(debit->debit.id().equals(receipt.id()));
        receipt.settle(new com.taxonomy.analysis.service.LlmTransportMeter.Usage(1L,1L,2L,null,null,false));
        assertThat(readState(connection)).as("pruned ID can never refund a new debit").isEqualTo(pruned);
        receipt.settle(new com.taxonomy.analysis.service.LlmTransportMeter.Usage(81L,1L,82L,null,null,false));
        assertThat(readState(connection).unsafeTokenBound()).isTrue();
        assertThatThrownBy(()->independent.tryReserve(LlmProvider.CUSTOM_OPENAI.id(),UUID.randomUUID().toString(),budget,()->{}))
                .isInstanceOf(ProviderRateQuotas.UnavailableException.class);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void prunedUnstartedOrCommitUnconfirmedReceiptCannotManufactureBoundAuthority(boolean loseStartAcknowledgement) throws Exception {
        var policy=new ProviderQuotaLedger.Policy(0,100,0,80,20,1000,16);provision(policy);
        var connection=connect();var commits=new AtomicInteger();
        var first=new ArtemisProviderRateQuotas(()->{
            Session real=connection.createSession(true);var core=((ActiveMQSession)real).getCoreSession();
            Session wrapped=(Session)Proxy.newProxyInstance(Session.class.getClassLoader(),new Class[]{Session.class},(proxy,method,args)->{
                Object value;
                try{value=method.invoke(real,args);}catch(InvocationTargetException failure){throw failure.getCause();}
                if(method.getName().equals("commit")&&commits.incrementAndGet()==2&&loseStartAcknowledgement)
                    throw new JMSException("Fixture lost start commit acknowledgement");
                return value;
            });return new ArtemisProviderRateQuotas.SessionResource(wrapped,core);
        },settings(policy),json,clock::get);
        var budget=first.requiredBudget(LlmProvider.OPENAI.id());
        var receipt=first.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),budget,()->{}).reservation();
        if(loseStartAcknowledgement)assertThatThrownBy(receipt::beforeTransport).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        var unconfirmed=readState(connection);
        receipt.settle(new com.taxonomy.analysis.service.LlmTransportMeter.Usage(81L,1L,82L,null,null,false));
        assertThat(readState(connection)).as("unconfirmed local start has no usage authority, even if DB/broker commit succeeded")
                .isEqualTo(unconfirmed);
        clock.set(162_001);var independent=quotas(connect(),policy);
        assertThat(independent.tryReserve(LlmProvider.CUSTOM_OPENAI.id(),UUID.randomUUID().toString(),budget,()->{}).granted()).isTrue();
        var pruned=readState(connection);
        receipt.settle(new com.taxonomy.analysis.service.LlmTransportMeter.Usage(81L,1L,82L,null,null,false));
        assertThat(readState(connection)).isEqualTo(pruned);
    }
    @ParameterizedTest @ValueSource(strings = {"OPENAI", "GEMINI", "EXAMPLE_CUSTOM"})
    void everyGatewayFamilyCommitsSharedDebitBeforeRealHttp(String selected) throws Exception {
        var policy = new ProviderQuotaLedger.Policy(1, 0, 10, 0, 0, 1000, 16); provision(policy);
        var first = quotas(connect(), policy);var observationConnection = connect();var independent = quotas(observationConnection, policy);
        var debitBeforeHttp = new AtomicBoolean();var physical = new AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            physical.incrementAndGet();
            debitBeforeHttp.set(!independent.tryReserve(new com.taxonomy.extension.api.llm.ProviderId(selected),
                    UUID.randomUUID().toString(),null,()->{}).granted());
            byte[] body = "{\"fixture\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            var registry = registry(url,first,null);
            try (var meter = com.taxonomy.analysis.service.LlmTransportMeter.openJournal(new com.taxonomy.analysis.service.LlmTransportMeter.Journal() {
                public void started(com.taxonomy.analysis.service.LlmTransportMeter.Attempt attempt) {
                    try { assertThat(readState(observationConnection).debits()).extracting(ProviderQuotaLedger.Debit::id).contains(attempt.id()); }
                    catch (JMSException failure) { throw new AssertionError(failure); }
                }
                public void completed(com.taxonomy.analysis.service.LlmTransportMeter.Attempt attempt,
                        com.taxonomy.analysis.service.LlmTransportMeter.Observation observation) {}
            })) {
                assertThat(registry.getGatewayById(new com.taxonomy.extension.api.llm.ProviderId(selected)).sendHttpRequest("fixture prompt","fixture-key"))
                        .contains("fixture");
            }
            assertThat(physical.get()).isOne();assertThat(debitBeforeHttp.get()).isTrue();
        } finally { server.stop(0); }
    }
    @ParameterizedTest @ValueSource(strings = {"OPENAI", "GEMINI", "EXAMPLE_CUSTOM"})
    void exhausted429PublishesCooldownToIndependentWorker(String selected) throws Exception {
        var policy = new ProviderQuotaLedger.Policy(10,0,20,0,0,1000,32);provision(policy);
        var first = quotas(connect(),policy);var independent = quotas(connect(),policy);var physical = new AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            physical.incrementAndGet();exchange.getResponseHeaders().add("Retry-After","10");
            byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var registry = registry("http://127.0.0.1:"+server.getAddress().getPort()+"/",first,
                    (key,fallback)->"llm.retry.max".equals(key)?0:fallback);
            assertThatThrownBy(()->registry.getGatewayById(new com.taxonomy.extension.api.llm.ProviderId(selected))
                    .sendHttpRequest("fixture prompt","fixture-key"))
                    .isInstanceOf(com.taxonomy.analysis.service.LlmRateLimitException.class);
            assertThat(physical.get()).isOne();
            var decision = independent.tryReserve(LlmProvider.CUSTOM_OPENAI.id(),UUID.randomUUID().toString(),null,()->{});
            assertThat(decision.granted()).as("429 cooldown remains shared even after local retry budget is exhausted").isFalse();
            assertThat(decision.waitMillis()).isEqualTo(12_000);
        } finally {server.stop(0);}
    }
    @Test void overflowingRetryAfterBlocksIndependentWorkersDurably() throws Exception {
        var policy = new ProviderQuotaLedger.Policy(10,0,20,0,0,1000,32);provision(policy);
        var first = quotas(connect(),policy);var independent = quotas(connect(),policy);var physical = new AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            physical.incrementAndGet();exchange.getResponseHeaders().add("Retry-After","99999999999999999999999999");
            byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var registry = registry("http://127.0.0.1:"+server.getAddress().getPort()+"/",first,
                    (key,fallback)->"llm.retry.max".equals(key)?0:fallback);
            var failure = org.assertj.core.api.Assertions.catchThrowable(()->registry.getGatewayById(LlmProvider.OPENAI.id())
                    .sendHttpRequest("fixture prompt","fixture-key"));
            assertThat(physical.get()).isOne();
            var decision = independent.tryReserve(LlmProvider.CUSTOM_OPENAI.id(),UUID.randomUUID().toString(),null,()->{});
            assertThat(decision.granted()).isFalse();
            assertThat(decision.waitMillis()).isEqualTo(Long.MAX_VALUE-100_000);
            assertThat(failure).isInstanceOf(com.taxonomy.analysis.service.LlmRateLimitException.class);
        } finally {server.stop(0);}
    }
    @ParameterizedTest @ValueSource(strings = {"OPENAI", "GEMINI", "EXAMPLE_CUSTOM"})
    void unavailableLedgerIsControlledAndNeverStartsGatewayHttp(String selected) throws Exception {
        var policy = new ProviderQuotaLedger.Policy(10,0,20,0,0,1000,32);
        var first=quotas(connect(),policy);var physical=new AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{physical.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});server.start();
        try {
            var registry=registry("http://127.0.0.1:"+server.getAddress().getPort()+"/",first,null);
            assertThatThrownBy(()->registry.getGatewayById(new com.taxonomy.extension.api.llm.ProviderId(selected))
                    .sendHttpRequest("fixture prompt","fixture-key")).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
            assertThat(physical.get()).isZero();
        } finally {server.stop(0);}
    }
    @Test void physicalStartExtendsExistingDebitAndCannotBeConsumedTwice() throws Exception {
        var policy=new ProviderQuotaLedger.Policy(1,0,2,0,0,1000,32);provision(policy);
        var port=quotas(connect(),policy);var independent=quotas(connect(),policy);
        var receipt=port.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),null,()->{}).reservation();
        clock.set(150_000);receipt.beforeTransport();
        assertThatThrownBy(receipt::beforeTransport).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        clock.set(162_000);
        var blocked=independent.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),null,()->{});
        assertThat(blocked.granted()).isFalse();assertThat(blocked.waitMillis()).isEqualTo(50_000);
        clock.set(212_000);
        assertThat(independent.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),null,()->{}).granted()).isTrue();
    }
    @Test void simultaneousPhysicalStartCanConsumeReceiptOnlyOnce() throws Exception {
        var policy=new ProviderQuotaLedger.Policy(2,0,10,0,0,1000,32);provision(policy);
        var port=quotas(connect(),policy);var independent=quotas(connect(),policy);
        var receipt=port.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),null,()->{}).reservation();
        var other=independent.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),null,()->{}).reservation();
        var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        var starts=new AtomicInteger();var failures=new AtomicInteger();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            List<java.util.concurrent.Future<?>> futures=new ArrayList<>();
            for(int i=0;i<2;i++)futures.add(pool.submit(()->{ready.countDown();try{go.await();receipt.beforeTransport();starts.incrementAndGet();}
                catch(ProviderRateQuotas.UnavailableException duplicate){failures.incrementAndGet();}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}));
            assertThat(ready.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();go.countDown();
            for(var future:futures)future.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(starts.get()).isOne();assertThat(failures.get()).isOne();other.beforeTransport();
        assertThat(independent.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),null,()->{}).granted()).isFalse();
    }
    @Test void coherentUsageCannotRefundAnUnstartedReceipt() throws Exception {
        var policy=new ProviderQuotaLedger.Policy(0,150,0,80,20,1000,16);provision(policy);
        var port=quotas(connect(),policy);var independent=quotas(connect(),policy);var budget=port.requiredBudget(LlmProvider.OPENAI.id());
        var receipt=port.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),budget,()->{}).reservation();
        receipt.settle(new com.taxonomy.analysis.service.LlmTransportMeter.Usage(1L,1L,2L,null,null,false));
        assertThat(independent.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),budget,()->{}).granted()).isFalse();
    }
    @Test void aLedgerRemovedReceiptNeverResurrectsAfterFreshAdmission() throws Exception {
        var policy=new ProviderQuotaLedger.Policy(1,0,0,0,0,1000,16);provision(policy);
        var port=quotas(connect(),policy);var observation=connect();var independent=quotas(observation,policy);
        var receipt=port.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),null,()->{}).reservation();
        clock.set(162_000);var fresh=independent.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),null,()->{});
        assertThat(fresh.granted()).isTrue();assertThatThrownBy(receipt::beforeTransport).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        assertThat(readState(observation).debits()).extracting(ProviderQuotaLedger.Debit::id).containsExactly(fresh.reservation().id());
    }
    @Test void cooldownEstablishedAfterAdmissionPreventsPhysicalStart() throws Exception {
        var policy=new ProviderQuotaLedger.Policy(2,0,5,0,0,1000,16);provision(policy);
        var port=quotas(connect(),policy);var observation=connect();var independent=quotas(observation,policy);
        var receipt=port.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),null,()->{}).reservation();
        independent.defer(LlmProvider.OPENAI.id(),10_000);
        assertThatThrownBy(receipt::beforeTransport).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        assertThat(readState(observation).debits()).hasSize(1).allSatisfy(debit->assertThat(debit.started()).isFalse());
    }
    @ParameterizedTest @ValueSource(strings={"OPENAI","GEMINI","EXAMPLE_CUSTOM"})
    void cancellationAfterJournalStartNeverStartsHttp(String selected) throws Exception {
        var policy=new ProviderQuotaLedger.Policy(10,0,20,0,0,1000,32);provision(policy);
        var first=quotas(connect(),policy);var physical=new AtomicInteger();var cancelled=new AtomicBoolean();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{physical.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});server.start();
        var guard=new com.taxonomy.analysis.service.AnalysisMemoryGuard(
                new com.taxonomy.analysis.service.AnalysisMemoryGuard.Policy(80,92,16*1024*1024,5000,120000),
                ()->new com.taxonomy.analysis.service.AnalysisMemoryGuard.Sample(1,1024L*1024*1024),System::currentTimeMillis);
        try(var worker=com.taxonomy.analysis.service.AnalysisRunControl.worker("fixture-operation",cancelled::get,guard);
            var journal=com.taxonomy.analysis.service.LlmTransportMeter.openJournal(new com.taxonomy.analysis.service.LlmTransportMeter.Journal(){
                public void started(com.taxonomy.analysis.service.LlmTransportMeter.Attempt attempt){cancelled.set(true);}
                public void completed(com.taxonomy.analysis.service.LlmTransportMeter.Attempt attempt,
                        com.taxonomy.analysis.service.LlmTransportMeter.Observation observation){}
            })) {
            var registry=registry("http://127.0.0.1:"+server.getAddress().getPort()+"/",first,null);
            assertThatThrownBy(()->registry.getGatewayById(new com.taxonomy.extension.api.llm.ProviderId(selected))
                    .sendHttpRequest("fixture prompt","fixture-key")).isInstanceOf(com.taxonomy.analysis.service.AnalysisStoppedException.class);
            assertThat(physical.get()).isZero();
        }finally{server.stop(0);}
    }
    @ParameterizedTest @ValueSource(strings = {"OPENAI", "GEMINI", "EXAMPLE_CUSTOM"})
    void durableJournalPauseCannotSpendExpiredPhysicalAdmission(String selected) throws Exception {
        var policy=new ProviderQuotaLedger.Policy(10,0,20,0,0,1000,32);provision(policy);
        var first=quotas(connect(),policy);var physical=new AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{physical.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});server.start();
        try (var db=java.sql.DriverManager.getConnection("jdbc:hsqldb:mem:quotaJournal"+UUID.randomUUID().toString().replace("-",""),"SA", "")) {
            db.createStatement().executeUpdate("create table started_attempt (id varchar(36) primary key)");db.setAutoCommit(false);
            var registry=registry("http://127.0.0.1:"+server.getAddress().getPort()+"/",first,null);
            try(var journal=com.taxonomy.analysis.service.LlmTransportMeter.openJournal(new com.taxonomy.analysis.service.LlmTransportMeter.Journal(){
                public void started(com.taxonomy.analysis.service.LlmTransportMeter.Attempt attempt){
                    try(var insert=db.prepareStatement("insert into started_attempt(id) values (?)")){
                        insert.setString(1,attempt.id());insert.executeUpdate();db.commit();
                        // Simulates a worker suspension after durable DB START and before actual transport.
                        clock.set(162_001);
                    }catch(java.sql.SQLException failure){throw new java.lang.IllegalStateException(failure);}
                }
                public void completed(com.taxonomy.analysis.service.LlmTransportMeter.Attempt attempt,
                        com.taxonomy.analysis.service.LlmTransportMeter.Observation observation){}
            })) {
                assertThatThrownBy(()->registry.getGatewayById(new com.taxonomy.extension.api.llm.ProviderId(selected))
                        .sendHttpRequest("fixture prompt","fixture-key")).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
            }
            assertThat(physical.get()).isZero();
            try(var rows=db.createStatement().executeQuery("select count(*) from started_attempt")){
                assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isOne();
            }
        }finally{server.stop(0);}
    }
    com.taxonomy.analysis.service.LlmGatewayRegistry registry(String url,ProviderRateQuotas quotas,
            com.taxonomy.analysis.service.AnalysisRuntimeSettings settings) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        var http = new org.springframework.web.client.RestTemplate((uri,method)->factory.createRequest(java.net.URI.create(url),method));
        var config = new com.taxonomy.analysis.service.LlmProviderConfig(null) {
            @Override public String getGeminiUrl() { return url+"?key="; }
        };
        var extension = new com.taxonomy.extension.api.llm.LlmTransportExtension() {
            public com.taxonomy.extension.api.llm.LlmProviderDescriptor descriptor() {
                return new com.taxonomy.extension.api.llm.LlmProviderDescriptor("EXAMPLE_CUSTOM","Example",false,false,false,false,List.of());
            }
            public com.taxonomy.extension.api.llm.LlmTransport transport() {
                return new com.taxonomy.extension.api.llm.LlmTransport() {
                public String providerName() { return "EXAMPLE_CUSTOM"; }
                public String extractResponseText(String raw) { return raw; }
                public String sendHttpRequest(String prompt,String key) {
                        try { return http.postForObject(url,prompt,String.class); }
                        catch (org.springframework.web.client.HttpClientErrorException failure) {
                            if (failure.getStatusCode().value() == 429) throw new com.taxonomy.extension.api.llm.LlmTransportException(
                                    com.taxonomy.extension.api.llm.LlmTransportException.FailureKind.RATE_LIMIT,
                                    "Fixture provider rate limit",Duration.ofSeconds(10));
                            throw failure;
                        }catch(org.springframework.web.client.HttpServerErrorException unavailable){
                            throw new com.taxonomy.extension.api.llm.LlmTransportException(
                                    com.taxonomy.extension.api.llm.LlmTransportException.FailureKind.UNAVAILABLE,"Fixture provider unavailable");
                        }
                    }
                };
            }
        };
        var registry = new com.taxonomy.analysis.service.LlmGatewayRegistry(config,http,json,settings,null,null,null,List.of(extension));
        registry.configureProviderQuotas(quotas);
        return registry;
    }
    @Test void adminCreatesOneDurableStateAndNeverRefillsAnExistingOrDeliveringLedger() throws Exception {
        var policy=new ProviderQuotaLedger.Policy(2,0,3,0,0,1000,16);
        try(var admin=factory()) {
            ArtemisProviderQuotaProvisioner.provision(admin,PREFIX,GROUP,
                    new ArtemisProviderQuotaPolicy(2,0,3,0,0,null,1000L,16));
            var observer=connect();var port=quotas(observer,policy);
            assertThat(port.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),null,()->{}).granted()).isTrue();
            try(var session=observer.createSession(true);var held=session.createConsumer(session.createQueue(QUEUE+"::"+QUEUE))) {
                assertThat(held.receive(1000)).isNotNull();
                assertThatThrownBy(()->ArtemisProviderQuotaProvisioner.provision(admin,PREFIX,GROUP,
                        new ArtemisProviderQuotaPolicy(2,0,3,0,0,null,1000L,16))).hasMessageContaining("already exists");
                session.rollback();
            }
            assertThat(readState(observer).debits()).hasSize(1);
        }
    }
    @ParameterizedTest @ValueSource(strings={"OPENAI","GEMINI","EXAMPLE_CUSTOM"})
    void unsupportedCompleteWireTpmContractIsRejectedBeforeDebitAndHttp(String selected) throws Exception {
        var policy=new ProviderQuotaLedger.Policy(10,150,20,80,20,1000,32);provision(policy);
        var observer=connect();var port=quotas(observer,policy);var physical=new AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{physical.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});server.start();
        try{
            var registry=registry("http://127.0.0.1:"+server.getAddress().getPort()+"/",port,null);
            assertThatThrownBy(()->registry.getGatewayById(new com.taxonomy.extension.api.llm.ProviderId(selected))
                    .sendHttpRequest("Full requirement/catalogue/system instructions remain in this compiled user prompt","fixture-key"))
                    .isInstanceOf(ProviderRateQuotas.UnavailableException.class);
            assertThat(physical.get()).isZero();assertThat(readState(observer).debits()).isEmpty();
        }finally{server.stop(0);}
    }
    @ParameterizedTest @ValueSource(strings={"OPENAI","GEMINI","EXAMPLE_CUSTOM"})
    void eachRealHttpRetryConsumesItsOwnPermanentDebit(String selected) throws Exception {
        var policy=new ProviderQuotaLedger.Policy(2,0,3,0,0,1000,32);provision(policy);
        var observer=connect();var port=quotas(observer,policy);var physical=new AtomicInteger();
        var ids=new ArrayList<String>();var logical=new HashSet<String>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            int attempt=physical.incrementAndGet();byte[] body="{\"fixture\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(attempt==1?503:200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try(var journal=com.taxonomy.analysis.service.LlmTransportMeter.openJournal(new com.taxonomy.analysis.service.LlmTransportMeter.Journal(){
            public void started(com.taxonomy.analysis.service.LlmTransportMeter.Attempt attempt){ids.add(attempt.id());logical.add(attempt.invocationId());}
            public void completed(com.taxonomy.analysis.service.LlmTransportMeter.Attempt attempt,
                    com.taxonomy.analysis.service.LlmTransportMeter.Observation observation){}
        })){
            var registry=registry("http://127.0.0.1:"+server.getAddress().getPort()+"/",port,
                    (key,fallback)->"llm.retry.max".equals(key)?1:fallback);
            assertThat(registry.getGatewayById(new com.taxonomy.extension.api.llm.ProviderId(selected))
                    .sendHttpRequest("fixture prompt","fixture-key")).contains("fixture");
            assertThat(physical.get()).isEqualTo(2);assertThat(ids).hasSize(2).doesNotHaveDuplicates();assertThat(logical).hasSize(1);
            assertThat(readState(observer).debits()).extracting(ProviderQuotaLedger.Debit::id).containsExactlyElementsOf(ids);
            assertThat(readState(observer).debits()).allSatisfy(debit->assertThat(debit.started()).isTrue());
        }finally{server.stop(0);}
    }
    @Test void committedPhysicalStartWithLostAcknowledgementCannotStartOrRetryHttp() throws Exception {
        var policy=new ProviderQuotaLedger.Policy(1,0,1,0,0,1000,16);provision(policy);var connection=connect();var commits=new AtomicInteger();
        var opened=new ArrayList<org.apache.activemq.artemis.api.core.client.ClientSession>();
        var uncertain=new ArtemisProviderRateQuotas(()->{
            var real=connection.createSession(true);var core=((ActiveMQSession)real).getCoreSession();
            opened.add(core);
            Session wrapped=(Session)Proxy.newProxyInstance(Session.class.getClassLoader(),new Class<?>[]{Session.class},(proxy,method,args)->{
                Object value;
                try{value=method.invoke(real,args);}catch(InvocationTargetException failure){throw failure.getCause();}
                if(method.getName().equals("commit")&&commits.incrementAndGet()==2)throw new JMSException("Fixture lost start commit acknowledgement");
                return value;
            });return new ArtemisProviderRateQuotas.SessionResource(wrapped,core);
        },settings(policy),json,clock::get);
        var receipt=uncertain.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),null,()->{}).reservation();
        var physical=new AtomicInteger();
        assertThatThrownBy(()->{receipt.beforeTransport();physical.incrementAndGet();}).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        assertThat(physical.get()).isZero();
        assertThat(opened).hasSize(2).allSatisfy(session->assertThat(session.isClosed()).isTrue());
        assertThat(broker.getActiveMQServer().locateQueue(SimpleString.of(QUEUE)).getConsumerCount()).isZero();
        assertThat(readState(connection).debits()).hasSize(1).allSatisfy(debit->assertThat(debit.started()).isTrue());
        assertThatThrownBy(receipt::beforeTransport).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        assertThat(quotas(connect(),policy).tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),null,()->{}).granted()).isFalse();
    }
    @Test void quotaWaitReleasesHttpCapacityAndCancellationStopsWithoutHttp() throws Exception {
        var policy=new ProviderQuotaLedger.Policy(1,0,5,0,0,1000,16);provision(policy);
        var port=quotas(connect(),policy);
        assertThat(port.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),null,()->{}).granted()).isTrue();
        String permitPrefix="taxonomy.analysis.provider-permits";
        try(var admin=factory()){ArtemisProviderPermitProvisioner.provision(admin,permitPrefix,GROUP,1);}
        var permitSettings=new ArtemisProviderPermitSettings(permitPrefix,Map.of(LlmProvider.OPENAI.id(),GROUP),5000);
        var permits=new ArtemisProviderConcurrencyPermits(connect(),permitSettings);
        var independent=new ArtemisProviderConcurrencyPermits(connect(),permitSettings);
        var waiting=new java.util.concurrent.CountDownLatch(1);var cancelled=new AtomicBoolean();var physical=new AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{physical.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});server.start();
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var registry=registry("http://127.0.0.1:"+server.getAddress().getPort()+"/",port,null);registry.configureProviderPermits(permits);
            var future=pool.submit(()->{
                var guard=new com.taxonomy.analysis.service.AnalysisMemoryGuard(
                        new com.taxonomy.analysis.service.AnalysisMemoryGuard.Policy(80,92,16*1024*1024,5000,120000),
                        ()->new com.taxonomy.analysis.service.AnalysisMemoryGuard.Sample(1,1024L*1024*1024),System::currentTimeMillis);
                var observer=new com.taxonomy.analysis.service.AnalysisRunControl.Observer(){
                    public void phase(String phase,String node){if("WAITING_RATE_LIMIT".equals(phase))waiting.countDown();}
                    public long started(String provider,String node){return 0;}
                    public void completed(long id,com.taxonomy.dto.LlmCallDetail detail,long duration){}
                    public void failed(long id,String failure,long duration){}
                    public void stopped(com.taxonomy.analysis.service.AnalysisStoppedException.Reason reason){}
                };
                try(var worker=com.taxonomy.analysis.service.AnalysisRunControl.worker(observer,cancelled::get,guard)){
                    registry.getGatewayById(LlmProvider.OPENAI.id()).sendHttpRequest("fixture prompt","fixture-key");return (RuntimeException)null;
                }catch(RuntimeException failure){return failure;}
            });
            try {
                assertThat(waiting.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                try(var free=independent.acquire(LlmProvider.OPENAI.id(),()->{})){assertThat(free).isNotNull();}
            }finally{cancelled.set(true);}
            assertThat(future.get(3,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(com.taxonomy.analysis.service.AnalysisStoppedException.class);
            assertThat(physical.get()).isZero();
        }finally{cancelled.set(true);server.stop(0);}
    }
    @Test void promotedSharedStoreBackupRetainsCommittedDebitCooldownAndUnknownUsage() throws Exception {
        broker.stop();
        var primary=new EmbeddedActiveMQ().setConfiguration(configuration(port).setHAPolicyConfiguration(
                new org.apache.activemq.artemis.core.config.ha.SharedStorePrimaryPolicyConfiguration().setFailoverOnServerShutdown(true)));
        primary.start();broker=primary;
        int backupPort;try(var socket=new ServerSocket(0)){backupPort=socket.getLocalPort();}
        var backup=new EmbeddedActiveMQ().setConfiguration(configuration(backupPort).setHAPolicyConfiguration(
                new org.apache.activemq.artemis.core.config.ha.SharedStoreBackupPolicyConfiguration()
                        .setAllowFailBack(false).setRestartBackup(false)));
        backup.start();
        try {
            assertThat(primary.getActiveMQServer().isActive()).isTrue();assertThat(backup.getActiveMQServer().isActive()).isFalse();
            var policy=new ProviderQuotaLedger.Policy(1,150,2,80,20,1000,16);provision(policy);
            var observer=connect();var portOnPrimary=quotas(observer,policy);var budget=portOnPrimary.requiredBudget(LlmProvider.OPENAI.id());
            var receipt=portOnPrimary.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),budget,()->{}).reservation();
            receipt.beforeTransport();portOnPrimary.defer(LlmProvider.OPENAI.id(),5000);
            // The primary has committed authority; an interrupted consumer holds only its uncommitted update.
            try(var interrupted=observer.createSession(true);var consumer=interrupted.createConsumer(interrupted.createQueue(QUEUE+"::"+QUEUE))){
                assertThat(consumer.receive(1000)).isNotNull();primary.stop();
                long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
                while(!backup.getActiveMQServer().isActive()&&System.nanoTime()<deadline)Thread.sleep(20);
                assertThat(backup.getActiveMQServer().isActive()).isTrue();
                broker=backup;port=backupPort;
                var resumedObserver=connect();var resumed=quotas(resumedObserver,policy);var saved=readState(resumedObserver);
                assertThat(saved.debits()).hasSize(1).allSatisfy(debit->{
                    assertThat(debit.id()).isEqualTo(receipt.id());assertThat(debit.started()).isTrue();
                    assertThat(debit.settled()).isFalse();assertThat(debit.reservedTokens()).isEqualTo(100);
                });assertThat(saved.cooldownUntil()).isEqualTo(107_000);
                assertThat(resumed.tryReserve(LlmProvider.CUSTOM_OPENAI.id(),UUID.randomUUID().toString(),budget,()->{}).granted()).isFalse();
                clock.set(162_000);
                assertThat(resumed.tryReserve(LlmProvider.CUSTOM_OPENAI.id(),UUID.randomUUID().toString(),budget,()->{}).granted()).isTrue();
                assertThat(resumed.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),budget,()->{}).granted()).isFalse();
            }
        }finally {
            if(broker!=backup)backup.stop();
        }
    }
    @Test void duplicatedLedgerIsRejectedEvenWhileOneStateMessageIsDelivering() throws Exception {
        var policy=new ProviderQuotaLedger.Policy(1,0,2,0,0,1000,16);provision(policy);var connection=connect();
        try(var held=connection.createSession(true);var consumer=held.createConsumer(held.createQueue(QUEUE+"::"+QUEUE))){
            assertThat(consumer.receive(1000)).isNotNull();
            try(var sender=connection.createSession(false);var producer=sender.createProducer(sender.createQueue(QUEUE+"::"+QUEUE))){
                var duplicate=sender.createTextMessage(json.writeValueAsString(ProviderQuotaLedger.empty(policy)));
                duplicate.setIntProperty("schemaVersion",1);duplicate.setStringProperty("quotaGroup",GROUP);
                duplicate.setStringProperty("policyFingerprint",policy.fingerprint());duplicate.setStringProperty("tokenAdapter","");
                producer.send(duplicate,DeliveryMode.PERSISTENT,jakarta.jms.Message.DEFAULT_PRIORITY,0);
            }
            var physical=new AtomicInteger();
            assertThatThrownBy(()->send(quotas(connect(),policy),LlmProvider.OPENAI,physical)).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
            assertThat(physical.get()).isZero();held.rollback();
        }
    }
    @Test void administrativeSeedRequiresExclusiveBrokerConsumerAuthority() throws Exception {
        var policy=new ArtemisProviderQuotaPolicy(1,0,2,0,0,null,1000L,16);
        try(var admin=factory()){ArtemisProviderQuotaProvisioner.provision(admin,PREFIX,GROUP,policy);}
        try(var session=connect().createSession(false)){
            assertThat(((ActiveMQSession)session).getCoreSession().queueQuery(SimpleString.of(QUEUE)).isExclusive()).isTrue();
        }
    }
    @Test void nonexclusiveLedgerCannotAdmitEvenWithOnlyOneActualState() throws Exception {
        var policy=new ProviderQuotaLedger.Policy(1,0,2,0,0,1000,16);
        provision(policy,json.writeValueAsString(ProviderQuotaLedger.empty(policy)),false);
        var physical=new AtomicInteger();
        assertThatThrownBy(()->send(quotas(connect(),policy),LlmProvider.OPENAI,physical)).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        assertThat(physical.get()).isZero();
    }
    @Test void actualSingleStateCanAdmitDespiteANonAtomicDoubleCountObservation() throws Exception {
        var policy=new ProviderQuotaLedger.Policy(1,0,2,0,0,1000,16);provision(policy);
        var actual=broker.getActiveMQServer().locateQueue(SimpleString.of(QUEUE));
        assertThat(actual.getMessageCount()).isOne();assertThat(actual.getDeliveringCount()).isZero();
        var physical=new AtomicInteger();
        assertThat(send(quotasWithCountObservation(connect(),policy,2),LlmProvider.OPENAI,physical)).isTrue();
        assertThat(physical.get()).isOne();
        assertThat(readState(connect()).debits()).hasSize(1);
    }
    @Test void actualDuplicateCannotAdmitDespiteANonAtomicSingleCountObservation() throws Exception {
        var policy=new ProviderQuotaLedger.Policy(1,0,2,0,0,1000,16);provision(policy);
        try(var session=connect().createSession(false);var producer=session.createProducer(session.createQueue(QUEUE+"::"+QUEUE))){
            var duplicate=session.createTextMessage(json.writeValueAsString(ProviderQuotaLedger.empty(policy)));
            duplicate.setIntProperty("schemaVersion",1);duplicate.setStringProperty("quotaGroup",GROUP);
            duplicate.setStringProperty("policyFingerprint",policy.fingerprint());duplicate.setStringProperty("tokenAdapter","");
            producer.send(duplicate,DeliveryMode.PERSISTENT,jakarta.jms.Message.DEFAULT_PRIORITY,0);
        }
        var actual=broker.getActiveMQServer().locateQueue(SimpleString.of(QUEUE));
        assertThat(actual.getMessageCount()).isEqualTo(2);assertThat(actual.getDeliveringCount()).isZero();
        var physical=new AtomicInteger();
        assertThatThrownBy(()->send(quotasWithCountObservation(connect(),policy,1),LlmProvider.OPENAI,physical))
                .isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        assertThat(physical.get()).isZero();assertThat(actual.getMessagesAcknowledged()).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void lostForcedDeliveryMarkerCancelsProofAndTerminatesItsRealReceiver(boolean cancelled) throws Exception {
        var policy=new ProviderQuotaLedger.Policy(1,0,2,0,0,1000,16);provision(policy);
        var marker=new java.util.concurrent.CountDownLatch(1);
        org.apache.activemq.artemis.api.core.Interceptor interceptor=(packet,connection)->{
            if(packet instanceof org.apache.activemq.artemis.core.protocol.core.impl.wireformat.SessionForceConsumerDelivery){
                marker.countDown();return false; // Real native force-delivery request is lost, not a mocked receive.
            }return true;
        };
        broker.getActiveMQServer().getRemotingService().addIncomingInterceptor(interceptor);
        var receiver=new AtomicReference<Thread>();var finished=new AtomicBoolean();var closed=new AtomicBoolean();
        var stop=new AtomicBoolean();var connection=connect();var physical=new AtomicInteger();
        var configured=settings(policy);
        var shortWait=new ArtemisProviderQuotaSettings(configured.destinationPrefix(),configured.providerGroups(),configured.policies(),
                configured.tokenBudgets(),cancelled?5000:250);
        var port=new ArtemisProviderRateQuotas(()->{
            Session real=connection.createSession(true);var core=((ActiveMQSession)real).getCoreSession();
            Session wrapped=(Session)Proxy.newProxyInstance(Session.class.getClassLoader(),new Class[]{Session.class},(proxy,method,args)->{
                Object value;try{value=method.invoke(real,args);}catch(InvocationTargetException failure){throw failure.getCause();}
                if(method.getName().equals("close"))closed.set(true);
                if(method.getName().equals("createConsumer")&&value instanceof MessageConsumer consumer){
                    return Proxy.newProxyInstance(MessageConsumer.class.getClassLoader(),new Class[]{MessageConsumer.class},(p,m,a)->{
                        if(m.getName().equals("receiveNoWait"))receiver.set(Thread.currentThread());
                        try{return m.invoke(consumer,a);}catch(InvocationTargetException failure){throw failure.getCause();}
                        finally{if(m.getName().equals("receiveNoWait"))finished.set(true);}
                    });
                }return value;
            });return new ArtemisProviderRateQuotas.SessionResource(wrapped,core);
        },shortWait,json,clock::get);
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var result=pool.submit(()->org.assertj.core.api.Assertions.catchThrowable(()->{
                var decision=port.tryReserve(LlmProvider.OPENAI.id(),UUID.randomUUID().toString(),null,()->{
                    if(stop.get())throw new com.taxonomy.analysis.service.AnalysisStoppedException(
                            com.taxonomy.analysis.service.AnalysisStoppedException.Reason.CANCELLED);
                });if(decision.granted())physical.incrementAndGet();
            }));
            assertThat(marker.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            if(cancelled)stop.set(true);
            assertThat(result.get(4,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(cancelled?
                    com.taxonomy.analysis.service.AnalysisStoppedException.class:ProviderRateQuotas.UnavailableException.class);
            assertThat(physical.get()).isZero();assertThat(closed.get()).isTrue();
            assertThat(receiver.get()).isNotNull();receiver.get().join(1000);
            assertThat(receiver.get().isAlive()).isFalse();assertThat(finished.get()).isTrue();
            assertThat(readState(connect()).debits()).isEmpty();
        }finally{stop.set(true);}
    }
    ArtemisProviderRateQuotas quotasWithCountObservation(ArtemisAnalysisConnection connection,ProviderQuotaLedger.Policy policy,long observedCount) {
        return new ArtemisProviderRateQuotas(()->{
            var session=connection.createSession(true);var actual=((ActiveMQSession)session).getCoreSession();
            var core=(org.apache.activemq.artemis.api.core.client.ClientSession)Proxy.newProxyInstance(
                    org.apache.activemq.artemis.api.core.client.ClientSession.class.getClassLoader(),
                    new Class[]{org.apache.activemq.artemis.api.core.client.ClientSession.class},(proxy,method,args)->{
                        Object value;try{value=method.invoke(actual,args);}catch(InvocationTargetException failure){throw failure.getCause();}
                        if(method.getName().equals("queueQuery")&&value instanceof org.apache.activemq.artemis.api.core.client.ClientSession.QueueQuery query){
                            return Proxy.newProxyInstance(query.getClass().getClassLoader(),
                                    new Class[]{org.apache.activemq.artemis.api.core.client.ClientSession.QueueQuery.class},(p,m,a)->{
                                        if(m.getName().equals("getMessageCount"))return observedCount;
                                        try{return m.invoke(query,a);}catch(InvocationTargetException failure){throw failure.getCause();}
                                    });
                        }return value;
                    });return new ArtemisProviderRateQuotas.SessionResource(session,core);
        },settings(policy),json,clock::get);
    }
    boolean send(ProviderRateQuotas quotas, LlmProvider provider, AtomicInteger physical) {
        var decision = quotas.tryReserve(provider.id(), UUID.randomUUID().toString(), null, () -> {});
        if (decision.granted()) physical.incrementAndGet(); return decision.granted();
    }
    ProviderQuotaLedger.State readState(ArtemisAnalysisConnection connection) throws JMSException {
        try (var session = connection.createSession(false); var browser = session.createBrowser(session.createQueue(QUEUE+"::"+QUEUE))) {
            var messages = browser.getEnumeration(); assertThat(messages.hasMoreElements()).isTrue();
            var state = (TextMessage)messages.nextElement();return json.readValue(state.getText(),ProviderQuotaLedger.State.class);
        }
    }
    ArtemisProviderRateQuotas quotas(ArtemisAnalysisConnection connection, ProviderQuotaLedger.Policy policy) {
        return new ArtemisProviderRateQuotas(connection, settings(policy), json, clock::get);
    }
    ArtemisProviderQuotaSettings settings(ProviderQuotaLedger.Policy policy) {
        return new ArtemisProviderQuotaSettings(PREFIX, Map.of(LlmProvider.OPENAI.id(), GROUP, LlmProvider.CUSTOM_OPENAI.id(), GROUP,
                LlmProvider.GEMINI.id(),GROUP,new com.taxonomy.extension.api.llm.ProviderId("EXAMPLE_CUSTOM"),GROUP),
                Map.of(GROUP, policy), policy.tpm() == 0 ? Map.of() : Map.of(GROUP,
                new ProviderRateQuotas.TokenBudget("fixture-text-budget", policy.inputUpperBound(), policy.outputCap())), 5000);
    }
    ActiveMQConnectionFactory factory() { var f = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + port);
        f.setInitialConnectAttempts(0);f.setReconnectAttempts(0);f.setCallTimeout(3000);return f; }
    ArtemisAnalysisConnection connect() {
        var connection = new ArtemisAnalysisConnection(factory());resources.push(connection);connection.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!connection.connected() && System.nanoTime() < deadline) { try { Thread.sleep(10); } catch (InterruptedException e) { throw new AssertionError(e); } }
        assertThat(connection.connected()).isTrue(); return connection;
    }
    void provision(ProviderQuotaLedger.Policy policy) throws Exception {
        provision(policy, json.writeValueAsString(ProviderQuotaLedger.empty(policy)));
    }
    void provision(ProviderQuotaLedger.Policy policy, String body) throws Exception {
        provision(policy,body,true);
    }
    void provision(ProviderQuotaLedger.Policy policy,String body,boolean exclusive) throws Exception {
        try (var factory = factory(); var connection = factory.createConnection();var session = connection.createSession(true, Session.SESSION_TRANSACTED)) {
            ((ActiveMQSession)session).getCoreSession().createQueue(QueueConfiguration.of(QUEUE).setAddress(QUEUE)
                    .setRoutingType(RoutingType.ANYCAST).setDurable(true).setExclusive(exclusive).setAutoDelete(false).setAutoCreated(false));
            try (var producer = session.createProducer(session.createQueue(QUEUE + "::" + QUEUE))) {
                var message = session.createTextMessage(body);
                message.setIntProperty("schemaVersion", 1);message.setStringProperty("quotaGroup", GROUP);
                message.setStringProperty("policyFingerprint", policy.fingerprint());
                if (policy.tpm() > 0) message.setStringProperty("tokenAdapter", "fixture-text-budget");
                producer.send(message, DeliveryMode.PERSISTENT, jakarta.jms.Message.DEFAULT_PRIORITY, 0); session.commit();
            }
        }
    }
}
