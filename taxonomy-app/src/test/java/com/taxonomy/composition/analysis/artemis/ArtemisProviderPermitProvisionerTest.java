package com.taxonomy.composition.analysis.artemis;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real administrative main with an isolated environment and TCP broker. */
@Timeout(60)
class ArtemisProviderPermitProvisionerTest {
    private static final String TLS_ENV = "TAXONOMY_ANALYSIS_ARTEMIS_REQUIRE_TLS";
    private static final String PREFIX = "taxonomy.analysis.provider-permits";
    private static final String GROUP = "cli-test";
    private static final String USER_CANARY = "fixture-broker-user";
    private static final String PASSWORD_CANARY = "fixture-broker-password";
    private static final String INVALID_CANARY = "fixture-invalid-tls-secret";

    @TempDir Path data;
    private EmbeddedActiveMQ broker;
    private int port;

    @BeforeEach
    void startBroker() throws Exception {
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var configuration = new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
                .setThreadPoolMaxSize(2).setScheduledThreadPoolMaxSize(1)
                .setJournalDirectory(data.resolve("journal").toString())
                .setBindingsDirectory(data.resolve("bindings").toString())
                .setLargeMessagesDirectory(data.resolve("large").toString())
                .setPagingDirectory(data.resolve("paging").toString());
        configuration.addAcceptorConfiguration("tcp", "tcp://127.0.0.1:" + port);
        broker = new EmbeddedActiveMQ().setConfiguration(configuration);
        broker.start();
    }

    @AfterEach
    void stopBroker() throws Exception {
        if (broker != null) broker.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "treu", "true ", " false", "yes", INVALID_CANARY})
    void invalidTlsSettingFailsBeforeAnyBrokerConnection(String requireTls) throws Exception {
        var result = runCli(requireTls);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains(TLS_ENV)
                .doesNotContain(INVALID_CANARY, USER_CANARY, PASSWORD_CANARY);
        assertNoBrokerConnectionOrQueue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"true", "TRUE"})
    void missingOrExplicitTrueStillRejectsPlaintextBeforeConnecting(String requireTls) throws Exception {
        var result = runCli(requireTls);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("sslEnabled=true")
                .doesNotContain(USER_CANARY, PASSWORD_CANARY);
        assertNoBrokerConnectionOrQueue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE"})
    void explicitFalseStillProvisionsTheRequestedCapacity(String requireTls) throws Exception {
        var result = runCli(requireTls);

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(broker.getActiveMQServer().getTotalConnectionCount()).isPositive();
        var queue = broker.getActiveMQServer().locateQueue(SimpleString.of(PREFIX + "." + GROUP));
        assertThat(queue).isNotNull();
        assertThat(queue.getMessageCount()).isEqualTo(2);
    }

    private void assertNoBrokerConnectionOrQueue() {
        assertThat(broker.getActiveMQServer().getTotalConnectionCount()).isZero();
        assertThat(broker.getActiveMQServer().locateQueue(SimpleString.of(PREFIX + "." + GROUP))).isNull();
    }

    private CliResult runCli(String requireTls) throws Exception {
        var log = data.resolve("cli.log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-XX:ActiveProcessorCount=2", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ArtemisProviderPermitProvisioner.class.getName(), GROUP, "2")
                .redirectErrorStream(true).redirectOutput(log.toFile());
        var environment = builder.environment();
        environment.keySet().removeIf(name -> name.startsWith("TAXONOMY_ANALYSIS_")
                || name.equals("JDK_JAVA_OPTIONS") || name.equals("JAVA_TOOL_OPTIONS") || name.equals("_JAVA_OPTIONS"));
        environment.put("TAXONOMY_ANALYSIS_ARTEMIS_BROKER_URL", "tcp://127.0.0.1:" + port);
        environment.put("TAXONOMY_ANALYSIS_ARTEMIS_USER", USER_CANARY);
        environment.put("TAXONOMY_ANALYSIS_ARTEMIS_PASSWORD", PASSWORD_CANARY);
        environment.put("TAXONOMY_ANALYSIS_PROVIDER_PERMITS_DESTINATION_PREFIX", PREFIX);
        if (requireTls != null) environment.put(TLS_ENV, requireTls);
        var process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("provisioner exits promptly").isTrue();
            return new CliResult(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private record CliResult(int exitCode, String output) {}
}
