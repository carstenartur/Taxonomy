package com.taxonomy.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Dependency-free review regressions, also executed by the Maven-owned JUnit suite. */
public final class SetupReviewRegressionCases {
    private static final String SECRET = "fixture-secret-never-print";

    private SetupReviewRegressionCases() { }

    @FunctionalInterface
    private interface Check { void run() throws Exception; }
    private record TestCase(String name, Check check) { }
    private record DatabaseCase(String profile, String url, String engine) { }
    private record EndpointCase(String name, String url) { }

    public static void main(String[] args) throws Exception {
        List<TestCase> cases = new ArrayList<>();
        for (String key : List.of("spring.datasource.password", "SPRING_DATASOURCE_PASSWORD",
                "KEYCLOAK_CLIENT_SECRET", "CUSTOM_LLM_API_KEY", "ADMIN_TOKEN", "Custom_Api_Key",
                "custom.apiKey", "custom.api-key", "custom.api_key", "custom.clientSecret",
                "custom.access-token", "CUSTOM_PRIVATE_KEY", "custom.credentials", "CUSTOM_PASSWD", "CUSTOM_PWD")) {
            cases.add(new TestCase("reject ordinary credential " + key, () -> rejectCredential(key)));
        }
        cases.add(new TestCase("preserve safe metadata and separate credentials", SetupReviewRegressionCases::safeLocalConfiguration));
        // Keep production in this matrix: HSQLDB fixtures are file-backed, not volatile.
        // The separate mem: case below verifies that volatile production storage is refused.
        List<DatabaseCase> databases = List.of(
                new DatabaseCase("postgres", "jdbc:postgresql://database:5432/taxonomy", "postgres"),
                new DatabaseCase("mssql", "jdbc:sqlserver://database:1433;databaseName=taxonomy", "mssql"),
                new DatabaseCase("oracle", "jdbc:oracle:thin:@//database:1521/taxonomy", "oracle"),
                new DatabaseCase("hsqldb", "jdbc:hsqldb:file:/unused/taxonomy", "hsqldb"),
                new DatabaseCase("hsqldb-file", "jdbc:hsqldb:file:/unused/taxonomy", "hsqldb"));
        for (DatabaseCase profile : databases) {
            for (DatabaseCase address : databases) {
                cases.add(new TestCase("profile " + profile.profile() + " with " + address.profile() + " URL", () -> {
                    Map<String, String> config = config(address.url());
                    boolean mismatch = !profile.engine().equals(address.engine());
                    var findings = SetupChecks.check(config::get, Set.of(profile.profile(), "production"));
                    require(hasError(findings) == mismatch, "Profile/URL compatibility was classified incorrectly");
                }));
            }
        }
        cases.add(new TestCase("reject multiple database engines", () -> {
            Map<String, String> config = config(databases.getFirst().url());
            require(hasError(SetupChecks.check(config::get, Set.of("postgres", "mssql"))), "Conflicting database profiles accepted");
        }));
        cases.add(new TestCase("allow HSQLDB profile aliases together", () -> {
            Map<String, String> config = config("jdbc:hsqldb:file:/unused/taxonomy");
            require(!hasError(SetupChecks.check(config::get, Set.of("hsqldb", "hsqldb-file"))), "Same-engine aliases rejected");
        }));
        cases.add(new TestCase("preserve explicit configuration without a database profile", () -> {
            Map<String, String> config = config(databases.getFirst().url());
            require(!hasError(SetupChecks.check(config::get, Set.of("production"))), "Explicit unprofiled configuration rejected");
        }));
        for (String property : List.of("spring.jpa.hibernate.ddl-auto", "spring.jpa.properties.hibernate.hbm2ddl.auto",
                "spring.jpa.properties.jakarta.persistence.schema-generation.database.action")) {
            for (String action : List.of("create-only", " CREATE-ONLY ", "create", "create-drop", "drop", "truncate")) {
                cases.add(new TestCase("reject " + property + "=" + action.trim(), () -> {
                    Map<String, String> config = config(databases.getFirst().url());
                    config.put(property, action);
                    require(SetupChecks.check(config::get, Set.of("postgres")).stream()
                            .anyMatch(f -> f.status() == SetupChecks.Status.ERROR && f.key().equals(property)),
                            "Persistent schema creation or destruction accepted");
                }));
            }
        }
        for (String action : List.of("none", "validate", "update")) {
            cases.add(new TestCase("preserve supported DDL action " + action, () -> {
                Map<String, String> config = config("jdbc:hsqldb:file:/unused/taxonomy");
                config.put("spring.jpa.hibernate.ddl-auto", action);
                require(!hasError(SetupChecks.check(config::get, Set.of("hsqldb"))), "Supported persistent action rejected");
            }));
        }
        cases.add(new TestCase("retain disposable in-memory test configuration", () -> {
            Map<String, String> config = config("jdbc:hsqldb:mem:test");
            config.put("spring.jpa.hibernate.ddl-auto", "create-drop");
            require(!hasError(SetupChecks.check(config::get, Set.of("hsqldb"))), "Disposable test configuration rejected");
            require(hasError(SetupChecks.check(config::get, Set.of("hsqldb", "production"))), "Volatile production database accepted");
        }));
        cases.add(new TestCase("profile mismatch diagnostics never echo JDBC credentials", () -> {
            Map<String, String> config = config("jdbc:sqlserver://database;password=" + SECRET);
            var findings = SetupChecks.check(config::get, Set.of("postgres"));
            require(hasError(findings), "Profile mismatch accepted");
            require(!findings.toString().contains(SECRET), "Diagnostic disclosed a credential");
        }));
        customEndpointCases(cases);
        int failed = 0;
        for (TestCase test : cases) {
            try {
                test.check().run();
                System.out.println("PASS: " + test.name());
            } catch (AssertionError | Exception failure) {
                failed++;
                // Names and exception types only: do not print configuration values.
                System.err.println("FAIL: " + test.name() + " (" + failure.getClass().getSimpleName() + ")");
            }
        }
        System.out.println("Setup review regressions: " + cases.size() + " cases, " + failed + " failures");
        if (failed != 0) { throw new AssertionError("Setup review regressions failed: " + failed); }
    }

