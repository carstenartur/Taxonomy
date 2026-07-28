package com.taxonomy.observability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for the optional OpenTelemetry deployment files. */
class ObservabilityConfigurationTest {

    private static final String AGENT_PATH =
            "/opt/opentelemetry/opentelemetry-javaagent.jar";
    private static final String AGENT_IMAGE =
            "otel/autoinstrumentation-java:2.28.1@sha256:"
                    + "41b92978e61d13d4f32c6eb20c6ae7821a73ffdec8539bc6a73858e884b411d8";

    @Test
    void runtimeImageBundlesAgentButDoesNotAttachItByDefault() throws IOException {
        String dockerfile = readRepositoryFile("Dockerfile");

        assertTrue(dockerfile.contains("FROM " + AGENT_IMAGE + " AS opentelemetry"));
        assertTrue(dockerfile.contains(
                "COPY --from=opentelemetry --chown=taxonomy:taxonomy /javaagent.jar "
                        + AGENT_PATH));
        assertTrue(dockerfile.contains(
                "COPY --chown=taxonomy:taxonomy observability/javaagent.properties"));

        String entrypoint = dockerfile.lines()
                .filter(line -> line.startsWith("ENTRYPOINT"))
                .findFirst()
                .orElseThrow();
        assertFalse(entrypoint.contains("-javaagent"),
                "The normal container entrypoint must remain telemetry-independent");
    }

    @Test
    void optionalComposeStackExportsOnlyTracesAndKeepsIngestionPrivate()
            throws IOException {
        String compose = readRepositoryFile("docker-compose.observability.yml");

        assertTrue(compose.contains("-javaagent:" + AGENT_PATH));
        assertTrue(compose.contains(
                "OTEL_JAVAAGENT_CONFIGURATION_FILE: /opt/opentelemetry/javaagent.properties"));
        assertTrue(compose.contains("OTEL_TRACES_EXPORTER: otlp"));
        assertTrue(compose.contains("OTEL_METRICS_EXPORTER: none"));
        assertTrue(compose.contains("OTEL_LOGS_EXPORTER: none"));
        assertTrue(compose.contains("127.0.0.1:8080:8080"));
        assertTrue(compose.contains("127.0.0.1:16686:16686"));
        assertFalse(compose.contains("4317:4317"),
                "OTLP gRPC ingestion must not be published to the host");
        assertFalse(compose.contains("4318:4318"),
                "OTLP HTTP ingestion must not be published to the host");
    }

    @Test
    void collectorAppliesResourceBoundsAndContentRedaction() throws IOException {
        String collector = readRepositoryFile("observability/otel-collector-config.yml");

        assertTrue(collector.contains("memory_limiter:"));
        assertTrue(collector.contains("limit_mib: 128"));
        assertTrue(collector.contains("send_batch_max_size: 1024"));
        assertTrue(collector.contains("queue_size: 512"));
        assertTrue(collector.contains("transform/privacy:"));
        assertTrue(collector.contains("\"gen_ai.prompt\""));
        assertTrue(collector.contains("\"gen_ai.input.messages\""));
        assertTrue(collector.contains("\"taxonomy.dsl.source\""));
        assertTrue(collector.contains("\"taxonomy.document.filename\""));
        assertTrue(collector.contains("\"exception.message\""));
        assertTrue(collector.contains("\"url.query\""));
    }

    @Test
    void domainInstrumentationNeverReferencesInvocationContent() throws Exception {
        Properties properties = loadAgentProperties();

        assertTrue(Boolean.parseBoolean(properties.getProperty(
                "otel.instrumentation.common.db-statement-sanitizer.enabled")));
        assertFalse(Boolean.parseBoolean(properties.getProperty(
                "otel.instrumentation.common.enduser.id.enabled")));
        assertFalse(Boolean.parseBoolean(properties.getProperty(
                "otel.instrumentation.common.enduser.role.enabled")));
        assertFalse(Boolean.parseBoolean(properties.getProperty(
                "otel.instrumentation.common.enduser.scope.enabled")));

        Map<String, Set<String>> targets = parseTargets(properties.getProperty(
                "otel.instrumentation.methods.include"));
        assertFalse(targets.isEmpty());

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        for (Map.Entry<String, Set<String>> target : targets.entrySet()) {
            Class<?> type = Class.forName(target.getKey(), false, classLoader);
            for (String methodName : target.getValue()) {
                assertTrue(Arrays.stream(type.getMethods())
                                .anyMatch(method -> method.getName().equals(methodName)),
                        () -> "Configured OpenTelemetry method does not exist: "
                                + target.getKey() + "." + methodName);
            }
        }

        String raw = readRepositoryFile("observability/javaagent.properties")
                .toLowerCase();
        assertFalse(raw.contains("spanattribute"));
        assertFalse(raw.contains("capture-request-headers"));
        assertFalse(raw.contains("capture-response-headers"));
        assertFalse(raw.contains("capture-request-parameters"));
    }

    @Test
    void observabilityProfileUsesAgentMdcKeysForLogCorrelation()
            throws IOException {
        String properties = readRepositoryFile(
                "taxonomy-app/src/main/resources/application-observability.properties");

        assertTrue(properties.contains("%X{trace_id:-none}"));
        assertTrue(properties.contains("%X{span_id:-none}"));
    }

    private static Properties loadAgentProperties() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(
                repositoryRoot().resolve("observability/javaagent.properties"))) {
            properties.load(reader);
        }
        return properties;
    }

    private static Map<String, Set<String>> parseTargets(String configuredTargets) {
        assertNotNull(configuredTargets);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String entry : configuredTargets.split(";")) {
            int openingBracket = entry.indexOf('[');
            int closingBracket = entry.lastIndexOf(']');
            assertTrue(openingBracket > 0 && closingBracket > openingBracket,
                    () -> "Invalid methods instrumentation entry: " + entry);

            String className = entry.substring(0, openingBracket).trim();
            Set<String> methods = new LinkedHashSet<>();
            for (String method : entry.substring(openingBracket + 1, closingBracket)
                    .split(",")) {
                if (!method.isBlank()) {
                    methods.add(method.trim());
                }
            }
            assertFalse(methods.isEmpty(),
                    () -> "No methods configured for " + className);
            result.put(className, methods);
        }
        return result;
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        return Files.readString(repositoryRoot().resolve(relativePath));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
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
}
