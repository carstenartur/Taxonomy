package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the real Java-agent and OTLP export path against an OpenTelemetry
 * Collector. The Collector uses its detailed debug exporter so the test can
 * prove parent/child correlation without introducing a trace backend.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObservabilityContainerIT {

    private static final String AGENT_IMAGE =
            "otel/autoinstrumentation-java:2.28.1@sha256:"
                    + "41b92978e61d13d4f32c6eb20c6ae7821a73ffdec8539bc6a73858e884b411d8";
    private static final String RUNTIME_IMAGE =
            "eclipse-temurin:21-jre-jammy@sha256:"
                    + "d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13";
    private static final String COLLECTOR_IMAGE =
            "otel/opentelemetry-collector-contrib@sha256:"
                    + "f2f01157055a9b2aab9df7118e1f1c9abf345e99b23bc7a2bc791db374a7d0f6";
    private static final String COLLECTOR_ALIAS = "otel-collector.test";
    private static final String APP_ALIAS = "taxonomy-observability.test";
    private static final String DEBUG_EXPORT_START = "ResourceSpans #";
    private static final String SAFE_OPERATION_LOG =
            "Observed taxonomy operation component=workspace "
                    + "operation=resolveRepositoryContextForUser outcome=success";
    private static final String BASIC_AUTH = "Basic "
            + Base64.getEncoder().encodeToString(
                    ("admin:" + ContainerTestUtils.TEST_ADMIN_PASSWORD)
                            .getBytes(StandardCharsets.UTF_8));
    private static final Pattern EXPORTED_SPAN = Pattern.compile(
            "Trace ID\\s*:\\s*([0-9a-fA-F]{32}).*?Name\\s*:\\s*([^\\r\\n]+)",
            Pattern.DOTALL);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Network network;
    private GenericContainer<?> collector;
    private GenericContainer<?> application;

    @BeforeAll
    void startObservedApplication() throws Exception {
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

        Future<String> observedImage = observedApplicationImage();
        application = new GenericContainer<>(observedImage)
                .withNetwork(network)
                .withNetworkAliases(APP_ALIAS)
                .withExposedPorts(8080)
                .withEnv("SPRING_PROFILES_ACTIVE", "hsqldb,observability")
                .withEnv("TAXONOMY_ADMIN_PASSWORD",
                        ContainerTestUtils.TEST_ADMIN_PASSWORD)
                .withEnv("TAXONOMY_REQUIRE_PASSWORD_CHANGE", "false")
                .withEnv("TAXONOMY_EMBEDDING_ENABLED", "false")
                .withEnv("TAXONOMY_THYMELEAF_CACHE", "false")
                .withEnv("LLM_MOCK", "true")
                .withEnv("LOGGING_LEVEL_COM_TAXONOMY_OBSERVABILITY", "DEBUG")
                .withEnv("JAVA_TOOL_OPTIONS",
                        "-javaagent:/tmp/opentelemetry-javaagent.jar")
                .withEnv("OTEL_JAVAAGENT_CONFIGURATION_FILE",
                        "/tmp/javaagent.properties")
                .withEnv("OTEL_SERVICE_NAME", "taxonomy-observability-it")
                .withEnv("OTEL_TRACES_EXPORTER", "otlp")
                .withEnv("OTEL_METRICS_EXPORTER", "none")
                .withEnv("OTEL_LOGS_EXPORTER", "none")
                .withEnv("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf")
                .withEnv("OTEL_EXPORTER_OTLP_ENDPOINT",
                        "http://" + COLLECTOR_ALIAS + ":4318")
                .withEnv("OTEL_TRACES_SAMPLER", "always_on")
                .withEnv("OTEL_BSP_SCHEDULE_DELAY", "100")
                .withEnv("OTEL_BSP_EXPORT_TIMEOUT", "1000")
                .waitingFor(Wait.forHttp("/actuator/health")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        application.start();
    }

    @AfterAll
    void stopContainers() throws Exception {
        ContainerTestUtils.closeAll(application, collector, network);
    }

    @Test
    void exportsCorrelatedHttpAndTaxonomySpansAndFailsOpenWithoutCollector()
            throws Exception {
        HttpResponse<String> relations = applicationGet("/api/relations");
        assertThat(relations.statusCode()).isEqualTo(200);

        HttpResponse<String> prometheus = applicationGet(
                "/actuator/prometheus", "text/plain");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("jvm_memory");
        assertThat(prometheus.body())
                .contains("taxonomy_workspace_resolve_seconds_count")
                .contains("taxonomy_component=\"workspace\"")
                .contains("taxonomy_operation=\"resolveRepositoryContextForUser\"")
                .contains("outcome=\"success\"");

        TraceEvidence evidence = awaitCorrelatedTrace();
        assertThat(evidence.spanNames())
                .anyMatch(name -> name.contains("/api/relations"));
        assertThat(evidence.spanNames())
                .anyMatch(name -> name.contains("resolveRepositoryContextForUser"));
        assertThat(evidence.exportedTelemetry())
                .doesNotContain(
                        "taxonomy.workspace.name",
                        "taxonomy.repository.name",
                        "taxonomy.dsl.source",
                        "taxonomy.document.filename",
                        "gen_ai.prompt",
                        "llm.prompt");
        awaitCorrelatedApplicationLog(evidence.traceId());

        collector.stop();

        HttpResponse<String> withoutCollector = applicationGet("/api/relations");
        assertThat(withoutCollector.statusCode()).isEqualTo(200);
    }

    private Future<String> observedApplicationImage() {
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

        // Keep every input in Testcontainers' virtual build context. A
        // filesystem Dockerfile under target/ is excluded by the repository's
        // .dockerignore and therefore cannot reliably see app.jar in CI.
        return new ImageFromDockerfile("taxonomy-observability-it", false)
                .withFileFromString("Dockerfile", dockerfile)
                .withFileFromPath("app.jar", ContainerTestUtils.findApplicationJar())
                .withFileFromPath(
                        "javaagent.properties",
                        root.resolve("observability/javaagent.properties"));
    }

    private TraceEvidence awaitCorrelatedTrace() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String lastLogs = "";
        while (System.nanoTime() < deadline) {
            lastLogs = collector.getLogs();
            Map<String, Set<String>> spansByTrace = spansByTrace(lastLogs);
            for (Map.Entry<String, Set<String>> trace : spansByTrace.entrySet()) {
                boolean http = trace.getValue().stream()
                        .anyMatch(name -> name.contains("/api/relations"));
                boolean taxonomy = trace.getValue().stream()
                        .anyMatch(name -> name.contains("resolveRepositoryContextForUser"));
                if (http && taxonomy) {
                    return new TraceEvidence(
                            trace.getKey(),
                            Set.copyOf(trace.getValue()),
                            exportedTelemetry(lastLogs));
                }
            }
            Thread.sleep(250);
        }

        int start = Math.max(0, lastLogs.length() - 12_000);
        throw new AssertionError(
                "No correlated HTTP and Taxonomy spans were exported. Collector tail:\n"
                        + lastLogs.substring(start));
    }

    private void awaitCorrelatedApplicationLog(String traceId)
            throws InterruptedException {
        Pattern correlatedLog = Pattern.compile(
                "trace_id=" + Pattern.quote(traceId)
                        + "\\s+span_id=[0-9a-fA-F]{16}.*"
                        + Pattern.quote(SAFE_OPERATION_LOG),
                Pattern.CASE_INSENSITIVE);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String lastLogs = "";
        while (System.nanoTime() < deadline) {
            lastLogs = application.getLogs();
            if (correlatedLog.matcher(lastLogs).find()) {
                return;
            }
            Thread.sleep(100);
        }
        int start = Math.max(0, lastLogs.length() - 8_000);
        throw new AssertionError(
                "No safe application log was correlated with trace " + traceId
                        + ". Application tail:\n" + lastLogs.substring(start));
    }

    private static String exportedTelemetry(String collectorLogs) {
        int start = collectorLogs.indexOf(DEBUG_EXPORT_START);
        if (start < 0) {
            throw new AssertionError(
                    "Collector reported spans but no detailed debug export was found");
        }
        return collectorLogs.substring(start);
    }

    private static Map<String, Set<String>> spansByTrace(String logs) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        Matcher matcher = EXPORTED_SPAN.matcher(logs);
        while (matcher.find()) {
            result.computeIfAbsent(
                            matcher.group(1).toLowerCase(),
                            ignored -> new LinkedHashSet<>())
                    .add(matcher.group(2).trim());
        }
        return result;
    }

    private HttpResponse<String> applicationGet(String path) throws Exception {
        return applicationGet(path, "application/json");
    }

    private HttpResponse<String> applicationGet(String path, String accept)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(applicationUri(path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", accept)
                .header("Authorization", BASIC_AUTH)
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI applicationUri(String path) {
        return URI.create("http://" + application.getHost() + ":"
                + application.getMappedPort(8080) + path);
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

    private record TraceEvidence(
            String traceId,
            Set<String> spanNames,
            String exportedTelemetry) {
    }
}
