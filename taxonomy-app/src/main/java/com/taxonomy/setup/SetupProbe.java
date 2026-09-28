package com.taxonomy.setup;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Explicit, bounded connection probes. Never starts Spring, Flyway or an AI provider. */
public final class SetupProbe {
    private SetupProbe() { }

    public static List<SetupChecks.Finding> check(Function<String, String> properties, Set<String> profiles) {
        List<SetupChecks.Finding> result = new ArrayList<>();
        String url = SetupChecks.value(properties, "spring.datasource.url");
        if (url.startsWith("jdbc:hsqldb:")) {
            result.add(new SetupChecks.Finding(SetupChecks.Status.NOT_CHECKED, "database.connection",
                    "Embedded databases are not opened by preflight: opening could create files or conflict with a running instance."));
        } else {
            result.add(bounded("database.connection", () -> database(properties), Duration.ofSeconds(10)));
        }
        if (profiles.contains("keycloak")) {
            String issuer = SetupChecks.value(properties, "spring.security.oauth2.client.provider.keycloak.issuer-uri");
            if (SetupChecks.safeEndpoint(issuer)) {
                result.add(bounded("authentication.discovery", () -> discovery(issuer), Duration.ofSeconds(10)));
            }
        }
        if (Boolean.parseBoolean(SetupChecks.value(properties, "embedding.enabled"))) {
            String directory = SetupChecks.value(properties, "embedding.model.dir");
            if (directory.isEmpty()) {
                result.add(new SetupChecks.Finding(SetupChecks.Status.NOT_CHECKED, "embedding.model.dir", "No local model directory selected; downloads are not performed by preflight."));
            } else {
                try {
                    Path path = Path.of(directory);
                    boolean available = List.of("model.onnx", "tokenizer.json").stream()
                            .allMatch(name -> Files.isRegularFile(path.resolve(name)) && Files.isReadable(path.resolve(name)));
                    result.add(new SetupChecks.Finding(available ? SetupChecks.Status.OK : SetupChecks.Status.ERROR,
                            "embedding.model.dir", available ? "Expected model files are readable; inference was not tested."
                            : "The model directory must contain readable model.onnx and tokenizer.json."));
                    if (available && !Files.isRegularFile(path.resolve("serving.properties"))) {
                        result.add(new SetupChecks.Finding(SetupChecks.Status.NOT_CHECKED, "embedding.serving-properties",
                                "Serving metadata is generated during model startup; generation permissions and model loading were not tested. No file was written."));
                    }
                } catch (IllegalArgumentException invalid) {
                    result.add(new SetupChecks.Finding(SetupChecks.Status.ERROR, "embedding.model.dir", "Invalid model directory."));
                }
            }
        }
        return List.copyOf(result);
    }

    private static SetupChecks.Finding database(Function<String, String> properties) throws Exception {
        String username = properties.apply("spring.datasource.username");
        String password = properties.apply("spring.datasource.password");
        try (Connection connection = DriverManager.getConnection(properties.apply("spring.datasource.url"), username, password)) {
            connection.setReadOnly(true);
            if (!connection.isValid(5)) { throw new IllegalStateException("Connection validation failed"); }
            return new SetupChecks.Finding(SetupChecks.Status.OK, "database.connection",
                    "JDBC connection validated in read-only mode; schema and migration permissions remain untested.");
        }
    }

    private static SetupChecks.Finding discovery(String issuer) throws Exception {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/.well-known/openid-configuration"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return new SetupChecks.Finding(status == 200 ? SetupChecks.Status.OK : SetupChecks.Status.ERROR,
                    "authentication.discovery", status == 200
                    ? "Discovery endpoint reachable; metadata, browser callback and role mapping still require a login test."
                    : "Discovery did not return HTTP 200; redirects are not followed.");
        }
    }

    static SetupChecks.Finding bounded(String key, Callable<SetupChecks.Finding> operation, Duration timeout) {
        FutureTask<SetupChecks.Finding> task = new FutureTask<>(operation);
        Thread thread = Thread.ofVirtual().name("taxonomy-setup-probe").unstarted(task);
        thread.start();
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new SetupChecks.Finding(SetupChecks.Status.ERROR, key, "Connection check interrupted.");
        } catch (Exception failure) {
            // Driver/TLS exceptions can contain credentials and full connection URLs.
            return new SetupChecks.Finding(SetupChecks.Status.ERROR, key,
                    "Connection failed or timed out; check DNS, network policy, TLS trust and credentials in the target environment.");
        } finally {
            task.cancel(true);
        }
    }
}
