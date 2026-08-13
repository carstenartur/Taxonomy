package com.taxonomy;

import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

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
    private static final Duration PRODUCTION_STARTUP_TIMEOUT = Duration.ofMinutes(3);

    @Test
    void relationSurvivesContainerReplacement() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        String dataVolume = "taxonomy-persistence-it-"
                + UUID.randomUUID().toString().replace("-", "");
        GenericContainer<?> first = null;
        GenericContainer<?> second = null;

        try {
            first = persistentAppContainer(dataVolume);
            first.start();
            URI firstOrigin = origin(first);
            awaitInitialized(client, firstOrigin);

            RelationSnapshot beforeWrite = relationSnapshot(client, firstOrigin);
            HttpResponse<String> createResponse = send(client, HttpRequest.newBuilder(
                    firstOrigin.resolve(
                            "/api/architecture/relations/BP/RELATED_TO/BR"))
                    .header("Authorization", AUTHORIZATION)
                    .header("Content-Type", "application/json")
                    .header("If-Match", beforeWrite.etag())
                    .header(
                            "Idempotency-Key",
                            "production-persistence-restart:"
                                    + UUID.randomUUID())
                    .PUT(HttpRequest.BodyPublishers.ofString("""
                            {
                              "status": "accepted",
                              "provenance": "%s",
                              "extensions": {
                                "x-description": "Persistence restart proof"
                              },
                              "rationale": "Persistence restart proof"
                            }
                            """.formatted(PERSISTENCE_PROVENANCE)))
                    .build());
            assertThat(createResponse.statusCode()).isIn(200, 201);
            assertThat(createResponse.headers().firstValue("ETag")).isPresent();

            HttpResponse<String> writtenRelations = relations(client, firstOrigin);
            assertThat(writtenRelations.statusCode()).isEqualTo(200);
            assertThat(writtenRelations.body()).contains(PERSISTENCE_PROVENANCE);
            assertThat(relationCount(client, firstOrigin))
                    .isGreaterThan(beforeWrite.count());
            first.stop();
            first = null;

            second = persistentAppContainer(dataVolume);
            second.start();
            URI secondOrigin = origin(second);
            awaitInitialized(client, secondOrigin);

            HttpResponse<String> persistedRelations = relations(client, secondOrigin);
            assertThat(persistedRelations.statusCode()).isEqualTo(200);
            assertThat(persistedRelations.body())
                    .as("Git-authoritative relation written before container replacement must remain present")
                    .contains(PERSISTENCE_PROVENANCE);

            // Catalogue-derived relation totals may be normalized during startup. The
            // persistence contract is that the explicit Git decision survives and that
            // the repository does not fall below its pre-write baseline.
            assertThat(relationCount(client, secondOrigin))
                    .isGreaterThanOrEqualTo(beforeWrite.count());
        } finally {
            stopQuietly(second);
            stopQuietly(first);
            removeVolume(dataVolume);
        }
    }

    private GenericContainer<?> persistentAppContainer(String dataVolume) {
        return ContainerTestUtils.appContainer()
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withBinds(new Bind(dataVolume, new Volume("/app/data"))))
                .waitingFor(Wait.forHttp("/actuator/health")
                        .forStatusCode(200)
                        .forPort(8080)
                        .withStartupTimeout(PRODUCTION_STARTUP_TIMEOUT))
                .withStartupTimeout(PRODUCTION_STARTUP_TIMEOUT)
                .withEnv("SPRING_PROFILES_ACTIVE", "production,hsqldb")
                .withEnv("TAXONOMY_DATASOURCE_URL",
                        "jdbc:hsqldb:file:/app/data/taxonomydb;hsqldb.default_table_type=cached;"
                                + "hsqldb.write_delay_millis=0;shutdown=true")
                .withEnv("TAXONOMY_DDL_AUTO", "update")
                .withEnv("TAXONOMY_SEARCH_DIRECTORY_TYPE", "local-filesystem")
                .withEnv("TAXONOMY_SEARCH_DIRECTORY_ROOT", "/app/data/lucene-index")
                .withEnv("TAXONOMY_ADMIN_PASSWORD", ADMIN_PASSWORD)
                .withEnv("TAXONOMY_REQUIRE_PASSWORD_CHANGE", "false")
                .withEnv("TAXONOMY_EMBEDDING_ENABLED", "false")
                .withEnv("TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD", "false")
                .withEnv("TAXONOMY_THYMELEAF_CACHE", "true");
    }

    private static void stopQuietly(GenericContainer<?> container) {
        if (container == null) {
            return;
        }
        try {
            container.stop();
        } catch (RuntimeException ignored) {
            // Preserve the original test failure; volume removal below is still attempted.
        }
    }

    private static void removeVolume(String volumeName) {
        try {
            DockerClientFactory.instance().client()
                    .removeVolumeCmd(volumeName)
                    .exec();
        } catch (DockerException ignored) {
            // Cleanup must never mask the original assertion or container failure. This also
            // covers volumes that were never created or remain attached after a failed stop.
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

    private static HttpResponse<String> relations(
            HttpClient client,
            URI origin) throws Exception {
        return send(client, HttpRequest.newBuilder(
                origin.resolve("/api/relations"))
                .header("Authorization", AUTHORIZATION)
                .GET()
                .build());
    }

    private static long relationCount(HttpClient client, URI origin) throws Exception {
        return relationSnapshot(client, origin).count();
    }

    private static RelationSnapshot relationSnapshot(
            HttpClient client,
            URI origin) throws Exception {
        HttpResponse<String> response = send(client, HttpRequest.newBuilder(
                origin.resolve("/api/relations/count"))
                .header("Authorization", AUTHORIZATION)
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        String digits = response.body().replaceAll("[^0-9]", "");
        assertThat(digits).isNotBlank();
        String etag = response.headers().firstValue("ETag").orElseThrow(
                () -> new AssertionError(
                        "relation count response must expose the authoritative Git ETag"));
        return new RelationSnapshot(Long.parseLong(digits), etag);
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private record RelationSnapshot(long count, String etag) {
    }
}
