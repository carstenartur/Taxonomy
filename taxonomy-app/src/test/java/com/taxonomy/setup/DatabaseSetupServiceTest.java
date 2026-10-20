package com.taxonomy.setup;

import com.taxonomy.preferences.PreferencesService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.hibernate.tool.schema.Action;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator.ActionGrouping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.Properties;
import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseSetupServiceTest {
    private final PreferencesService preferences = mock(PreferencesService.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final DatabaseSetupService service = new DatabaseSetupService(environment, preferences);

    @Test void onlyEmbeddedHsqldbOffersTheInitialPromptUntilDismissed() {
        for (String url : new String[]{"jdbc:hsqldb:mem:demo", "jdbc:hsqldb:file:./data/demo"}) {
            environment.setProperty("spring.datasource.url", url);
            assertTrue(service.status().promptRequired());
        }
        assertEquals("hsqldb-file", service.status().database());
        assertFalse(service.status().inMemory());
        when(preferences.getBoolean("setup.database.dismissed", false)).thenReturn(true);
        assertFalse(service.status().promptRequired());
        when(preferences.getBoolean("setup.database.dismissed", false)).thenReturn(false);
        for (String url : new String[]{"jdbc:postgresql://db/demo", "jdbc:sqlserver://db", "jdbc:oracle:thin:@db:1521/demo", "jdbc:hsqldb:hsql://db/demo", "jdbc:unknown:demo"}) {
            environment.setProperty("spring.datasource.url", url);
            assertFalse(service.status().promptRequired());
        }
    }

    @Test void dismissalPersistsOnlyItsFlagUnderTheAuthenticatedActor() throws Exception {
        environment.setProperty("spring.datasource.url", "jdbc:hsqldb:mem:demo");
        service.dismiss("administrator");
        verify(preferences).update(Map.of("setup.database.dismissed", true), "administrator");
        assertEquals("jdbc:hsqldb:mem:demo", environment.getProperty("spring.datasource.url"));
        verifyNoMoreInteractions(preferences);
    }

    @Test void postgresPreviewPreservesOtherProfilesUsesValidationAndNeverIncludesAPassword() throws Exception {
        environment.setActiveProfiles("hsqldb-file", "production", "keycloak", "observability");
        environment.setProperty("spring.datasource.password", "EXISTING_SECRET_SENTINEL");
        var input = input("postgres", true, "/etc/taxonomy/db-ca.crt");
        var preview = service.preview(input);
        assertEquals("postgres,production,keycloak,observability", preview.profiles());
        assertTrue(preview.jdbcUrl().contains("sslmode=verify-full"));
        assertFalse(preview.jdbcUrl().contains("socketTimeout"));
        assertFalse(preview.jdbcUrl().contains("loginTimeout"));
        assertTrue(preview.jdbcUrl().contains("sslrootcert=%2Fetc%2Ftaxonomy%2Fdb-ca.crt"));
        Properties properties = new Properties(); properties.load(new StringReader(preview.properties()));
        assertEquals("validate", properties.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("validate", properties.getProperty("spring.jpa.properties.hibernate.hbm2ddl.auto"));
        assertEquals(org.hibernate.tool.schema.Action.VALIDATE, org.hibernate.tool.schema.Action.interpretJpaSetting(
                properties.getProperty("spring.jpa.properties.jakarta.persistence.schema-generation.database.action")));
        assertEquals(preview.jdbcUrl(), properties.getProperty("spring.datasource.url"));
        assertFalse(properties.containsKey("spring.datasource.password"));
        assertFalse(preview.toString().contains("PRIVATE_SENTINEL"));
        assertFalse(preview.toString().contains("EXISTING_SECRET_SENTINEL"));
        assertTrue(preview.findings().stream().noneMatch(f -> f.key().startsWith("authentication.") || f.key().startsWith("llm.")));
        assertTrue(preview.findings().stream().noneMatch(f -> f.status() == SetupChecks.Status.ERROR));
        assertFalse(input.toString().contains("PRIVATE_SENTINEL"));
        assertTrue(preview.helmValues().contains("SPRING_PROFILES_ACTIVE: \"postgres,production,keycloak,observability,kubernetes\""));
        assertTrue(preview.helmValues().contains("TAXONOMY_DDL_AUTO: \"validate\""));
        assertTrue(preview.helmValues().contains("database:\n  type: existing\n  url: \"\"\n  username: \"\""));
        assertTrue(preview.helmValues().contains("# SPRING_DATASOURCE_URL="));
        verifyNoInteractions(preferences);
    }

    @Test void nativeSchemaOverridesBlockExportsWithoutReflectingTheirValues() {
        for (String key : new String[]{"spring.jpa.properties.hibernate.hbm2ddl.auto",
                "spring.jpa.properties.jakarta.persistence.schema-generation.database.action",
                "spring.jpa.properties.javax.persistence.schema-generation.database.action",
                "spring.jpa.properties.[hibernate.hbm2ddl.auto]",
                "spring.jpa.properties.[jakarta.persistence.schema-generation.database.action]",
                "spring.jpa.properties.[javax.persistence.schema-generation.database.action]"}) {
            for (String value : new String[]{"create-drop", "drop-and-create", "none", "", "PRIVATE_SENTINEL"}) {
                MockEnvironment conflict = new MockEnvironment().withProperty(key, value);
                RuntimeException failure = assertThrows(RuntimeException.class,
                        () -> new DatabaseSetupService(conflict, preferences).preview(input("postgres", true, "")));
                assertTrue(failure.getMessage().contains(key.replace("[", "").replace("]", "")));
                assertFalse(failure.getMessage().contains("PRIVATE_SENTINEL"));
                assertEquals(value, conflict.getProperty(key));
            }
        }
        verifyNoInteractions(preferences);
    }

    @Test void higherPrecedenceJsonJvmAndCommandLineSchemaActionsBlockExport() {
        for (String kind : new String[]{"json", "jvm", "commandLine", "commandLineRelaxed"}) {
            StandardEnvironment effective = isolatedEnvironment(Map.of());
            if (kind.equals("json")) {
                effective.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("SPRING_APPLICATION_JSON", "{\"spring\":{\"jpa\":{\"hibernate\":{\"ddl-auto\":\"create-drop\"}}}}")));
                new org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor()
                        .postProcessEnvironment(effective, new org.springframework.boot.SpringApplication(DatabaseSetupService.class));
            } else if (kind.equals("jvm")) {
                effective.getPropertySources().addFirst(new MapPropertySource("systemProperties", Map.of(
                        "spring.jpa.hibernate.ddl-auto", "create-drop")));
            } else {
                effective.getPropertySources().addFirst(new org.springframework.core.env.SimpleCommandLinePropertySource(
                        kind.equals("commandLineRelaxed") ? "--spring.jpa.hibernate.ddlAuto=create-drop" : "--spring.jpa.hibernate.ddl-auto=create-drop"));
            }
            ConfigurationPropertySources.attach(effective);
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> new DatabaseSetupService(effective, preferences).preview(input("postgres", true, "")), kind);
            assertTrue(failure.getMessage().contains("spring.jpa.hibernate.ddl-auto"));
        }
    }

    @Test void mainHsqldbCreateDefaultRemainsExportableWhileHigherPrecedenceValidateWins() {
        StandardEnvironment defaults = isolatedEnvironment(Map.of());
        org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.applyTo(defaults);
        assertEquals("create", defaults.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertDoesNotThrow(() -> new DatabaseSetupService(defaults, preferences).preview(input("postgres", true, "")));
        defaults.getPropertySources().addFirst(new MapPropertySource("lowerJvmOverride", Map.of("spring.jpa.hibernate.ddl-auto", "create-drop")));
        defaults.getPropertySources().addFirst(new org.springframework.core.env.SimpleCommandLinePropertySource("--spring.jpa.hibernate.ddl-auto=validate"));
        assertDoesNotThrow(() -> new DatabaseSetupService(defaults, preferences).preview(input("postgres", true, "")));
    }

    @Test void connectionCheckStillOpensItsIndependentConnectionWhenExportIsBlocked() throws Exception {
        for (String key : new String[]{"spring.jpa.properties.hibernate.hbm2ddl.auto",
                "spring.jpa.properties.javax.persistence.schema-generation.database.action",
                "spring.jpa.properties.[javax.persistence.schema-generation.database.action]"}) {
            MockEnvironment conflict = new MockEnvironment().withProperty(key, "create-drop");
            var conflictedService = new DatabaseSetupService(conflict, preferences);
            // A local fixture accepts and closes a real pgJDBC handshake; no external DB or active datasource is used.
            try (var listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
                var accepted = new java.util.concurrent.CompletableFuture<Void>();
                Thread.startVirtualThread(() -> {
                    try (var socket = listener.accept()) { accepted.complete(null); }
                    catch (java.io.IOException failure) { accepted.completeExceptionally(failure); }
                });
                var result = conflictedService.check(new DatabaseSetupService.ConnectionSettings("postgres", "127.0.0.1", listener.getLocalPort(),
                        "taxonomy", "taxonomy", "PRIVATE_SENTINEL", false, ""));
                accepted.get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(result.findings().stream().anyMatch(f -> f.key().equals("database.connection")));
                assertTrue(result.findings().stream().anyMatch(f -> f.key().equals("database.schema-overrides") && f.status() == SetupChecks.Status.WARNING), key);
                assertFalse(result.toString().contains("PRIVATE_SENTINEL"));
            }
            assertThrows(RuntimeException.class, () -> conflictedService.preview(input("postgres", true, "")), key);
            assertEquals("create-drop", conflict.getProperty(key));
        }
        verifyNoInteractions(preferences);
    }

    @Test void safeHelmValuesSurviveTheActualShellLauncherAndBindToHibernateValidation(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of("/bin/sh")), "Docker launcher uses POSIX sh");
        var generated = service.preview(input("postgres", true, ""));
        Map<?, ?> values = new Yaml(new SafeConstructor(new LoaderOptions())).load(generated.helmValues());
        java.nio.file.Path javaFixture = directory.resolve("java");
        java.nio.file.Files.writeString(javaFixture, "#!/bin/sh\nprintf 'ARG=%s\\n' \"$@\"\nexec /usr/bin/env\n");
        assertTrue(javaFixture.toFile().setExecutable(true));
        // Exercise the Dockerfile's exact command with an isolated, harmless executable fixture.
        var launcher = new ProcessBuilder("sh", "-c", "exec java $JAVA_OPTS -jar app.jar").redirectErrorStream(true);
        launcher.environment().clear();
        launcher.environment().put("PATH", directory + ":/usr/bin:/bin");
        launcher.environment().put("JAVA_OPTS", "-Dfixture.keep=unchanged");
        String retainedJson = "{\"spring\":{\"jpa\":{\"properties\":{\"jakarta.persistence.schema-generation.database.action\":\"validate\"}}},\"fixture\":\"kept\"}";
        launcher.environment().put("SPRING_APPLICATION_JSON", retainedJson);
        ((Map<?, ?>) values.get("config")).forEach((key, value) -> launcher.environment().put(key.toString(), value.toString()));
        Process process = launcher.start();
        assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
        String transported = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue());
        assertTrue(transported.contains("ARG=-Dfixture.keep=unchanged\nARG=-jar\nARG=app.jar"));
        Map<String, Object> effectiveEnvironment = new LinkedHashMap<>();
        for (String line : transported.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0 && !line.startsWith("ARG=")) { effectiveEnvironment.put(line.substring(0, separator), line.substring(separator + 1)); }
        }
        assertEquals(retainedJson, effectiveEnvironment.get("SPRING_APPLICATION_JSON"));
        assertEquals("-Dfixture.keep=unchanged", effectiveEnvironment.get("JAVA_OPTS"));
        assertTrue(((Map<?, ?>) values.get("config")).keySet().stream().allMatch(key -> key.toString().matches("[A-Z][A-Z0-9_]*")));
        StandardEnvironment effective = isolatedEnvironment(effectiveEnvironment);
        new org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor()
                .postProcessEnvironment(effective, new org.springframework.boot.SpringApplication(DatabaseSetupService.class));
        effective.getPropertySources().addLast(new MapPropertySource("applicationDefaults", Map.of("spring.jpa.hibernate.ddl-auto", "${TAXONOMY_DDL_AUTO:create}")));
        ConfigurationPropertySources.attach(effective);
        Map<String, String> nativeSettings = Binder.get(effective)
                .bind("spring.jpa.properties", Bindable.mapOf(String.class, String.class)).get();
        assertEquals("validate", effective.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("validate", nativeSettings.get("hibernate.hbm2ddl.auto"));
        assertEquals("validate", nativeSettings.get("jakarta.persistence.schema-generation.database.action"));
        assertEquals(Action.VALIDATE, ActionGrouping.interpret(nativeSettings).databaseAction());
        assertDoesNotThrow(() -> new DatabaseSetupService(effective, preferences).preview(input("postgres", true, "")));
    }

    private static StandardEnvironment isolatedEnvironment(Map<String, Object> settings) {
        StandardEnvironment result = new StandardEnvironment();
        result.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        result.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, settings));
        return result;
    }

    @Test void sqlServerIpv6UrlParsesWithTheBundledDriverWithoutCorruptingHostOrPort() throws Exception {
        var preview = service.preview(new DatabaseSetupService.ConnectionSettings("mssql", "[::1]", 1433,
                "taxonomy", "taxonomy", "PRIVATE_SENTINEL", true, ""));
        Map<String, String> parsed = new LinkedHashMap<>();
        for (var property : new com.microsoft.sqlserver.jdbc.SQLServerDriver().getPropertyInfo(preview.jdbcUrl(), new Properties())) {
            if (Set.of("serverName", "portNumber", "databaseName").contains(property.name)) { parsed.put(property.name, property.value); }
        }
        assertEquals("::1", parsed.get("serverName"));
        assertEquals("1433", parsed.get("portNumber"));
        assertEquals("taxonomy", parsed.get("databaseName"));
    }

    @Test void sqlServerAndOracleUseVerifiedTlsAndRetainEvaluationWarnings() {
        var mssql = service.preview(input("mssql", true, ""));
        assertTrue(mssql.jdbcUrl().contains("encrypt=true;trustServerCertificate=false"));
        var oracle = service.preview(input("oracle", true, ""));
        assertTrue(oracle.jdbcUrl().contains("(PROTOCOL=TCPS)"));
        assertTrue(oracle.jdbcUrl().contains("SSL_SERVER_DN_MATCH=YES"));
        for (var result : new DatabaseSetupService.Preview[]{mssql, oracle}) {
            assertFalse(result.jdbcUrl().contains("Timeout"));
            assertTrue(result.findings().stream().anyMatch(f -> f.key().equals("database.support") && f.status() == SetupChecks.Status.WARNING));
            assertFalse(result.toString().contains("PRIVATE_SENTINEL"));
        }
    }

    @Test void omittedTlsDefaultsToVerificationAndDisabledTlsHasAnExplicitWarning() {
        assertTrue(service.preview(new DatabaseSetupService.ConnectionSettings("postgres", "db.example.test", 5432, "taxonomy", "taxonomy", null, null, null)).jdbcUrl().contains("sslmode=verify-full"));
        var preview = service.preview(input("postgres", false, ""));
        assertTrue(preview.jdbcUrl().contains("sslmode=disable"));
        assertTrue(preview.findings().stream().anyMatch(f -> f.key().equals("database.tls") && f.status() == SetupChecks.Status.WARNING));
    }

    @Test void delimiterInjectionInvalidPortsAndUnsupportedCaPathsAreRejectedWithoutEcho() {
        for (var input : new DatabaseSetupService.ConnectionSettings[]{
                new DatabaseSetupService.ConnectionSettings("postgres", "db?password=PRIVATE_SENTINEL", 5432, "taxonomy", "u", "PRIVATE_SENTINEL", true, ""),
                new DatabaseSetupService.ConnectionSettings("mssql", "db", 1433, "taxonomy;password=PRIVATE_SENTINEL", "u", "PRIVATE_SENTINEL", true, ""),
                new DatabaseSetupService.ConnectionSettings("oracle", "db", 1521, "demo)(PASSWORD=PRIVATE_SENTINEL)", "u", "PRIVATE_SENTINEL", true, ""),
                new DatabaseSetupService.ConnectionSettings("postgres", "db", 65536, "taxonomy", "u", "PRIVATE_SENTINEL", true, ""),
                new DatabaseSetupService.ConnectionSettings("postgres", "db", 5432, "taxonomy", "u\nspring.datasource.password=PRIVATE_SENTINEL", "PRIVATE_SENTINEL", true, ""),
                input("mssql", true, "/tmp/PRIVATE_SENTINEL"),
                input("postgres", true, "/tmp/../PRIVATE_SENTINEL")}) {
            var error = assertThrows(IllegalArgumentException.class, () -> service.preview(input));
            assertFalse(error.getMessage().contains("PRIVATE_SENTINEL"));
        }
    }

    @Test void deploymentManagementIsDetectedFromKubernetesProfile() {
        environment.setActiveProfiles("hsqldb", "kubernetes");
        assertTrue(service.status().deploymentManaged());
    }

    private DatabaseSetupService.ConnectionSettings input(String type, Boolean tls, String ca) {
        return new DatabaseSetupService.ConnectionSettings(type, "db.example.test", type.equals("mssql") ? 1433 : type.equals("oracle") ? 1521 : 5432, "taxonomy", "taxonomy", "PRIVATE_SENTINEL", tls, ca);
    }
}
