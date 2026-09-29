package com.taxonomy.setup;

import com.taxonomy.security.config.ProductionSecurityGuard;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Real ConfigData and ApplicationRunner contracts, also run by Maven. No DB or IdP is started. */
public final class SetupStartupRegressionCases {
    private static final String PRIVATE = "fixture-private-never-in-diagnostics";
    private static final List<String> failures = new ArrayList<>();
    private static int checks;

    private SetupStartupRegressionCases() { }

    public static void main(String[] args) throws Exception {
        checks = 0;
        failures.clear();
        if (args.length == 1) {
            renderedKeycloakStartup(Path.of(args[0]));
            return;
        }
        guardCases();
        nativeCases();
        modelCases();
        System.out.println("Startup review contracts: " + checks + " cases, " + failures.size() + " failures");
        if (!failures.isEmpty()) { throw new AssertionError(String.join("; ", failures)); }
    }

    private static void guardCases() {
        run("production Keycloak needs no local password", () -> startup(central(), null));
        run("Keycloak retains optional machine token validation", () -> {
            var settings = central();
            settings.put("ADMIN_PASSWORD", "short-token");
            startup(settings, ": ADMIN_PASSWORD");
        });
        run("Keycloak accepts a strong independent machine token", () -> {
            var settings = central();
            settings.put("ADMIN_PASSWORD", "fixture-strong-machine-token-2026");
            startup(settings, null);
        });
        for (String key : List.of("KEYCLOAK_CLIENT_ID", "KEYCLOAK_CLIENT_SECRET")) {
            run("Keycloak rejects missing " + key, () -> {
                var settings = central();
                settings.put("TAXONOMY_ADMIN_PASSWORD", PRIVATE);
                settings.put(key, "");
                startup(settings, "keycloak");
            });
        }
        run("Keycloak rejects local administration conflict", () -> {
            var settings = central();
            settings.put("TAXONOMY_ADMIN_PASSWORD", PRIVATE);
            settings.put("SPRING_PROFILES_ACTIVE", "postgres,production,keycloak,local-user-management");
            startup(settings, "central login");
        });
        run("Keycloak rejects insecure issuer", () -> {
            var settings = central();
            settings.put("TAXONOMY_ADMIN_PASSWORD", PRIVATE);
            settings.put("KEYCLOAK_ISSUER_URI", "http://identity.example.invalid/realm");
            startup(settings, "HTTPS");
        });
        for (String password : List.of("", "short", "replace-with-a-long-random-password", PRIVATE)) {
            run("local production password policy", () -> {
                var settings = central();
                settings.put("SPRING_PROFILES_ACTIVE", "postgres,production,local-user-management");
                settings.put("TAXONOMY_ADMIN_PASSWORD", password);
                startup(settings, password.equals(PRIVATE) ? null : "TAXONOMY_ADMIN_PASSWORD");
            });
        }
        run("local production machine token must remain distinct", () -> {
            var settings = central();
            settings.put("SPRING_PROFILES_ACTIVE", "postgres,production,local-user-management");
            settings.put("TAXONOMY_ADMIN_PASSWORD", PRIVATE);
            settings.put("ADMIN_PASSWORD", PRIVATE);
            startup(settings, "distinct machine token");
        });
    }

