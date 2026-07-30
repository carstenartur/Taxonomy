package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproducible relative performance measurement for the optional OpenTelemetry
 * Java agent. This is deliberately a dedicated opt-in integration test rather
 * than part of every developer build. Run it through
 * {@code .github/scripts/run-observability-performance.sh}.
 */
@Tag("observability-performance")
@EnabledIfSystemProperty(
        named = "taxonomy.observability.performance.enabled",
        matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObservabilityPerformanceIT {

    private static final String AGENT_IMAGE =
            "otel/autoinstrumentation-java:2.28.1@sha256:"
                    + "41b92978e61d13d4f32c6eb20c6ae7821a73ffdec8539bc6a73858e884b411d8";
    private static final String RUNTIME_IMAGE =
            "eclipse-temurin:21-jre-jammy@sha256:"
                    + "d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13";
    private static final String COLLECTOR_IMAGE =
            "otel/opentelemetry-collector-contrib@sha256:"
                    + "f2f01157055a9b2aab9df7118e1f1c9abf345e99b23bc7a2bc791db374a7d0f6";
    private static final String COLLECTOR_ALIAS = "otel-performance-collector.test";
    private static final int WARMUP_REQUESTS = Integer.getInteger(
            "taxonomy.observability.performance.warmup-requests", 12);
    private static final int MEASURED_REQUESTS = Integer.getInteger(
            "taxonomy.observability.performance.measured-requests", 80);
    private static final double INVESTIGATION_THRESHOLD_PERCENT = 10.0;
    private static final double HARD_P95_PERCENT = Double.parseDouble(System.getProperty(
            "taxonomy.observability.performance.hard-p95-percent", "100"));
    private static final long HARD_P95_ABSOLUTE_MILLIS = Long.getLong(
            "taxonomy.observability.performance.hard-p95-absolute-millis", 30L);
    private static final long HARD_STARTUP_ABSOLUTE_MILLIS = Long.getLong(
            "taxonomy.observability.performance.hard-startup-absolute-millis", 30_000L);
    private static final long HARD_MEMORY_DELTA_MIB = Long.getLong(
            "taxonomy.observability.performance.hard-memory-delta-mib", 256L);
    private static final Pattern EXPORTED_SPAN = Pattern.compile("Trace ID\\s*:");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final String BASIC_AUTH = "Basic "
            + Base64.getEncoder().encodeToString(
                    ("admin:" + ContainerTestUtils.TEST_ADMIN_PASSWORD)
                            .getBytes(StandardCharsets.UTF_8));

    private Network network;
    private GenericContainer<?> collector;
    private Future<String> applicationImage;

    @BeforeAll
    void startCollectorAndBuildApplicationImage() {
        network = Network.newNetwork();
        collector = new GenericContainer<>(DockerImageName.parse(COLLECTOR_IMAGE))
                .withNetwork(network)
                .withNetworkAliases(COLLECTOR_ALIAS)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(
                                "observability/otel-collector-test-config.yml"),
                        "/etc/otelcol-contrib/config.yaml")
                .withCommand("--config=/etc/otelcol-contrib/config.yaml")
                .waitingFor(Wait.forLogMessage(".*Everything is ready.*\\n", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        collector.start();
        applicationImage = performanceApplicationImage();
    }

    @AfterAll
    void stopInfrastructure() throws Exception {
        ContainerTestUtils.closeAll(collector, network);
    }

    @Test
    void measuresAgentAndSamplingOverheadAndWritesEvidence() throws Exception {
        ModeResult baseline = measure(new Mode("baseline", false, "none", null));
        ModeResult alwaysOn = measure(new Mode("agent-always-on", true, "always_on", null));
        ModeResult sampled = measure(new Mode(
                "agent-sampled-10-percent", true, "parentbased_traceidratio", "0.10"));

        assertThat(baseline.exportedSpans()).as("baseline exported spans").isZero();
        assertThat(alwaysOn.exportedSpans()).as("always-on exported spans").isPositive();
        assertThat(sampled.exportedSpans())
                .as("sampling must reduce exported span volume")
                .isLessThan(alwaysOn.exportedSpans());

        double p95Overhead = overheadPercent(baseline.p95Millis(), alwaysOn.p95Millis());
        double sampledP95Overhead = overheadPercent(baseline.p95Millis(), sampled.p95Millis());
        double startupOverhead = overheadPercent(
                baseline.startupMillis(), alwaysOn.startupMillis());
        long memoryDeltaMiB = Math.round(
                (alwaysOn.memoryPeakBytes() - baseline.memoryPeakBytes())
                        / (1024.0 * 1024.0));

        boolean p95HardLimit = p95Overhead > HARD_P95_PERCENT
                && alwaysOn.p95Millis() - baseline.p95Millis()
                > HARD_P95_ABSOLUTE_MILLIS;
        boolean startupHardLimit = startupOverhead > 100.0
                && alwaysOn.startupMillis() - baseline.startupMillis()
                > HARD_STARTUP_ABSOLUTE_MILLIS;
        boolean memoryHardLimit = memoryDeltaMiB > HARD_MEMORY_DELTA_MIB;
        BudgetEvaluation budget = new BudgetEvaluation(
                INVESTIGATION_THRESHOLD_PERCENT,
                p95Overhead,
                sampledP95Overhead,
                startupOverhead,
                memoryDeltaMiB,
                p95Overhead > INVESTIGATION_THRESHOLD_PERCENT,
                p95HardLimit,
                startupHardLimit,
                memoryHardLimit,
                p95HardLimit || startupHardLimit || memoryHardLimit);

        PerformanceReport report = new PerformanceReport(
                1,
                Instant.now().toString(),
                System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors(),
                WARMUP_REQUESTS,
                MEASURED_REQUESTS,
                "/api/relations",
                List.of(baseline, alwaysOn, sampled),
                budget);
        Path reportDirectory = writeReport(report);

        if (Boolean.getBoolean("taxonomy.observability.performance.enforce")) {
            assertThat(budget.hardBudgetExceeded())
                    .as("OpenTelemetry hard performance budget; inspect %s",
                            reportDirectory.resolve("report.md"))
                    .isFalse();
        }
    }

    private ModeResult measure(Mode mode) throws Exception {
        GenericContainer<?> application = applicationContainer(mode);
        long startupStarted = System.nanoTime();
        application.start();
        long startupMillis = elapsedMillis(startupStarted);

        try {
            for (int i = 0; i < WARMUP_REQUESTS; i++) {
                assertSuccessful(applicationGet(application, "/api/relations"));
            }
            awaitCollectorQuiet();
            int collectorOffset = collector.getLogs().length();
            long cpuBeforeMicros = readCpuMicros(application);

            List<Long> latencyNanos = new ArrayList<>(MEASURED_REQUESTS);
            for (int i = 0; i < MEASURED_REQUESTS; i++) {
                long started = System.nanoTime();
                HttpResponse<String> response = applicationGet(application, "/api/relations");
                latencyNanos.add(System.nanoTime() - started);
                assertSuccessful(response);
            }

            long cpuAfterMicros = readCpuMicros(application);
            awaitCollectorQuiet();
            String collectorLogs = collector.getLogs();
            String newCollectorLogs = collectorLogs.length() >= collectorOffset
                    ? collectorLogs.substring(collectorOffset)
                    : collectorLogs;
            long memoryPeakBytes = readMemoryPeakBytes(application);
            latencyNanos.sort(Comparator.naturalOrder());

            long p50Millis = percentileMillis(latencyNanos, 50);
            long p95Millis = percentileMillis(latencyNanos, 95);
            long cpuMicros = Math.max(0, cpuAfterMicros - cpuBeforeMicros);
            int spans = countSpans(newCollectorLogs);
            return new ModeResult(
                    mode.name(),
                    mode.agentAttached(),
                    mode.sampler(),
                    mode.samplerArgument(),
                    startupMillis,
                    memoryPeakBytes,
                    cpuMicros,
                    cpuMicros / (double) MEASURED_REQUESTS,
                    p50Millis,
                    p95Millis,
                    spans,
                    spans / (double) MEASURED_REQUESTS);
        } finally {
            application.stop();
        }
    }

    private GenericContainer<?> applicationContainer(Mode mode) {
        GenericContainer<?> application = new GenericContainer<>(applicationImage)
                .withNetwork(network)
                .withExposedPorts(8080)
                .withEnv("SPRING_PROFILES_ACTIVE", "hsqldb,observability")
                .withEnv("TAXONOMY_ADMIN_PASSWORD", ContainerTestUtils.TEST_ADMIN_PASSWORD)
                .withEnv("TAXONOMY_REQUIRE_PASSWORD_CHANGE", "false")
                .withEnv("TAXONOMY_EMBEDDING_ENABLED", "false")
                .withEnv("TAXONOMY_THYMELEAF_CACHE", "true")
                .withEnv("LLM_MOCK", "true")
                .waitingFor(Wait.forHttp("/actuator/health")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        if (!mode.agentAttached()) {
            return application;
        }
        application
                .withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/tmp/opentelemetry-javaagent.jar")
                .withEnv("OTEL_JAVAAGENT_CONFIGURATION_FILE", "/tmp/javaagent.properties")
                .withEnv("OTEL_SERVICE_NAME", "taxonomy-observability-performance")
                .withEnv("OTEL_TRACES_EXPORTER", "otlp")
                .withEnv("OTEL_METRICS_EXPORTER", "none")
                .withEnv("OTEL_LOGS_EXPORTER", "none")
                .withEnv("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf")
                .withEnv("OTEL_EXPORTER_OTLP_ENDPOINT",
                        "http://" + COLLECTOR_ALIAS + ":4318")
                .withEnv("OTEL_TRACES_SAMPLER", mode.sampler())
                .withEnv("OTEL_BSP_SCHEDULE_DELAY", "100")
                .withEnv("OTEL_BSP_EXPORT_TIMEOUT", "1000");
        if (mode.samplerArgument() != null) {
            application.withEnv("OTEL_TRACES_SAMPLER_ARG", mode.samplerArgument());
        }
        return application;
    }

    private Future<String> performanceApplicationImage() {
        Path root = repositoryRoot();
        String dockerfile = """
                FROM %s AS opentelemetry
                FROM %s
                WORKDIR /app
                COPY app.jar /app/app.jar
                COPY javaagent.properties /tmp/javaagent.properties
                COPY --from=opentelemetry /javaagent.jar /tmp/opentelemetry-javaagent.jar
                EXPOSE 8080
                ENTRYPOINT ["java", "-jar", "/app/app.jar"]
                """.formatted(AGENT_IMAGE, RUNTIME_IMAGE);
        return new ImageFromDockerfile("taxonomy-observability-performance-it", false)
                .withFileFromString("Dockerfile", dockerfile)
                .withFileFromPath("app.jar", ContainerTestUtils.findApplicationJar())
                .withFileFromPath(
                        "javaagent.properties",
                        root.resolve("observability/javaagent.properties"));
    }

    private static HttpResponse<String> applicationGet(
            GenericContainer<?> application, String path) throws Exception {
        URI uri = URI.create("http://" + application.getHost() + ":"
                + application.getMappedPort(8080) + path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Authorization", BASIC_AUTH)
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertSuccessful(HttpResponse<String> response) {
        assertThat(response.statusCode())
                .withFailMessage("Performance workload returned %s: %s",
                        response.statusCode(), response.body())
                .isEqualTo(200);
    }

    private void awaitCollectorQuiet() throws InterruptedException {
        int unchanged = 0;
        int previousLength = -1;
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && unchanged < 3) {
            int currentLength = collector.getLogs().length();
            if (currentLength == previousLength) {
                unchanged++;
            } else {
                unchanged = 0;
                previousLength = currentLength;
            }
            Thread.sleep(250);
        }
    }

    private static long readCpuMicros(GenericContainer<?> application) throws Exception {
        String command = """
                if [ -r /sys/fs/cgroup/cpu.stat ]; then
                  awk '$1 == "usage_usec" { print $2; exit }' /sys/fs/cgroup/cpu.stat
                elif [ -r /sys/fs/cgroup/cpuacct/cpuacct.usage ]; then
                  awk '{ printf "%.0f\\n", $1 / 1000 }' /sys/fs/cgroup/cpuacct/cpuacct.usage
                else
                  echo 0
                fi
                """;
        return parseLong(application.execInContainer("sh", "-c", command), "CPU usage");
    }

    private static long readMemoryPeakBytes(GenericContainer<?> application) throws Exception {
        String command = """
                if [ -r /sys/fs/cgroup/memory.peak ]; then
                  cat /sys/fs/cgroup/memory.peak
                elif [ -r /sys/fs/cgroup/memory/memory.max_usage_in_bytes ]; then
                  cat /sys/fs/cgroup/memory/memory.max_usage_in_bytes
                elif [ -r /sys/fs/cgroup/memory.current ]; then
                  cat /sys/fs/cgroup/memory.current
                else
                  echo 0
                fi
                """;
        return parseLong(application.execInContainer("sh", "-c", command), "memory usage");
    }

    private static long parseLong(Container.ExecResult result, String measurement) {
        assertThat(result.getExitCode())
                .withFailMessage("Failed to read %s: %s", measurement, result.getStderr())
                .isZero();
        return Long.parseLong(result.getStdout().trim());
    }

    private static int countSpans(String logs) {
        int count = 0;
        Matcher matcher = EXPORTED_SPAN.matcher(logs);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static long percentileMillis(List<Long> values, int percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile / 100.0 * values.size()) - 1);
        return Math.max(1, Duration.ofNanos(values.get(index)).toMillis());
    }

    private static long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static double overheadPercent(long baseline, long observed) {
        if (baseline <= 0) {
            return observed <= 0 ? 0.0 : 100.0;
        }
        return (observed - baseline) * 100.0 / baseline;
    }

    private static Path writeReport(PerformanceReport report) throws Exception {
        Path directory = repositoryRoot().resolve("target/observability-performance");
        Files.createDirectories(directory);
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve("report.json").toFile(), report);
        Files.writeString(directory.resolve("report.md"), markdown(report), StandardCharsets.UTF_8);
        return directory;
    }

    private static String markdown(PerformanceReport report) {
        StringBuilder markdown = new StringBuilder("# OpenTelemetry performance evidence\n\n")
                .append("Generated: `").append(report.generatedAt()).append("`  \n")
                .append("Java: `").append(report.javaVersion()).append("`  \n")
                .append("Workload: ").append(report.measuredRequests())
                .append(" requests to `").append(report.endpoint()).append("` after ")
                .append(report.warmupRequests()).append(" warm-up requests.\n\n")
                .append("| Mode | Startup ms | Memory peak MiB | CPU µs/request | p50 ms | p95 ms | Spans/request |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (ModeResult mode : report.modes()) {
            markdown.append(String.format(Locale.ROOT,
                    "| %s | %d | %.1f | %.1f | %d | %d | %.2f |%n",
                    mode.name(), mode.startupMillis(),
                    mode.memoryPeakBytes() / (1024.0 * 1024.0),
                    mode.cpuMicrosPerRequest(), mode.p50Millis(), mode.p95Millis(),
                    mode.spansPerRequest()));
        }
        BudgetEvaluation budget = report.budget();
        markdown.append("\n## Budget evaluation\n\n")
                .append(String.format(Locale.ROOT,
                        "- Always-on p95 overhead: **%.1f%%**.%n", budget.p95OverheadPercent()))
                .append(String.format(Locale.ROOT,
                        "- Sampled p95 overhead: **%.1f%%**.%n", budget.sampledP95OverheadPercent()))
                .append(String.format(Locale.ROOT,
                        "- Always-on startup overhead: **%.1f%%**.%n", budget.startupOverheadPercent()))
                .append("- Always-on memory delta: **").append(budget.memoryDeltaMiB())
                .append(" MiB**.\n")
                .append("- More than ").append(budget.investigationThresholdPercent())
                .append("% p95 overhead requires investigation: **")
                .append(budget.investigationRequired()).append("**.\n")
                .append("- Hard regression budget exceeded: **")
                .append(budget.hardBudgetExceeded()).append("**.\n");
        return markdown.toString();
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("project.basedir", "."))
                .toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve("taxonomy-app/pom.xml"))) {
            return current;
        }
        if ("taxonomy-app".equals(current.getFileName().toString())
                && Files.isRegularFile(current.resolve("pom.xml"))) {
            return current.getParent();
        }
        throw new IllegalStateException(
                "Cannot locate Taxonomy repository root from " + current);
    }

    private record Mode(
            String name,
            boolean agentAttached,
            String sampler,
            String samplerArgument) {
    }

    private record ModeResult(
            String name,
            boolean agentAttached,
            String sampler,
            String samplerArgument,
            long startupMillis,
            long memoryPeakBytes,
            long cpuMicros,
            double cpuMicrosPerRequest,
            long p50Millis,
            long p95Millis,
            int exportedSpans,
            double spansPerRequest) {
    }

    private record BudgetEvaluation(
            double investigationThresholdPercent,
            double p95OverheadPercent,
            double sampledP95OverheadPercent,
            double startupOverheadPercent,
            long memoryDeltaMiB,
            boolean investigationRequired,
            boolean p95HardLimitExceeded,
            boolean startupHardLimitExceeded,
            boolean memoryHardLimitExceeded,
            boolean hardBudgetExceeded) {
    }

    private record PerformanceReport(
            int schemaVersion,
            String generatedAt,
            String javaVersion,
            int availableProcessors,
            int warmupRequests,
            int measuredRequests,
            String endpoint,
            List<ModeResult> modes,
            BudgetEvaluation budget) {
    }
}
