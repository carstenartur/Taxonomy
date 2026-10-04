package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.dag.AnalysisTaskCompletionStore;
import com.taxonomy.analysis.dispatch.AnalysisDispatchService;
import com.taxonomy.analysis.dispatch.AnalysisDispatchStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ArtemisAnalysisTransportConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ArtemisAnalysisTransportConfiguration.class)
            .withBean(AnalysisDispatchStore.class, () -> mock(AnalysisDispatchStore.class))
            .withBean(AnalysisTaskCompletionStore.class, () -> mock(AnalysisTaskCompletionStore.class));

    @Test
    void localModeCreatesNoBrokerClient() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ArtemisAnalysisConnection.class);
            assertThat(context).doesNotHaveBean(AnalysisDispatchService.class);
        });
        runner.withPropertyValues("taxonomy.analysis.transport.mode=local")
                .run(context -> assertThat(context).doesNotHaveBean(ArtemisAnalysisConnection.class));
    }

    @Test
    void artemisModeFailsClosedWithoutTlsOrBrokerUrl() {
        runner.withPropertyValues("taxonomy.analysis.transport.mode=artemis")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("taxonomy.analysis.transport.mode=artemis",
                        "taxonomy.analysis.artemis.broker-url=tcp://127.0.0.1:61616")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("sslEnabled=true"));
    }

    @Test
    void unreachableBrokerDoesNotBlockStartupAndReportsDown() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        runner.withPropertyValues("taxonomy.analysis.transport.mode=artemis",
                        "taxonomy.analysis.artemis.broker-url=tcp://127.0.0.1:" + port,
                        "taxonomy.analysis.artemis.require-tls=false",
                        "taxonomy.analysis.worker.shards=CP")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AnalysisDispatchService.class);
                    var health = context.getBean(ArtemisAnalysisBrokerHealthIndicator.class).health();
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails().toString()).doesNotContain("127.0.0.1");
                    assertThat(context.getBean(ArtemisAnalysisWorker.class).consumerCount()).isZero();
                });
    }

    @Test
    void unknownTransportModeFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(AnalysisTransportModeValidator.class)
                .withPropertyValues("taxonomy.analysis.transport.mode=kafka")
                .run(context -> assertThat(context).hasFailed());
    }
}
