package com.taxonomy;

import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the documented embedded production setup retains application data
 * when the application container is replaced while /app/data is preserved.
 */
@Tag("persistence")
class ProductionPersistenceRestartIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String READ_MODEL_HEADER = "X-Taxonomy-Relation-Read-Model";
    private static final String PROJECTION_STATE_HEADER = "X-Taxonomy-Relation-Projection-State";
    private static final String BASELINE_PROVENANCE = "production-persistence-baseline-it";
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

            // The first Git command switches migration-only catalogue reads to the
            // canonical projection. Root templates are deliberately not architecture
            // edges, so their legacy count is not a persistence baseline.
            RelationSnapshot initial = relationSnapshot(client, firstOrigin);
            createRelation(client, firstOrigin, initial.etag(),
                    "BP-1000", "BR-1000", BASELINE_PROVENANCE);
            RelationSnapshot beforeWrite = relationSnapshot(client, firstOrigin);
            Set<RelationDecision> baseline = projectedRelations(client, firstOrigin, beforeWrite);
            assertThat(baseline).contains(new RelationDecision(
                    "BP-1000", "RELATED_TO", "BR-1000", BASELINE_PROVENANCE));

            String writtenEtag = createRelation(client, firstOrigin, beforeWrite.etag(),
                    "BR-1000", "BP-1000", PERSISTENCE_PROVENANCE);
            RelationSnapshot afterWrite = relationSnapshot(client, firstOrigin);
            assertThat(afterWrite.count()).isEqualTo(beforeWrite.count() + 1);
            assertThat(afterWrite.etag()).isEqualTo(writtenEtag);
            Set<RelationDecision> written = projectedRelations(client, firstOrigin, afterWrite);
            Set<RelationDecision> expected = new HashSet<>(baseline);
            expected.add(new RelationDecision(
                    "BR-1000", "RELATED_TO", "BP-1000", PERSISTENCE_PROVENANCE));
            assertThat(written).containsExactlyInAnyOrderElementsOf(expected);
            first.stop();
            first = null;

            second = persistentAppContainer(dataVolume);
            second.start();
            URI secondOrigin = origin(second);
            awaitInitialized(client, secondOrigin);

            RelationSnapshot afterRestart = relationSnapshot(client, secondOrigin);
            assertThat(afterRestart.count())
                    .as("Container replacement must preserve the complete committed relation count")
                    .isEqualTo(afterWrite.count());
            assertThat(afterRestart.etag())
                    .as("Container replacement must not replace the authoritative Git commit")
                    .isEqualTo(afterWrite.etag());
            assertThat(projectedRelations(client, secondOrigin, afterRestart))
                    .as("Every committed relation identity and provenance must survive replacement")
                    .containsExactlyInAnyOrderElementsOf(written);
        } finally {
            stopQuietly(second);
            stopQuietly(first);
            removeVolume(dataVolume);
        }
    }

    private GenericContainer<?> persistentAppContainer(String dataVolume) {
        // Retain ContainerTestUtils' canonical /actuator/health/readiness wait strategy.
        // Aggregate health may contain optional capabilities and is not the supported
        // application-availability boundary for container replacement.
        return ContainerTestUtils.appContainer()
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withBinds(new Bind(dataVolume, new Volume("/app/data"))))
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

    private static String createRelation(
            HttpClient client,
            URI origin,
            String expectedEtag,
            String sourceCode,
            String targetCode,
            String provenance) throws Exception {
        HttpResponse<String> response = send(client, HttpRequest.newBuilder(
                origin.resolve("/api/architecture/relations/"
                        + sourceCode + "/RELATED_TO/" + targetCode))
                .header("Authorization", AUTHORIZATION)
                .header("Content-Type", "application/json")
                .header("If-Match", expectedEtag)
                .header("Idempotency-Key", "production-persistence-restart:" + UUID.randomUUID())
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {
                          "status": "accepted",
                          "provenance": "%s",
                          "extensions": {
                            "x-description": "Persistence restart proof"
                          },
                          "rationale": "Persistence restart proof"
                        }
                        """.formatted(provenance)))
                .build());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        String etag = response.headers().firstValue("ETag").orElseThrow(
                () -> new AssertionError("Creation must return the authoritative Git ETag"));
        assertThat(etag).isNotEqualTo(expectedEtag);
        return etag;
    }

    private static Set<RelationDecision> projectedRelations(
            HttpClient client,
            URI origin,
            RelationSnapshot snapshot) throws Exception {
        HttpResponse<String> response = send(client, HttpRequest.newBuilder(
                origin.resolve("/api/relations"))
                .header("Authorization", AUTHORIZATION)
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(READ_MODEL_HEADER)).hasValue("PROJECTION");
        assertThat(response.headers().firstValue(PROJECTION_STATE_HEADER)).hasValue("READY");
        assertThat(response.headers().firstValue("ETag")).hasValue(snapshot.etag());
        assertThat(snapshot.readModel()).isEqualTo("PROJECTION");
        assertThat(snapshot.projectionState()).isEqualTo("READY");
        JsonNode rows = JSON.readTree(response.body());
        assertThat(rows.isArray()).isTrue();
        assertThat((long) rows.size()).isEqualTo(snapshot.count());
        Set<RelationDecision> decisions = new HashSet<>();
        for (JsonNode row : rows) {
            // Projection row IDs may change on rebuild; Git identities may not.
            assertThat(decisions.add(new RelationDecision(
                    requiredText(row, "sourceCode"),
                    requiredText(row, "relationType"),
                    requiredText(row, "targetCode"),
                    row.path("provenance").asText(null))))
                    .as("A complete relation projection must not contain duplicate decisions")
                    .isTrue();
        }
        return Set.copyOf(decisions);
    }

    private static String requiredText(JsonNode row, String field) {
        JsonNode value = row.path(field);
        assertThat(value.isTextual()).as(field).isTrue();
        assertThat(value.asText()).as(field).isNotBlank();
        return value.asText();
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
        JsonNode count = JSON.readTree(response.body()).path("count");
        assertThat(count.isIntegralNumber()).isTrue();
        assertThat(count.longValue()).isGreaterThanOrEqualTo(0);
        String etag = response.headers().firstValue("ETag").orElseThrow(
                () -> new AssertionError(
                        "relation count response must expose the authoritative Git ETag"));
        return new RelationSnapshot(count.longValue(), etag,
                response.headers().firstValue(READ_MODEL_HEADER).orElseThrow(),
                response.headers().firstValue(PROJECTION_STATE_HEADER).orElseThrow());
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private record RelationSnapshot(
            long count, String etag, String readModel, String projectionState) {
    }

    private record RelationDecision(
            String sourceCode, String relationType, String targetCode, String provenance) {
    }
}
