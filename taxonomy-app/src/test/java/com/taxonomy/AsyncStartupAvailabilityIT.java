package com.taxonomy;

import com.taxonomy.catalog.definition.BundledCatalogue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real HTTP proves web availability independently of the authoritative catalogue load. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "server.servlet.context-path=/taxonomy",
        "taxonomy.init.async=true",
        "taxonomy.catalogue.resource=startup-test:catalogue",
        "taxonomy.security.require-password-change=false",
        "embedding.enabled=false",
        "embedding.allow-download=false",
        "spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
})
@ActiveProfiles("hsqldb")
@ContextConfiguration(initializers = AsyncStartupAvailabilityIT.BlockedCatalogueInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AsyncStartupAvailabilityIT {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String DATABASE = "jdbc:hsqldb:mem:async-startup-" + UUID.randomUUID()
            + ";shutdown=true;hsqldb.lob_compressed=true;hsqldb.lob_file_scale=1;hsqldb.log_size=1";
    private static final String AUTHORIZATION = "Basic " + Base64.getEncoder().encodeToString(
            ("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8));

    @LocalServerPort private int port;
    @Autowired private BlockedCatalogueResource catalogue;

    @DynamicPropertySource
    static void isolatedDatabaseAndCredentials(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> DATABASE);
        properties.add("taxonomy.admin-password", () -> PASSWORD);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void servesLoginShellAndStatusWhileCatalogueLoadsThenAdmitsCatalogueRequests() throws Exception {
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            try {
                assertThat(catalogue.loadingStarted.await(20, TimeUnit.SECONDS))
                        .as("the real asynchronous catalogue loader must reach its resource")
                        .isTrue();
                assertThat(catalogue.blockExpired)
                        .as("the application context must start before the resource wait expires")
                        .isFalse();

                assertHealth(http, "/actuator/health/liveness", 200, "UP");
                assertHealth(http, "/actuator/health/readiness", 200, "UP");
                assertHealth(http, "/actuator/health", 503, "DOWN");

                var login = get(http, "/login", false, "text/html");
                assertThat(login.statusCode()).isEqualTo(200);
                assertThat(login.body()).contains("class=\"login-form\"", "action=\"/taxonomy/login\"");

                var shell = get(http, "/", true, "text/html");
                assertThat(shell.statusCode()).isEqualTo(200);
                assertThat(shell.body()).contains("id=\"startupBanner\"", "id=\"mainContent\"");

                var unavailable = get(http, "/api/taxonomy", true, "application/json");
                assertThat(unavailable.statusCode()).isEqualTo(503);

                JsonNode loading = startup(http);
                assertThat(loading.path("initialized").asString()).isEqualTo("false");
                assertThat(loading.path("phase").asString()).isEqualTo("LOADING_TAXONOMY");
                assertThat(loading.path("progress").path("phase").asString()).isEqualTo("LOADING_CATALOGUE");
                assertThat(loading.path("features").path("catalogue").asString()).isEqualTo("false");
                assertThat(loading.path("features").path("fullTextSearch").asString()).isEqualTo("false");
                assertThat(catalogue.release.getCount()).isOne();
                assertThat(catalogue.blockExpired).isFalse();

                catalogue.release.countDown();
                await("catalogue and full-text readiness after releasing the real workbook")
                        .atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(250))
                        .untilAsserted(() -> {
                            JsonNode ready = startup(http);
                            assertThat(ready.path("phase").asString()).isEqualTo("READY");
                            assertThat(ready.path("initialized").asString()).isEqualTo("true");
                            assertThat(ready.path("features").path("catalogue").asString()).isEqualTo("true");
                            assertThat(ready.path("features").path("fullTextSearch").asString()).isEqualTo("true");
                        });

                var available = get(http, "/api/taxonomy", true, "application/json");
                assertThat(available.statusCode()).isEqualTo(200);
                JsonNode tree = JSON.readTree(available.body());
                assertThat(tree.isArray()).isTrue();
                assertThat(tree.size()).isEqualTo(8);
            } finally {
                catalogue.release.countDown();
            }
        }
    }

    private void assertHealth(HttpClient http, String path, int status, String health) throws Exception {
        var response = get(http, path, false, "application/json");
        assertThat(response.statusCode()).as(path).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).path("status").asString()).as(path).isEqualTo(health);
    }

    private JsonNode startup(HttpClient http) throws Exception {
        var response = get(http, "/api/status/startup", true, "application/json");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> get(HttpClient http, String path, boolean authenticated, String accept)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/taxonomy" + path))
                .timeout(Duration.ofSeconds(10)).header("Accept", accept).GET();
        if (authenticated) request.header("Authorization", AUTHORIZATION);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static final class BlockedCatalogueInitializer implements ApplicationContextInitializer<GenericApplicationContext> {
        @Override
        public void initialize(GenericApplicationContext context) {
            var resource = new BlockedCatalogueResource(context.getResource(BundledCatalogue.BILINGUAL_RESOURCE));
            context.getBeanFactory().registerSingleton("blockedStartupCatalogue", resource);
            context.addProtocolResolver((location, loader) ->
                    "startup-test:catalogue".equals(location) ? resource : null);
        }
    }

    /** Delays only the real resource read; all parsing, persistence and indexing stay real. */
    static final class BlockedCatalogueResource extends AbstractResource {
        private final Resource workbook;
        private final CountDownLatch loadingStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean blockExpired;

        private BlockedCatalogueResource(Resource workbook) {
            this.workbook = workbook;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            loadingStarted.countDown();
            try {
                if (!release.await(3, TimeUnit.MINUTES)) {
                    blockExpired = true;
                    throw new IOException("Startup availability fixture was not released before its deadline");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Startup availability fixture was interrupted", interrupted);
            }
            return workbook.getInputStream();
        }

        @Override public boolean exists() { return workbook.exists(); }
        @Override public String getFilename() { return workbook.getFilename(); }
        @Override public String getDescription() { return "Blocked bundled catalogue for startup availability verification"; }
    }
}