    private static Map<String, Object> central() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("SPRING_PROFILES_ACTIVE", "postgres,kubernetes,production,keycloak");
        settings.put("KEYCLOAK_ISSUER_URI", "https://identity.example.invalid/realms/taxonomy");
        settings.put("KEYCLOAK_JWK_SET_URI", "https://identity.example.invalid/keys");
        settings.put("KEYCLOAK_CLIENT_ID", "taxonomy-app");
        settings.put("KEYCLOAK_CLIENT_SECRET", PRIVATE);
        return settings;
    }

    private static void startup(Map<String, Object> settings, String expectedFailure) {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> isolated = new HashMap<>(settings);
        isolated.put("LOGGING_LEVEL_ROOT", "OFF");
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource("renderedEnvironment", isolated));
        SpringApplication application = new SpringApplication(ProductionSecurityGuard.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        // Exercise actual Spring constructor injection and the ApplicationRunner startup lifecycle.
        RuntimeException failure = null;
        try (var context = application.run("--logging.level.root=OFF")) {
            require(context.getBean(ProductionSecurityGuard.class) != null, "production guard must not be disabled");
        } catch (RuntimeException exception) { failure = exception; }
        if (expectedFailure == null) {
            if (failure != null) { throw new AssertionError("valid startup refused", failure); }
        } else {
            require(failure != null, "unsafe startup accepted");
            StringBuilder messages = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) { messages.append(cause.getMessage()); }
            require(messages.toString().contains(expectedFailure), "startup refused for an unrelated reason");
            require(!messages.toString().contains(PRIVATE), "diagnostic leaked credential");
        }
    }

    /** Feed the real rendered Deployment environment through Spring, not a copied profile fixture. */
    public static void renderedKeycloakStartup(Path manifest) throws Exception {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> environment = new HashMap<>();
        try (var input = Files.newInputStream(manifest)) {
            for (Object document : yaml.loadAll(input)) {
                if (!(document instanceof Map<?, ?> root) || !"Deployment".equals(root.get("kind"))) { continue; }
                Map<?, ?> spec = (Map<?, ?>) root.get("spec");
                Map<?, ?> template = (Map<?, ?>) spec.get("template");
                Map<?, ?> pod = (Map<?, ?>) template.get("spec");
                Map<?, ?> app = (Map<?, ?>) ((List<?>) pod.get("containers")).getFirst();
                for (Object entry : (List<?>) app.get("env")) {
                    Map<?, ?> item = (Map<?, ?>) entry;
                    String name = item.get("name").toString();
                    require(!name.equals("TAXONOMY_ADMIN_PASSWORD"), "Keycloak must not receive local bootstrap credential");
                    if (item.containsKey("value")) { environment.put(name, item.get("value").toString()); }
                    else if (name.equals("KEYCLOAK_CLIENT_SECRET")) {
                        Map<?, ?> source = (Map<?, ?>) item.get("valueFrom");
                        require(source.get("secretKeyRef") instanceof Map, "central credential must be a Secret reference");
                        environment.put(name, PRIVATE);
                    }
                }
            }
        }
        require(environment.containsKey("SPRING_PROFILES_ACTIVE"), "Deployment environment missing");
        require(environment.containsKey("KEYCLOAK_CLIENT_SECRET"), "central Secret mapping missing");
        startup(environment, null);
        environment.put("ADMIN_PASSWORD", "short-token");
        startup(environment, ": ADMIN_PASSWORD");
        System.out.println("Rendered Keycloak ApplicationRunner startup contracts passed");
    }

    private static void nativeCases() throws Exception {
        String oldNative = System.getProperty("taxonomy.native");
        String oldDirectory = System.getProperty("taxonomy.config-directory");
        Path parent = Files.createTempDirectory("native review ");
        try (PrintStream out = new PrintStream(new ByteArrayOutputStream())) {
            System.setProperty("taxonomy.native", "true");
            Path config = parent.resolve("settings");
            System.setProperty("taxonomy.config-directory", config.toString());
            String missing = "--spring.config.additional-location=optional:" + parent.resolve("missing.properties").toUri();
            run("native optional location cannot bypass required file", () ->
                    require(SetupCommand.execute(new String[] {missing}, out) == 2, "native fallback accepted"));
            run("native check also requires installation", () ->
                    require(SetupCommand.execute(new String[] {"--check-configuration", missing}, out) == 2, "native check fallback accepted"));
            run("help remains available before native configuration", () ->
                    require(SetupCommand.execute(new String[] {"--setup-help"}, out) == 0, "help refused"));
            LocalSetup.write(config, LocalSetup.localProperties(config.resolve("data")), Map.of("taxonomy.admin-password", PRIVATE));
            Path overlay = parent.resolve("overlay.properties");
            Files.writeString(overlay, "server.port=8182\n");
            run("native file is loaded before additional overlays", () -> {
                var env = SetupCommand.loadEnvironment(SetupCommand.applicationArguments(new String[] {
                        "--spring.config.additional-location=" + overlay.toUri()}));
                require("8182".equals(env.getProperty("server.port")), "overlay lost");
                require(PRIVATE.equals(env.getProperty("taxonomy.admin-password")), "native configtree not loaded");
                require(env.getProperty("spring.datasource.url", "").startsWith("jdbc:hsqldb:file:"), "fell back to volatile defaults");
            });
            run("JVM additional location retains native file and overlay", () -> {
                String previous = System.getProperty("spring.config.additional-location");
                System.setProperty("spring.config.additional-location", overlay.toUri().toString());
                try {
                    var env = SetupCommand.loadEnvironment(SetupCommand.applicationArguments(new String[0]));
                    require(PRIVATE.equals(env.getProperty("taxonomy.admin-password")), "required file omitted");
                    require("8182".equals(env.getProperty("server.port")), "JVM overlay lost");
                } finally { restore("spring.config.additional-location", previous); }
            });
            run("JSON additional location retains native file and overlay", () -> {
                String json = "--spring.application.json={\"spring\":{\"config\":{\"additional-location\":\""
                        + overlay.toUri() + "\"}}}";
                var env = SetupCommand.loadEnvironment(SetupCommand.applicationArguments(new String[] {json}));
                require(PRIVATE.equals(env.getProperty("taxonomy.admin-password")), "required file omitted");
                require("8182".equals(env.getProperty("server.port")), "JSON overlay lost");
            });
            run("replacement location cannot remove native installation", () -> {
                var env = SetupCommand.loadEnvironment(SetupCommand.applicationArguments(new String[] {
                        "--spring.config.location=optional:" + parent.resolve("missing.properties").toUri()}));
                require(PRIVATE.equals(env.getProperty("taxonomy.admin-password")), "native installation replaced");
            });
            run("native optional overlay retains installation", () -> {
                var env = SetupCommand.loadEnvironment(SetupCommand.applicationArguments(new String[] {missing}));
                require(PRIVATE.equals(env.getProperty("taxonomy.admin-password")), "native installation omitted");
            });
            run("native empty file cannot silently enable volatile defaults", () -> {
                Path empty = Files.createDirectory(parent.resolve("empty"));
                Files.writeString(empty.resolve("application.properties"), "# empty installation\n");
                System.setProperty("taxonomy.config-directory", empty.toString());
                try { require(SetupCommand.execute(new String[0], out) == 2, "volatile native startup accepted"); }
                finally { System.setProperty("taxonomy.config-directory", config.toString()); }
            });
            run("native command-line override still wins", () -> {
                var env = SetupCommand.loadEnvironment(SetupCommand.applicationArguments(new String[] {
                        "--spring.config.additional-location=" + overlay.toUri(), "--server.port=8183"}));
                require("8183".equals(env.getProperty("server.port")), "CLI precedence lost");
            });
            run("native auto-detected endpoint is checked before startup", () ->
                    require(SetupCommand.execute(new String[] {"--llm.provider=",
                            "--custom.llm.url=http://ai.example.invalid/v1/chat/completions",
                            "--custom.llm.model=fixture-model"}, out) == 2, "unsafe automatic endpoint accepted"));
            run("static command rejects auto-detected URL credentials without disclosure", () -> {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (PrintStream diagnostics = new PrintStream(bytes)) {
                    require(SetupCommand.execute(new String[] {"--check-configuration=static", "--llm.provider=",
                            "--custom.llm.url=https://user:" + PRIVATE + "@ai.example.invalid/v1/chat/completions",
                            "--custom.llm.model=fixture-model"}, diagnostics) == 2, "credential-bearing URL accepted");
                }
                require(bytes.toString().contains("ERROR custom.llm.url"), "wrong validation failure");
                require(!bytes.toString().contains(PRIVATE), "endpoint credential disclosed");
            });
            run("native auto-detected HTTPS endpoint remains valid", () ->
                    require(SetupCommand.execute(new String[] {"--llm.provider=",
                            "--custom.llm.url=https://ai.example.invalid/v1/chat/completions",
                            "--custom.llm.model=fixture-model"}, out) == -1, "valid automatic endpoint rejected"));
            run("native auto-detected endpoint requires model", () ->
                    require(SetupCommand.execute(new String[] {"--llm.provider=",
                            "--custom.llm.url=https://ai.example.invalid/v1/chat/completions",
                            "--custom.llm.model="}, out) == 2, "incomplete automatic configuration accepted"));
            run("native setup checks do not create database files", () -> {
                try (var entries = Files.list(config.resolve("data"))) { require(entries.count() == 0, "preflight wrote database"); }
            });
        } finally {
            restore("taxonomy.native", oldNative);
            restore("taxonomy.config-directory", oldDirectory);
            remove(parent);
        }
    }

    private static void modelCases() throws Exception {
        Path model = Files.createTempDirectory("model review ");
        try {
            Map<String, String> settings = Map.of("spring.datasource.url", "jdbc:hsqldb:mem:probe",
                    "embedding.enabled", "true", "embedding.model.dir", model.toString());
            run("missing model inputs still rejected", () -> require(modelError(settings), "empty directory accepted"));
            Files.writeString(model.resolve("model.onnx"), "readability fixture, not an inference model");
            run("tokenizer is a required input", () -> require(modelError(settings), "missing tokenizer accepted"));
            Files.writeString(model.resolve("tokenizer.json"), "{}");
            run("generated metadata is not a required input", () -> {
                require(!modelError(settings), "repairable metadata incorrectly rejected");
                require(SetupProbe.check(settings::get, Set.of()).stream().anyMatch(f ->
                        f.key().equals("embedding.serving-properties") && f.status() == SetupChecks.Status.NOT_CHECKED),
                        "metadata generation must not be claimed as verified");
                require(!Files.exists(model.resolve("serving.properties")), "probe created metadata");
            });
            Files.writeString(model.resolve("serving.properties"), "engine=OnnxRuntime\n");
            run("existing model metadata remains accepted", () -> require(!modelError(settings), "readable inputs rejected"));
        } finally { remove(model); }
    }

    private static boolean modelError(Map<String, String> settings) {
        return SetupProbe.check(settings::get, Set.of()).stream().anyMatch(f -> f.status() == SetupChecks.Status.ERROR);
    }
    private static void run(String name, Checked operation) {
        checks++;
        try { operation.run(); System.out.println("PASS: " + name); }
        catch (Exception | AssertionError failure) { failures.add(name); System.out.println("FAIL: " + name); }
    }
    private static void require(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
    private static void restore(String key, String value) {
        if (value == null) { System.clearProperty(key); } else { System.setProperty(key, value); }
    }
    private static void remove(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
        }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
