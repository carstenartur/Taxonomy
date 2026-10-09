package com.taxonomy.build;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

final class PackagedPluginSupport {
    private static final String PASSWORD = "Plugin-Packaging-Test-2026!";
    static Path repository() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".mvn/verification-suites.json"))) root = root.getParent();
        assertThat(root).isNotNull(); return root;
    }
    static Path application(Path root) throws IOException { return artifact(root.resolve("taxonomy-app/target"), "taxonomy-app-"); }
    static Path artifact(Path directory, String prefix) throws IOException {
        try (var files = Files.list(directory)) {
            var jars = files.filter(p -> p.getFileName().toString().startsWith(prefix) && p.toString().endsWith(".jar")
                    && !p.toString().endsWith("-sources.jar") && !p.toString().endsWith("-javadoc.jar")
                    && !p.toString().endsWith("-test-fixtures.jar")).toList();
            assertThat(jars).as("One built artifact in %s", directory).hasSize(1); return jars.getFirst();
        }
    }
    static String sha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var stream = new java.security.DigestInputStream(Files.newInputStream(file), digest)) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
    static Path buildIndependentPlugin(Path root, Path temporary) throws Exception {
        Path localRepository = Files.createDirectory(temporary.resolve("empty-maven-repository"));
        try (var contents = Files.list(localRepository)) { assertThat(contents).isEmpty(); }
        String version = System.getProperty("taxonomy.project.version");
        assertThat(version).isNotBlank();
        // Install only exact built SDK JARs and their original published POMs, never host sources.
        for (String module : List.of("taxonomy", "taxonomy-domain", "taxonomy-extension-api", "taxonomy-export")) {
            Path destination = Files.createDirectories(localRepository.resolve("com/taxonomy/" + module + "/" + version));
            Files.copy(root.resolve(module.equals("taxonomy") ? "pom.xml" : module + "/pom.xml"),
                    destination.resolve(module + "-" + version + ".pom"));
            if (!module.equals("taxonomy")) Files.copy(artifact(root.resolve(module + "/target"), module + "-"),
                    destination.resolve(module + "-" + version + ".jar"));
        }
        Path project = Files.createDirectory(temporary.resolve("independent-project"));
        Path source = root.resolve("plugins/taxonomy-mermaid-plugin");
        Files.copy(source.resolve("pom.xml"), project.resolve("pom.xml"));
        try (var paths = Files.walk(source.resolve("src"))) {
            for (Path input : paths.sorted().toList()) {
                Path output = project.resolve(source.relativize(input));
                if (Files.isDirectory(input)) Files.createDirectories(output); else Files.copy(input, output);
            }
        }
        var command = new ArrayList<>(List.of(System.getProperty("taxonomy.maven.executable"), "-B", "-ntp",
                "-Dmaven.repo.local=" + localRepository, "-Dtaxonomy.sdk.version=" + version, "verify"));
        String settings = System.getProperty("taxonomy.maven.user-settings");
        if (settings != null && Files.isRegularFile(Path.of(settings))) command.addAll(List.of("-s", settings));
        var builder = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true)
                .redirectOutput(temporary.resolve("independent-build.log").toFile());
        builder.environment().remove("MAVEN_PROJECTBASEDIR");
        builder.environment().remove("MAVEN_MULTI_MODULE_PROJECT_DIRECTORY");
        var process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
        assertThat(finished).as("Independent plugin build timeout; log: %s", temporary.resolve("independent-build.log")).isTrue();
        assertThat(process.exitValue()).as(Files.readString(temporary.resolve("independent-build.log"))).isZero();
        int tests = 0;
        var xml = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var reports = Files.list(project.resolve("target/surefire-reports"))) {
            for (Path report : reports.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml")).toList()) {
                var suite = xml.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
                tests += Integer.parseInt(suite.getAttribute("tests"));
                for (String outcome : List.of("failures", "errors", "skipped"))
                    assertThat(Integer.parseInt(suite.getAttribute(outcome))).as("Independent plugin %s", outcome).isZero();
            }
        }
        assertThat(tests).as("Moved Mermaid regression tests must execute in the independent build").isGreaterThanOrEqualTo(5);
        Files.writeString(root.resolve("taxonomy-build/target/plugin-sdk-verification.txt"), "independent.tests=" + tests + "\nfailures=0\nerrors=0\nskipped=0\n");
        return artifact(project.resolve("target"), "taxonomy-mermaid-plugin-");
    }
    static Application start(Path host, Path plugins, Path state) throws Exception {
        return start(host, plugins, host.getParent().resolve("features"), state);
    }
    static Application start(Path host, Path plugins, Path features, Path state) throws Exception {
        return start(host, plugins, features, state, List.of());
    }
    static Application start(Path host, Path plugins, Path features, Path state, List<String> extraArguments) throws Exception {
        Files.createDirectories(state);
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx700m", "-Dloader.path=" + features, "-jar", host.toString(), "--server.address=127.0.0.1", "--server.port=" + port,
                "--taxonomy.plugins.directory=" + plugins, "--taxonomy.plugins.cache-directory=" + state.resolve("cache"),
                "--taxonomy.admin-password=" + PASSWORD, "--taxonomy.security.require-password-change=false",
                "--llm.mock=true", "--embedding.enabled=false", "--embedding.allow-download=false",
                "--taxonomy.init.async=false", "--spring.datasource.url=jdbc:hsqldb:mem:pluginpack;shutdown=true",
                "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"));
        for (String override : extraArguments) {
            if (!override.startsWith("--") || !override.contains("=")) throw new IllegalArgumentException("Expected named application argument");
            String key = override.substring(0, override.indexOf('=') + 1);
            command.removeIf(argument -> argument.startsWith(key));
            command.add(override);
        }
        Path log = state.resolve("application.log");
        var builder = new ProcessBuilder(command).directory(state.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("TAXONOMY_REQUIRE_PASSWORD_CHANGE", "false");
        var process = builder.start();
        var app = new Application(process, URI.create("http://127.0.0.1:" + port), log);
        try {
            long end = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            while (System.nanoTime() < end) {
                if (!process.isAlive()) throw new AssertionError("Packaged host exited: " + Files.readString(log));
                try { if (app.request("GET", "/actuator/health/readiness", null).statusCode() == 200) return app; }
                catch (IOException starting) { /* bounded readiness probe */ }
                Thread.sleep(300);
            }
            throw new AssertionError("Packaged host not ready: " + Files.readString(log));
        } catch (Throwable failed) { app.close(); throw failed; }
    }
    record Application(Process process, URI origin, Path log) implements AutoCloseable {
        HttpResponse<String> request(String method, String route, String body) throws IOException, InterruptedException {
            return request(method, route, body, Map.of());
        }
        HttpResponse<String> request(String method, String route, String body, Map<String, String> headers) throws IOException, InterruptedException {
            var builder = HttpRequest.newBuilder(origin.resolve(route)).timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/json");
            headers.forEach(builder::header);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        @Override public void close() throws InterruptedException {
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
            assertThat(process.isAlive()).as("Packaged host process must terminate").isFalse();
        }
    }
}
