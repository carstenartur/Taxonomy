package com.taxonomy.setup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Dependency-free cases also invoked by the JUnit suite. */
public final class SetupContractCases {
    private static int checks;

    public static void main(String[] args) throws Exception {
        checks = 0;
        Map<String, String> config = valid();
        expect(!errors(config, Set.of("postgres", "production")), "valid server settings");
        config.put("spring.jpa.hibernate.ddl-auto", "create");
        expect(errors(config, Set.of("postgres")), "persistent DB must not be recreated");
        config = valid();
        config.put("spring.datasource.url", "jdbc:hsqldb:mem:taxonomydb");
        expect(errors(config, Set.of("production", "hsqldb")), "server may not use volatile DB");
        config = valid();
        expect(errors(config, Set.of("postgres", "keycloak", "local-user-management")), "conflicting auth modes");
        expect(errors(config, Set.of("postgres", "keycloak")), "missing issuer is invalid");
        config.put("spring.security.oauth2.client.provider.keycloak.issuer-uri", "https://identity.example/realms/test");
        config.put("spring.security.oauth2.client.registration.keycloak.client-id", "taxonomy");
        config.put("spring.security.oauth2.client.registration.keycloak.client-secret", "secret-never-echo");
        config.put("taxonomy.security.local-users-enabled", "false");
        expect(!errors(config, Set.of("postgres", "keycloak")), "complete central login");
        config.put("spring.security.oauth2.client.provider.keycloak.issuer-uri", "https://user:secret-never-echo@identity.example/realms/test");
        List<SetupChecks.Finding> findings = SetupChecks.check(config::get, Set.of("keycloak"));
        expect(findings.stream().anyMatch(f -> f.status() == SetupChecks.Status.ERROR), "URL credentials rejected");
        expect(!findings.toString().contains("secret-never-echo"), "diagnostics redact credentials");
        config = valid();
        config.put("llm.provider", "CUSTOM_OPENAI");
        expect(errors(config, Set.of("postgres")), "custom model needs endpoint and model");
        config.put("custom.llm.url", "http://localhost:11434/v1/chat/completions");
        config.put("custom.llm.model", "example-model");
        expect(!errors(config, Set.of("postgres")), "explicit local provider");
        config.put("custom.llm.url", "http://localhost.evil.example/v1/chat/completions");
        expect(errors(config, Set.of("postgres")), "loopback exception must not accept suffix host");
        config = valid();
        config.put("embedding.enabled", "true");
        expect(errors(config, Set.of("postgres")), "model or explicit download consent required");
        config.put("embedding.allow-download", "true");
        expect(!errors(config, Set.of("postgres")), "download explicitly allowed, not performed");
        config = valid();
        config.put("llm.provider", "unknown-provider");
        expect(errors(config, Set.of("postgres")), "unknown provider rejected");
        config = valid();
        config.put("server.port", "99999");
        expect(errors(config, Set.of("postgres")), "port range");
        config.put("server.port", "invalid-secret-never-echo");
        expect(!SetupChecks.check(config::get, Set.of()).toString().contains("invalid-secret"), "invalid numbers redacted");
        config = valid();
        config.put("embedding.enabled", "tru");
        expect(errors(config, Set.of("postgres")), "invalid boolean rejected");
        for (String provider : List.of("GEMINI", "OPENAI", "DEEPSEEK", "QWEN", "LLAMA", "MISTRAL")) {
            config = valid();
            config.put("llm.provider", provider);
            expect(errors(config, Set.of("postgres")), "provider credential required");
            config.put(provider.toLowerCase(java.util.Locale.ROOT) + ".api.key", "not-a-real-credential");
            expect(!errors(config, Set.of("postgres")), "provider credential available");
        }
        config = valid();
        config.put("llm.provider", "LOCAL_ONNX");
        expect(errors(config, Set.of("postgres")), "LOCAL_ONNX cannot implicitly enable embeddings");
        config.put("embedding.enabled", "true");
        config.put("embedding.model.dir", "local-model");
        expect(!errors(config, Set.of("postgres")), "explicit local embeddings");
        expect(!SetupChecks.safeEndpoint("https://host.invalid/realm?password=hidden"), "URL query rejected");
        expect(!SetupChecks.safeEndpoint("not a URL"), "malformed endpoint");
        expect(!SetupChecks.safeEndpoint("file:/private"), "non-HTTP endpoint");
        expect(SetupChecks.safeEndpoint("http://[::1]:8080/realm"), "IPv6 loopback");
        var failure = SetupProbe.bounded("test", () -> { throw new java.sql.SQLException("hidden-password"); }, java.time.Duration.ofSeconds(1));
        expect(failure.status() == SetupChecks.Status.ERROR && !failure.toString().contains("hidden-password"), "probe failure redacted");
        var timeout = SetupProbe.bounded("test", () -> { Thread.sleep(1000); return null; }, java.time.Duration.ofMillis(5));
        expect(timeout.status() == SetupChecks.Status.ERROR, "probe has bounded timeout");
        Map<String, String> embedded = Map.of("spring.datasource.url", "jdbc:hsqldb:file:/must-not-be-created");
        expect(SetupProbe.check(embedded::get, Set.of()).getFirst().status() == SetupChecks.Status.NOT_CHECKED, "embedded database is not opened");
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realm/.well-known/openid-configuration", exchange -> { exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            Map<String, String> oidc = new HashMap<>(embedded);
            oidc.put("spring.security.oauth2.client.provider.keycloak.issuer-uri", "http://127.0.0.1:" + server.getAddress().getPort() + "/realm");
            expect(SetupProbe.check(oidc::get, Set.of("keycloak")).stream().anyMatch(f -> f.key().equals("authentication.discovery") && f.status() == SetupChecks.Status.OK), "explicit loopback discovery reachable");
        } finally { server.stop(0); }
        Path parent = Files.createTempDirectory("taxonomy setup test ");
        Path target = parent.resolve("settings");
        Properties props = LocalSetup.localProperties(parent.resolve("data with spaces ÄÖé"));
        expect(props.getProperty("spring.datasource.url").contains("data with spaces"), "absolute data path preserved");
        expect("127.0.0.1".equals(props.getProperty("server.address")), "local setup is loopback only");
        expect("update".equals(props.getProperty("spring.jpa.hibernate.ddl-auto")), "local schema preserved");
        LocalSetup.write(target, props, Map.of("taxonomy.admin-password", "only-in-secret-file"));
        String application = Files.readString(target.resolve("application.properties"));
        expect(!application.contains("only-in-secret-file"), "public config contains no credentials");
        expect(Files.readString(target.resolve("secrets/taxonomy.admin-password")).equals("only-in-secret-file"), "secret stored separately");
        Properties loaded = new Properties();
        try (var input = Files.newInputStream(target.resolve("application.properties"))) { loaded.load(input); }
        expect(loaded.getProperty("spring.config.import").startsWith("configtree:"), "required configtree import");
        expect(loaded.getProperty("spring.datasource.url").equals(props.getProperty("spring.datasource.url")), "properties escaping round trips");
        boolean refused = false;
        try { LocalSetup.write(target, props, Map.of()); } catch (java.io.IOException expected) { refused = true; }
        expect(refused, "reinstall refuses to overwrite existing config");
        expect(Files.readString(target.resolve("secrets/taxonomy.admin-password")).equals("only-in-secret-file"), "reinstall preserves secret");
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            expect(Files.getPosixFilePermissions(target).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")), "owner-only directory");
            expect(Files.getPosixFilePermissions(target.resolve("secrets/taxonomy.admin-password")).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")), "owner-only secret");
        }
        expect(Files.isDirectory(target.resolve("data")), "private durable data directory exists");
        expect(!Files.exists(target.resolve(".application.properties.tmp")), "configuration publication completed");
        Path model = Files.createDirectory(parent.resolve("model"));
        Map<String, String> modelConfig = new HashMap<>(embedded);
        modelConfig.put("embedding.enabled", "true");
        modelConfig.put("embedding.model.dir", model.toString());
        expect(SetupProbe.check(modelConfig::get, Set.of()).stream().anyMatch(f -> f.status() == SetupChecks.Status.ERROR), "missing model files reported");
        for (String name : List.of("model.onnx", "tokenizer.json", "serving.properties")) { Files.writeString(model.resolve(name), "fixture"); }
        expect(SetupProbe.check(modelConfig::get, Set.of()).stream().noneMatch(f -> f.status() == SetupChecks.Status.ERROR), "model files present without inference");
        try (var paths = Files.walk(parent)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.delete(path); }
        }
        System.out.println("Setup contract: " + checks + " checks passed");
    }

    private static Map<String, String> valid() {
        Map<String, String> result = new HashMap<>();
        result.put("spring.datasource.url", "jdbc:postgresql://database.example:5432/taxonomy");
        result.put("spring.datasource.username", "taxonomy");
        result.put("spring.datasource.password", "private-value");
        result.put("spring.jpa.hibernate.ddl-auto", "validate");
        result.put("server.port", "8080");
        return result;
    }

    private static boolean errors(Map<String, String> config, Set<String> profiles) {
        return SetupChecks.check(config::get, profiles).stream().anyMatch(f -> f.status() == SetupChecks.Status.ERROR);
    }

    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) { throw new AssertionError(message); }
    }
}
