package com.taxonomy;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the documented embedded production setup retains application data
 * when the application container is replaced while /app/data is preserved.
 */
@Tag("persistence")
class ProductionPersistenceRestartIT {

    private static final String ADMIN_PASSWORD = "Restart-Test-Password-2026!";
    private static final String PERSISTENCE_PROVENANCE = "production-persistence-restart-it";
    private static final String AUTHORIZATION = "Basic " + Base64.getEncoder().encodeToString(
            ("admin:" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8));

    @TempDir
    Path persistentData;

    @Test
    void relationSurvivesContainerReplacement() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        long countBeforeWrite;
        GenericContainer<?> first = persistentAppContainer();
        try {
            first.start();
            URI origin = origin(first);
            awaitInitialized(client, origin);

            countBeforeWrite = relationCount(client, origin);
            HttpResponse<String> createResponse = send(client, HttpRequest.newBuilder(
                    origin.resolve("/api/relations"))
                    .header("Authorization", AUTHORIZATION)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {
                              "sourceCode": "BP",
                              "targetCode": "BR",
                              "relationType": "RELATED_TO",
                              "description": "Persistence restart proof",
                              "provenance": "%s"
                            }
                            """.formatted(PERSISTENCE_PROVENANCE)))
                    .build());
            assertThat(createResponse.statusCode()).isEqualTo(200);
            assertThat(relationCount(client, origin)).isGreaterThan(countBeforeWrite);
        } finally {
            stopContainerAndRestoreHostPermissions(first);
        }

        GenericContainer<?> second = persistentAppContainer();
        try {
            second.start();
            URI origin = origin(second);
            awaitInitialized(client, origin);

            HttpResponse<String> relations = send(client, HttpRequest.newBuilder(
                    origin.resolve("/api/relations"))
                    .header("Authorization", AUTHORIZATION)
                    .GET()
                    .build());
            assertThat(relations.statusCode()).isEqualTo(200);
            assertThat(relations.body())
                    .as("relation written before container replacement must remain present")
                    .contains(PERSISTENCE_PROVENANCE);

            // Catalogue-derived relation totals may be normalized during startup. The
            // persistence contract is that the explicit user relation survives and that
            // the repository does not fall below its pre-write baseline.
            assertThat(relationCount(client, origin)).isGreaterThanOrEqualTo(countBeforeWrite);
        } finally {
            stopContainerAndRestoreHostPermissions(second);
        }
    }

    private GenericContainer<?> persistentAppContainer() {
        return ContainerTestUtils.appContainer()
                .withFileSystemBind(persistentData.toAbsolutePath().toString(),
                        "/app/data", BindMode.READ_WRITE)
                .withEnv("SPRING_PROFILES_ACTIVE", "production,hsqldb")
                .withEnv("TAXONOMY_DATASOURCE_URL",
                        "jdbc:hsqldb:file:/app/data/taxonomydb;hsqldb.default_table_type=cached;shutdown=true")
                .withEnv("TAXONOMY_DDL_AUTO", "update")
                .withEnv("TAXONOMY_SEARCH_DIRECTORY_TYPE", "local-filesystem")
                .withEnv("TAXONOMY_SEARCH_DIRECTORY_ROOT", "/app/data/lucene-index")
                .withEnv("TAXONOMY_ADMIN_PASSWORD", ADMIN_PASSWORD)
                .withEnv("TAXONOMY_REQUIRE_PASSWORD_CHANGE", "false")
                .withEnv("TAXONOMY_EMBEDDING_ENABLED", "false")
                .withEnv("TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD", "false")
                .withEnv("TAXONOMY_THYMELEAF_CACHE", "true");
    }

    /**
     * The production image deliberately runs as the non-root {@code taxonomy}
     * user. Files created in a host bind mount therefore have the container UID.
     * Before JUnit removes its {@link TempDir}, make the persisted contents
     * writable by the host runner. This preserves the non-root runtime contract
     * while keeping test cleanup deterministic.
     */
    private static void stopContainerAndRestoreHostPermissions(GenericContainer<?> container)
            throws Exception {
        try {
            if (container.isRunning()) {
                var result = container.execInContainer(
                        "sh", "-c",
                        "find /app/data -mindepth 1 -exec chmod a+rwX {} +");
                assertThat(result.getExitCode())
                        .as("restore host cleanup permissions: %s", result.getStderr())
                        .isZero();
            }
        } finally {
            container.stop();
        }
    }

    private static URI origin(GenericContainer<?> container) {
        return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(8080));
    }

    private static void awaitInitialized(HttpClient client, URI origin) {
        Awaitility.await("taxonomy initialization")
                .atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    HttpResponse<String> response = send(client, HttpRequest.newBuilder(
                            origin.resolve("/api/status/startup"))
                            .header("Authorization", AUTHORIZATION)
                            .GET()
                            .build());
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.body()).contains("\"initialized\":true");
                });
    }

    private static long relationCount(HttpClient client, URI origin) throws Exception {
        HttpResponse<String> response = send(client, HttpRequest.newBuilder(
                origin.resolve("/api/relations/count"))
                .header("Authorization", AUTHORIZATION)
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        String digits = response.body().replaceAll("[^0-9]", "");
        assertThat(digits).isNotBlank();
        return Long.parseLong(digits);
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