    private static void customEndpointCases(List<TestCase> cases) {
        List<EndpointCase> invalid = List.of(
                new EndpointCase("remote HTTP", "http://ai.example.invalid/v1/chat/completions"),
                new EndpointCase("userinfo", "https://user:" + SECRET + "@ai.example.invalid/v1/chat/completions"),
                new EndpointCase("query", "https://ai.example.invalid/v1/chat/completions?token=" + SECRET),
                new EndpointCase("fragment", "https://ai.example.invalid/v1/chat/completions#fragment"),
                new EndpointCase("loopback lookalike", "http://localhost.example.invalid/v1/chat/completions"),
                new EndpointCase("missing host", "https://:443/v1/chat/completions"),
                new EndpointCase("backslash", "https://ai.example.invalid\\evil/v1/chat/completions"),
                new EndpointCase("non-HTTP scheme", "file:/private/model"));
        List<EndpointCase> valid = List.of(
                new EndpointCase("HTTPS", "https://ai.example.invalid/v1/chat/completions"),
                new EndpointCase("localhost", "http://localhost:11434/v1/chat/completions"),
                new EndpointCase("IPv4 loopback", "http://127.0.0.1:11434/v1/chat/completions"),
                new EndpointCase("IPv6 loopback", "http://[::1]:11434/v1/chat/completions"));
        for (String provider : List.of("", "CUSTOM_OPENAI", "GEMINI")) {
            String selection = provider.isEmpty() ? "auto" : provider;
            for (EndpointCase endpoint : invalid) {
                cases.add(new TestCase("reject custom " + endpoint.name() + " with " + selection, () -> {
                    var settings = customConfiguration(provider, endpoint.url());
                    var findings = SetupChecks.check(settings::get, Set.of("hsqldb"));
                    require(findings.stream().anyMatch(f -> f.status() == SetupChecks.Status.ERROR
                            && f.key().equals("custom.llm.url")), "Unsafe custom endpoint passed preflight");
                    require(!findings.toString().contains(SECRET), "Endpoint diagnostic exposed a credential");
                }));
            }
            for (EndpointCase endpoint : valid) {
                cases.add(new TestCase("accept custom " + endpoint.name() + " with " + selection, () ->
                        require(!hasError(SetupChecks.check(customConfiguration(provider, endpoint.url())::get,
                                Set.of("hsqldb"))), "Valid custom endpoint rejected")));
            }
        }
        for (String provider : List.of("", "CUSTOM_OPENAI")) {
            cases.add(new TestCase("custom candidate needs model with " + (provider.isEmpty() ? "auto" : provider), () -> {
                var settings = customConfiguration(provider, valid.getFirst().url());
                settings.put("custom.llm.model", "  ");
                require(SetupChecks.check(settings::get, Set.of("hsqldb")).stream().anyMatch(f ->
                        f.status() == SetupChecks.Status.ERROR && f.key().equals("custom.llm.model")),
                        "Incomplete custom configuration accepted");
            }));
        }
        cases.add(new TestCase("explicit custom still requires URL", () ->
                require(hasError(SetupChecks.check(customConfiguration("CUSTOM_OPENAI", "")::get,
                        Set.of("hsqldb"))), "Missing explicit custom URL accepted")));
        cases.add(new TestCase("other explicit provider does not require unused custom model", () -> {
            var settings = customConfiguration("GEMINI", valid.getFirst().url());
            settings.remove("custom.llm.model");
            require(!hasError(SetupChecks.check(settings::get, Set.of("hsqldb"))), "Unused model made mandatory");
        }));
    }

