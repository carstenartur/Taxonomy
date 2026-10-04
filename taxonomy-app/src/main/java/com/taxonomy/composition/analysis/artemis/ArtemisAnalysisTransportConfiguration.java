package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.dag.AnalysisTaskCompletionStore;
import com.taxonomy.analysis.dag.AnalysisTaskHandlers;
import com.taxonomy.analysis.dag.AnalysisWorkerShards;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.dispatch.AnalysisDispatchRecoveryTrigger;
import com.taxonomy.analysis.dispatch.AnalysisDispatchService;
import com.taxonomy.analysis.dispatch.AnalysisDispatchStore;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opt-in clustered analysis transport ({@code taxonomy.analysis.transport.mode=artemis}).
 *
 * <p>Without this mode nothing here is created and analysis stays in-process.
 * The broker is external; Taxonomy never embeds one in production. Dispatch
 * recovery is triggered by the first broker connection (startup), by a broker
 * reconnect and by the explicit admin repair — never by a timer.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taxonomy.analysis.transport.mode", havingValue = ArtemisAnalysisSettings.MODE_ARTEMIS)
public class ArtemisAnalysisTransportConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ArtemisAnalysisTransportConfiguration.class);

    @Bean
    ArtemisAnalysisSettings artemisAnalysisSettings(
            @Value("${taxonomy.analysis.artemis.broker-url:}") String brokerUrl,
            @Value("${taxonomy.analysis.artemis.require-tls:true}") boolean requireTls,
            @Value("${taxonomy.analysis.artemis.retry-interval-ms:1000}") long retryIntervalMs,
            @Value("${taxonomy.analysis.artemis.call-timeout-ms:30000}") long callTimeoutMs) {
        return new ArtemisAnalysisSettings(brokerUrl, requireTls, retryIntervalMs, callTimeoutMs);
    }

    @Bean(destroyMethod = "close")
    ArtemisAnalysisConnection artemisAnalysisConnection(
            ArtemisAnalysisSettings settings,
            @Value("${taxonomy.analysis.artemis.user:}") String user,
            @Value("${taxonomy.analysis.artemis.password:}") String password) {
        return new ArtemisAnalysisConnection(connectionFactory(settings, user, password));
    }

    static ActiveMQConnectionFactory connectionFactory(ArtemisAnalysisSettings settings, String user, String password) {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(settings.brokerUrl());
        if (user != null && !user.isBlank()) {
            factory.setUser(user);
            factory.setPassword(password);
        }
        factory.setInitialConnectAttempts(-1);
        factory.setReconnectAttempts(-1);
        factory.setRetryInterval(settings.retryIntervalMs());
        factory.setRetryIntervalMultiplier(2.0);
        factory.setMaxRetryInterval(Math.max(settings.retryIntervalMs(), 30_000L));
        factory.setCallTimeout(settings.callTimeoutMs());
        factory.setCallFailoverTimeout(settings.callTimeoutMs());
        // No client-side prefetch: an idle worker gets the next task instead of a busy one hoarding it.
        factory.setConsumerWindowSize(0);
        return factory;
    }

    @Bean
    AnalysisDestinations analysisDestinations(
            @Value("${taxonomy.analysis.artemis.destination-prefix:" + AnalysisDestinations.DEFAULT_PREFIX + "}")
            String prefix) {
        return new AnalysisDestinations(prefix);
    }

    @Bean
    AnalysisMessageCodec analysisMessageCodec() {
        return new AnalysisMessageCodec();
    }

    @Bean(destroyMethod = "close")
    ArtemisAnalysisTaskPublisher artemisAnalysisTaskPublisher(ArtemisAnalysisConnection connection,
                                                              AnalysisDestinations destinations,
                                                              AnalysisMessageCodec codec) {
        return new ArtemisAnalysisTaskPublisher(connection, destinations, codec);
    }

    @Bean
    AnalysisDispatchService analysisDispatchService(
            AnalysisDispatchStore store, ArtemisAnalysisTaskPublisher publisher,
            ObjectProvider<ArtemisAnalysisMetrics> instrumentation,
            @Value("${taxonomy.analysis.dispatch.recovery-batch:200}") int recoveryBatch,
            @Value("${taxonomy.analysis.dispatch.recovery-limit:5000}") int recoveryLimit) {
        var metrics = instrumentation.getIfAvailable();
        return new AnalysisDispatchService(store, task -> {
            if (metrics == null) publisher.publish(task);
            else metrics.dispatched(task, () -> publisher.publish(task));
        }, recoveryBatch, recoveryLimit);
    }

    @Bean(destroyMethod = "close")
    ArtemisAnalysisWorker artemisAnalysisWorker(
            ArtemisAnalysisConnection connection, AnalysisDestinations destinations,
            AnalysisTaskCompletionStore completions, AnalysisMessageCodec codec,
            ObjectProvider<AnalysisTaskHandlers> handlers,
            @Value("${taxonomy.analysis.worker.shards:}") String shards,
            @Value("${taxonomy.analysis.worker.consumers-per-shard:1}") int consumersPerShard) {
        return new ArtemisAnalysisWorker(connection, destinations, AnalysisWorkerShards.parse(shards),
                consumersPerShard, handlers.getIfAvailable(() -> AnalysisTaskHandlers.NONE), completions, codec);
    }

    @Bean
    ArtemisAnalysisBrokerHealthIndicator analysisBrokerHealthIndicator(ArtemisAnalysisConnection connection,
                                                                      AnalysisDispatchStore store) {
        return new ArtemisAnalysisBrokerHealthIndicator(connection, store);
    }

    /** Wires connection events to worker subscription and event-driven dispatch recovery. */
    @Lazy(false) // Background transport must start even when application beans are lazy by default.
    @Bean(initMethod = "start", destroyMethod = "close")
    ArtemisAnalysisLifecycle artemisAnalysisLifecycle(
            ArtemisAnalysisConnection connection, ArtemisAnalysisWorker worker, AnalysisDispatchService dispatch,
            @Value("${taxonomy.analysis.worker.enabled:true}") boolean workerEnabled,
            @Value("${taxonomy.analysis.runtime-role:all}") String role) {
        return new ArtemisAnalysisLifecycle(connection, worker, dispatch, workerEnabled, RuntimeRole.parse(role));
    }

    enum RuntimeRole {
        ALL, COORDINATOR, WORKER;
        static RuntimeRole parse(String role) { return valueOf(role.strip().toUpperCase(java.util.Locale.ROOT)); }
    }

    /** Lifecycle owner; recovery runs on one non-scheduled thread, coalesced by the dispatch service. */
    static final class ArtemisAnalysisLifecycle implements AutoCloseable {

        private final ArtemisAnalysisConnection connection;
        private final ArtemisAnalysisWorker worker;
        private final AnalysisDispatchService dispatch;
        private final boolean workerEnabled;
        private final RuntimeRole role;
        private final ExecutorService recovery = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("taxonomy-analysis-dispatch-recovery").factory());

        ArtemisAnalysisLifecycle(ArtemisAnalysisConnection connection, ArtemisAnalysisWorker worker,
                                 AnalysisDispatchService dispatch, boolean workerEnabled, RuntimeRole role) {
            this.connection = connection;
            this.worker = worker;
            this.dispatch = dispatch;
            this.workerEnabled = workerEnabled;
            this.role = role;
        }

        void start() {
            connection.addListener(reconnect -> {
                if (workerEnabled || role != RuntimeRole.WORKER) {
                    try {
                        worker.start(workerEnabled && role != RuntimeRole.COORDINATOR, role != RuntimeRole.WORKER);
                    } catch (RuntimeException failure) {
                        log.warn("Analysis worker subscription failed ({})", failure.getClass().getSimpleName());
                    }
                }
                AnalysisDispatchRecoveryTrigger trigger = reconnect
                        ? AnalysisDispatchRecoveryTrigger.BROKER_RECONNECT : AnalysisDispatchRecoveryTrigger.STARTUP;
                recovery.execute(() -> {
                    try {
                        dispatch.recover(trigger);
                    } catch (RuntimeException failure) {
                        log.warn("Analysis dispatch recovery ({}) failed ({})", trigger,
                                failure.getClass().getSimpleName());
                    }
                });
            });
            connection.start();
        }

        @Override
        public void close() {
            recovery.shutdownNow();
        }
    }
}