    private static Map<String, String> customConfiguration(String provider, String url) {
        var settings = config("jdbc:hsqldb:file:/unused/taxonomy");
        settings.put("llm.provider", provider);
        settings.put("custom.llm.url", url);
        settings.put("custom.llm.model", "fixture-model");
        if (provider.equals("GEMINI")) { settings.put("gemini.api.key", SECRET); }
        return settings;
    }

    private static void rejectCredential(String key) throws Exception {
        Path parent = Files.createTempDirectory("taxonomy-review-secret-");
        try {
            Path target = parent.resolve("settings");
            Properties properties = LocalSetup.localProperties(parent.resolve("data"));
            properties.setProperty(key, SECRET);
            boolean rejected = false;
            try { LocalSetup.write(target, properties, Map.of()); }
            catch (IOException expected) {
                rejected = true;
                require(!expected.toString().contains(SECRET), "Exception disclosed a credential");
            }
            require(rejected, "Ordinary credential accepted");
            require(!Files.exists(target), "Rejected setup must not create any files");
        } finally { deleteTemporaryDirectory(parent); }
    }

    private static void safeLocalConfiguration() throws Exception {
        Path parent = Files.createTempDirectory("taxonomy-review-safe-");
        try {
            Path target = parent.resolve("settings");
            Properties properties = LocalSetup.localProperties(parent.resolve("data"));
            properties.setProperty("custom.token-uri", "https://identity.example.invalid/token");
            properties.setProperty("custom.password-min-length", "16");
            properties.setProperty("CUSTOM_API_KEY_FILE", "/private/key");
            LocalSetup.write(target, properties, Map.of("CUSTOM_LLM_API_KEY", SECRET));
            require(!Files.readString(target.resolve("application.properties")).contains(SECRET), "Credential leaked into ordinary properties");
            require(Files.readString(target.resolve("secrets/CUSTOM_LLM_API_KEY")).equals(SECRET), "Separate credential changed");
        } finally { deleteTemporaryDirectory(parent); }
    }

    private static Map<String, String> config(String url) {
        Map<String, String> result = new HashMap<>();
        result.put("spring.datasource.url", url);
        result.put("spring.jpa.hibernate.ddl-auto", "validate");
        return result;
    }

    private static boolean hasError(List<SetupChecks.Finding> findings) {
        return findings.stream().anyMatch(f -> f.status() == SetupChecks.Status.ERROR);
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    private static void deleteTemporaryDirectory(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.delete(path); }
        }
    }
}
